"""Snapshot archive codec.

Spec: ../app/docs/architecture/OPTIMALX_LINK.md §archive-format.

Wire-compatible with the Kotlin `BackupArchive` object on the Android side
(``app/src/main/java/com/example/optimalx/data/backup/BackupArchive.kt``).
The two implementations exchange identical ``.zip`` bytes:

    manifest.json
    database/optimalx.db
    files/optimalx_files/<attachment>...
    files/workshop/<project_id>/<file>...

This module is intentionally side-effect-free and GUI-free so it can be unit
tested without spinning up a Qt application or a real network.
"""

from __future__ import annotations

import io
import json
import zipfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import BinaryIO

from . import (
    CURRENT_FORMAT_VERSION,
    DB_DIR_PREFIX,
    FILES_ATTACHMENTS_PREFIX,
    FILES_WORKSHOP_PREFIX,
    MANIFEST_ENTRY,
)


@dataclass(frozen=True)
class Manifest:
    """Parsed ``manifest.json`` contents.

    Mirrors ``BackupManifest`` in the Android codebase. All fields except
    ``format_version`` have defaults so older or newer archives can still
    parse cleanly — provided no field is ever repurposed without bumping
    ``format_version``.
    """

    format_version: int
    export_epoch_ms: int = 0
    app_version_name: str = ""
    db_version: int = 0
    includes_files: bool = False

    @staticmethod
    def from_json(raw: dict) -> Manifest:
        """Parse a dict (typically already-loaded JSON) into a Manifest.

        Unknown keys are ignored so a phone running a newer build that has
        added manifest fields still produces a snapshot the desktop can list.
        Missing optional keys fall back to defaults.
        """
        return Manifest(
            format_version=int(raw.get("formatVersion", 0)),
            export_epoch_ms=int(raw.get("exportEpochMs", 0)),
            app_version_name=str(raw.get("appVersionName", "")),
            db_version=int(raw.get("dbVersion", 0)),
            includes_files=bool(raw.get("includesFiles", False)),
        )

    def to_json(self) -> dict:
        """Emit a dict suitable for ``json.dump`` that round-trips through
        the Android side. Keys use Kotlin's camelCase to match the wire
        format produced by ``kotlinx.serialization``.
        """
        return {
            "formatVersion": self.format_version,
            "exportEpochMs": self.export_epoch_ms,
            "appVersionName": self.app_version_name,
            "dbVersion": self.db_version,
            "includesFiles": self.includes_files,
        }


class ArchiveError(Exception):
    """Raised when an archive cannot be parsed or is structurally invalid."""


class ZipSlipError(ArchiveError):
    """Raised when an archive contains an entry that resolves outside the
    destination directory. Same protection the Kotlin side enforces; without
    it a malicious zip could write to arbitrary filesystem paths."""


@dataclass(frozen=True)
class ExtractedSnapshot:
    """Result of :func:`extract_snapshot_zip`. ``root`` is the directory the
    archive was unpacked into; callers walk it through ``snapshot_lib`` to
    enumerate database / attachments / workshop projects.
    """

    root: Path
    manifest: Manifest
    entry_names: list[str] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Write side
# ---------------------------------------------------------------------------


