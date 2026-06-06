"""Filesystem CRUD over the local snapshot library.

Spec: ../app/docs/architecture/OPTIMALX_LINK.md §desktop-app.

A "snapshot" on the desktop is a plain folder under
``~/OptimalX-Link/snapshots/<id>/``:

    <id>/
        manifest.json
        database/optimalx.db
        files/optimalx_files/...
        files/workshop/<project_id>/...

The folder name is also the snapshot id, and is derived from a UTC timestamp.
Keeping the layout as plain folders (not zips) means the user can open the
file manager and dig through any snapshot without going through the GUI —
that's the main escape hatch this whole subsystem exists to provide.
"""

from __future__ import annotations

import shutil
import tempfile
import time
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import BinaryIO

from . import DB_DIR_PREFIX, FILES_ATTACHMENTS_PREFIX, FILES_WORKSHOP_PREFIX
from .archive import (
    ArchiveError,
    Manifest,
    extract_snapshot_zip,
    read_manifest,
    write_snapshot_zip,
)

# Snapshot folder names look like ``2026-05-26_22-12-04`` — sorts lex == sorts
# chronologically and stays readable in `ls`. UTC keeps the ordering correct
# across DST boundaries even if the user travels.
_SNAPSHOT_ID_FORMAT: str = "%Y-%m-%d_%H-%M-%S"


@dataclass(frozen=True)
class SnapshotEntry:
    """One row in the desktop snapshot library.

    ``size_bytes`` is the recursive sum of every file under the snapshot
    folder — used by the GUI to display human-readable disk usage.
    """

    id: str
    path: Path
    epoch_ms: int
    manifest: Manifest
    size_bytes: int
    workshop_project_count: int
    attachment_count: int

    def to_json(self) -> dict:
        return {
            "id": self.id,
            "epochMs": self.epoch_ms,
            "appVersion": self.manifest.app_version_name,
            "dbVersion": self.manifest.db_version,
            "formatVersion": self.manifest.format_version,
            "sizeBytes": self.size_bytes,
            "workshopProjectCount": self.workshop_project_count,
            "attachmentCount": self.attachment_count,
        }


def make_snapshot_id(epoch_ms: int | None = None) -> str:
    """Return a snapshot id derived from a UTC timestamp.

    Idempotent for a given epoch; two calls in the same second collide,
    which is exactly what :func:`reserve_snapshot_dir` handles by suffixing
    a disambiguator.
    """
    if epoch_ms is None:
        epoch_ms = int(time.time() * 1000)
    return datetime.fromtimestamp(epoch_ms / 1000, tz=UTC).strftime(_SNAPSHOT_ID_FORMAT)


def reserve_snapshot_dir(root: Path, epoch_ms: int | None = None) -> Path:
    """Create and return a fresh empty snapshot directory under ``root``.

    Adds a numeric suffix when a same-second collision occurs so two rapid
    pulls cannot stomp on each other. The directory is created with mode 700
    so other accounts on a shared box can't read snapshot contents.
    """
    root.mkdir(parents=True, exist_ok=True)
    base = make_snapshot_id(epoch_ms)
    candidate = root / base
    suffix = 1
    while candidate.exists():
        candidate = root / f"{base}_{suffix}"
        suffix += 1
    candidate.mkdir(mode=0o700, parents=False, exist_ok=False)
    return candidate


def parse_snapshot_id_to_epoch_ms(snapshot_id: str) -> int | None:
    """Inverse of :func:`make_snapshot_id`. Returns None if the id doesn't
    parse — callers fall back to the folder's mtime in that case.

    Tolerates the ``_N`` collision-suffix by parsing only the leading
    timestamp portion.
    """
    head = snapshot_id.split("_", 2)
    if len(head) < 2:
        return None
    # `_SNAPSHOT_ID_FORMAT` uses one underscore between date and time, so the
    # first two pieces concatenated are the canonical id.
    parsed_str = f"{head[0]}_{head[1]}"
    try:
        dt = datetime.strptime(parsed_str, _SNAPSHOT_ID_FORMAT).replace(tzinfo=UTC)
    except ValueError:
        return None
    return int(dt.timestamp() * 1000)


def list_snapshots(root: Path) -> list[SnapshotEntry]:
    """Enumerate every snapshot under ``root`` sorted newest-first.

    Folders that fail to parse (missing or invalid manifest, etc.) are
    skipped silently rather than poisoning the whole listing. They still
    exist on disk — the user can inspect them in the file manager.
    """
    if not root.is_dir():
        return []
    entries: list[SnapshotEntry] = []
    for child in root.iterdir():
        if not child.is_dir():
            continue
        try:
            entry = _entry_for(child)
        except ArchiveError:
            continue
        entries.append(entry)
    entries.sort(key=lambda e: e.epoch_ms, reverse=True)
    return entries