def write_snapshot_zip(
    out: BinaryIO,
    manifest: Manifest,
    db_path: Path | None,
    attachments_dir: Path | None,
    workshop_dir: Path | None,
    db_entry_name: str = "optimalx.db",
) -> None:
    """Stream a complete snapshot archive into the binary file-like ``out``.

    The caller owns ``out`` and is responsible for closing it. Files are added
    in the same canonical order the Kotlin writer uses so byte-for-byte
    comparison is meaningful in golden-file tests:

        1. ``manifest.json``
        2. ``database/<db_entry_name>`` (if ``db_path`` is given)
        3. ``files/optimalx_files/...`` (if ``attachments_dir`` exists)
        4. ``files/workshop/...`` (if ``workshop_dir`` exists)

    A directory that doesn't exist or is empty is silently skipped — matching
    the Android writer which treats missing optional directories as "nothing
    to include".
    """
    with zipfile.ZipFile(out, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        _write_manifest(zf, manifest)
        if db_path is not None:
            if not db_path.is_file():
                raise ArchiveError(f"db_path is not a file: {db_path}")
            zf.write(db_path, arcname=f"{DB_DIR_PREFIX}{db_entry_name}")
        if attachments_dir is not None and attachments_dir.is_dir():
            _add_directory(zf, attachments_dir, FILES_ATTACHMENTS_PREFIX)
        if workshop_dir is not None and workshop_dir.is_dir():
            _add_directory(zf, workshop_dir, FILES_WORKSHOP_PREFIX)


def _write_manifest(zf: zipfile.ZipFile, manifest: Manifest) -> None:
    body = json.dumps(manifest.to_json(), separators=(",", ":"), ensure_ascii=False)
    info = zipfile.ZipInfo(MANIFEST_ENTRY)
    info.compress_type = zipfile.ZIP_DEFLATED
    zf.writestr(info, body.encode("utf-8"))


def _add_directory(zf: zipfile.ZipFile, source: Path, prefix: str) -> None:
    """Recursively add every regular file under ``source`` to the zip with
    entry names rooted at ``prefix``. Symlinks and special files are skipped
    on purpose — the snapshot format only carries plain bytes.
    """
    for path in sorted(source.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(source).as_posix()
        zf.write(path, arcname=f"{prefix}{relative}")


# ---------------------------------------------------------------------------
# Read side
# ---------------------------------------------------------------------------


def extract_snapshot_zip(source: BinaryIO | bytes, dest_dir: Path) -> ExtractedSnapshot:
    """Extract a snapshot archive from ``source`` into ``dest_dir``.

    Returns the parsed manifest and every entry name observed in the archive.
    Caller is responsible for cleaning up ``dest_dir`` (use a temp dir for
    transient extracts).

    Raises:
        ZipSlipError: when an entry resolves outside ``dest_dir``.
        ArchiveError: when the archive contains no ``manifest.json`` or its
            JSON is invalid.
    """
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest_abs = dest_dir.resolve()

    if isinstance(source, (bytes, bytearray)):
        source = io.BytesIO(source)

    entry_names: list[str] = []
    manifest_bytes: bytes | None = None
    manifest_path: str | None = None

    with zipfile.ZipFile(source, mode="r") as zf:
        for info in zf.infolist():
            entry_names.append(info.filename)
            target = (dest_abs / info.filename).resolve()
            # Zip-slip guard: every extracted path must stay inside dest_dir.
            if dest_abs != target and dest_abs not in target.parents:
                raise ZipSlipError(
                    f"entry {info.filename!r} resolves outside {dest_abs}"
                )
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, open(target, "wb") as dst:
                while True:
                    chunk = src.read(64 * 1024)
                    if not chunk:
                        break
                    dst.write(chunk)
            # Track the shallowest manifest.json so wrapper folders still parse.
            if _is_manifest_entry(info.filename):
                if manifest_path is None or _depth(info.filename) < _depth(manifest_path):
                    manifest_bytes = target.read_bytes()
                    manifest_path = info.filename

    if manifest_bytes is None:
        raise ArchiveError(
            "Archive is missing manifest.json. The .zip may have been re-archived "
            "or is not an OptimalX snapshot."
        )
    try:
        parsed = json.loads(manifest_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ArchiveError(f"manifest.json could not be parsed: {exc}") from exc
    if not isinstance(parsed, dict):
        raise ArchiveError("manifest.json must be a JSON object")
    manifest = Manifest.from_json(parsed)
    return ExtractedSnapshot(root=dest_abs, manifest=manifest, entry_names=entry_names)


def read_manifest(snapshot_dir: Path) -> Manifest:
    """Read the manifest from an already-extracted snapshot folder.

    Looks for ``manifest.json`` directly under ``snapshot_dir`` (the canonical
    place for desktop-side storage). For archives extracted with a wrapper
    folder, callers should use :func:`extract_snapshot_zip` which already
    finds the shallowest manifest for you.
    """
    path = snapshot_dir / MANIFEST_ENTRY
    if not path.is_file():
        raise ArchiveError(f"No manifest.json under {snapshot_dir}")
    try:
        parsed = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ArchiveError(f"manifest.json could not be parsed: {exc}") from exc
    if not isinstance(parsed, dict):
        raise ArchiveError("manifest.json must be a JSON object")
    return Manifest.from_json(parsed)


def fresh_manifest(app_version: str, db_version: int, includes_files: bool = True) -> Manifest:
    """Build a manifest stamped with the current epoch for use by the writer
    side. Centralizes the ``CURRENT_FORMAT_VERSION`` constant so callers don't
    accidentally drift from the Android side.
    """
    import time

    return Manifest(
        format_version=CURRENT_FORMAT_VERSION,
        export_epoch_ms=int(time.time() * 1000),
        app_version_name=app_version,
        db_version=db_version,
        includes_files=includes_files,
    )


def manifest_as_pretty_json(manifest: Manifest) -> str:
    """Return a human-readable rendering of ``manifest`` for the GUI detail
    pane. Two-space indent matches the formatting Android Studio's pretty
    printer produces so opening the same file in either editor looks the same.
    """
    return json.dumps(manifest.to_json(), indent=2, ensure_ascii=False)


def _is_manifest_entry(name: str) -> bool:
    """True if a zip entry name is the manifest.json — anywhere in the tree."""
    return name == MANIFEST_ENTRY or name.endswith(f"/{MANIFEST_ENTRY}")


def _depth(name: str) -> int:
    """Slash-separated path depth, used to find the shallowest manifest when
    an archive happened to be wrapped in an extra folder."""
    return name.count("/")


__all__ = [
    "ArchiveError",
    "ExtractedSnapshot",
    "Manifest",
    "ZipSlipError",
    "extract_snapshot_zip",
    "fresh_manifest",
    "manifest_as_pretty_json",
    "read_manifest",
    "write_snapshot_zip",
]


# Provide a Manifest helper for tests that want to construct from kwargs
# without depending on dataclasses' replace.
Manifest.__module__ = __name__


# Suppress unused-import warning for `asdict` — kept around because it's the
# most useful debug helper when poking at manifests in a REPL.
_ = asdict