def _entry_for(path: Path) -> SnapshotEntry:
    """Build a :class:`SnapshotEntry` for an existing snapshot folder.

    Raises ArchiveError when the folder lacks a manifest — callers in
    :func:`list_snapshots` swallow that to skip corrupt folders without
    failing the whole enumeration.
    """
    manifest = read_manifest(path)
    size = _directory_size(path)
    workshop_dir = path / FILES_WORKSHOP_PREFIX
    attachments_dir = path / FILES_ATTACHMENTS_PREFIX
    workshop_count = (
        sum(1 for child in workshop_dir.iterdir() if child.is_dir())
        if workshop_dir.is_dir()
        else 0
    )
    attachment_count = _count_files(attachments_dir)
    # Prefer the manifest's authoritative epoch; fall back to the folder name
    # parser, then to mtime as a last resort. mtime is unreliable when a user
    # has rsync'd snapshots between hosts.
    epoch_ms = manifest.export_epoch_ms
    if epoch_ms == 0:
        parsed = parse_snapshot_id_to_epoch_ms(path.name)
        epoch_ms = parsed if parsed is not None else int(path.stat().st_mtime * 1000)
    return SnapshotEntry(
        id=path.name,
        path=path,
        epoch_ms=epoch_ms,
        manifest=manifest,
        size_bytes=size,
        workshop_project_count=workshop_count,
        attachment_count=attachment_count,
    )


def get_snapshot(root: Path, snapshot_id: str) -> SnapshotEntry:
    """Resolve a single snapshot by id. Raises KeyError when absent —
    HTTP layer maps that to ``404``.

    Rejects ids containing path separators to keep ``/v1/snapshots/{id}/...``
    safe against traversal attempts.
    """
    if "/" in snapshot_id or "\\" in snapshot_id or snapshot_id in ("", ".", ".."):
        raise KeyError(snapshot_id)
    path = root / snapshot_id
    if not path.is_dir():
        raise KeyError(snapshot_id)
    return _entry_for(path)


def delete_snapshot(root: Path, snapshot_id: str) -> None:
    """Remove a snapshot folder. Raises KeyError when absent. Best-effort
    recursive delete; the OS error surfaces if the folder is read-only.
    """
    entry = get_snapshot(root, snapshot_id)
    shutil.rmtree(entry.path)


def import_snapshot_from_zip(root: Path, archive_source: BinaryIO | bytes) -> SnapshotEntry:
    """Extract a snapshot zip into a fresh folder under ``root``.

    Streams to a temp directory first so a partial decode never leaves a
    half-written snapshot in the library. On success, atomically renames
    the temp directory into its final slot. The returned entry is the same
    shape :func:`list_snapshots` produces.

    The manifest's epoch (when present) overrides the wall-clock time so the
    snapshot folder name reflects the moment the phone exported, not the
    moment the desktop received.
    """
    with tempfile.TemporaryDirectory(prefix="optimalx-link-stage-", dir=str(root.parent)) as staging:
        staging_root = Path(staging) / "snapshot"
        staging_root.mkdir()
        extracted = extract_snapshot_zip(archive_source, staging_root)
        epoch_ms = extracted.manifest.export_epoch_ms or None
        final_dir = reserve_snapshot_dir(root, epoch_ms=epoch_ms)
        # Move children rather than the staging root so we land cleanly in
        # ``final_dir`` (which is already an empty folder).
        for child in extracted.root.iterdir():
            shutil.move(str(child), str(final_dir / child.name))
        return _entry_for(final_dir)


def stream_snapshot_zip(entry: SnapshotEntry, out: BinaryIO) -> None:
    """Repackage a snapshot folder as a zip on the wire.

    Used by the desktop's ``GET /v1/snapshots/{id}/bundle`` endpoint and by
    the Phase 4 **Restore to phone** flow. We rebuild the archive from the
    folder contents on each call so the user can hand-edit a snapshot folder
    and still re-send it (e.g., trimming a workshop project before restore).
    """
    db_file = _first_file_under(entry.path / DB_DIR_PREFIX)
    attachments = entry.path / FILES_ATTACHMENTS_PREFIX
    workshop = entry.path / FILES_WORKSHOP_PREFIX
    db_entry_name = db_file.name if db_file is not None else "optimalx.db"
    write_snapshot_zip(
        out=out,
        manifest=entry.manifest,
        db_path=db_file,
        attachments_dir=attachments if attachments.is_dir() else None,
        workshop_dir=workshop if workshop.is_dir() else None,
        db_entry_name=db_entry_name,
    )


def _first_file_under(directory: Path) -> Path | None:
    """Return any regular file directly under ``directory``, or None when
    the directory is missing or empty. We use this for the database entry
    because the canonical layout has exactly one file under ``database/``.
    """
    if not directory.is_dir():
        return None
    for child in sorted(directory.iterdir()):
        if child.is_file():
            return child
    return None


def _directory_size(root: Path) -> int:
    """Recursive size in bytes. Skips broken symlinks and special files."""
    total = 0
    for path in root.rglob("*"):
        try:
            if path.is_file() and not path.is_symlink():
                total += path.stat().st_size
        except OSError:
            continue
    return total


def _count_files(root: Path) -> int:
    if not root.is_dir():
        return 0
    count = 0
    for path in root.rglob("*"):
        if path.is_file() and not path.is_symlink():
            count += 1
    return count


def iter_entries(root: Path) -> Iterable[SnapshotEntry]:
    """Streaming variant of :func:`list_snapshots` for callers that want
    lazy iteration. Sort order is identical.
    """
    yield from list_snapshots(root)


__all__ = [
    "SnapshotEntry",
    "delete_snapshot",
    "get_snapshot",
    "import_snapshot_from_zip",
    "iter_entries",
    "list_snapshots",
    "make_snapshot_id",
    "parse_snapshot_id_to_epoch_ms",
    "reserve_snapshot_dir",
    "stream_snapshot_zip",
]
