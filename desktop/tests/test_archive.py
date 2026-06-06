"""Roundtrip tests for the snapshot archive codec.

These tests verify wire-format compatibility with the Kotlin
``BackupArchive`` object. If a test breaks here, either the Python side has
drifted from the spec or the Android side has. Cross-check both before
"fixing" the test.
"""

from __future__ import annotations

import io
import json
import zipfile
from pathlib import Path

import pytest

from optimalx_link import (
    CURRENT_FORMAT_VERSION,
    DB_DIR_PREFIX,
    FILES_ATTACHMENTS_PREFIX,
    FILES_WORKSHOP_PREFIX,
    MANIFEST_ENTRY,
)
from optimalx_link.archive import (
    ArchiveError,
    Manifest,
    ZipSlipError,
    extract_snapshot_zip,
    fresh_manifest,
    read_manifest,
    write_snapshot_zip,
)


def _make_payload(tmp_path: Path) -> tuple[Path, Path, Path]:
    """Build a tiny on-disk payload (db + attachments + workshop) for the
    writer to consume. Returns (db_file, attachments_dir, workshop_dir).
    """
    db = tmp_path / "optimalx.db"
    db.write_bytes(b"SQLite format 3\x00" + b"\x00" * 64)

    attachments = tmp_path / "attachments"
    attachments.mkdir()
    (attachments / "note-1.txt").write_text("first note")
    (attachments / "subdir").mkdir()
    (attachments / "subdir" / "note-2.txt").write_text("second note")

    workshop = tmp_path / "workshop"
    workshop.mkdir()
    project = workshop / "project-7"
    project.mkdir()
    (project / "index.html").write_text("<h1>hi</h1>")
    (project / "script.js").write_text("console.log(1)")

    return db, attachments, workshop


def test_roundtrip_full_payload(tmp_path: Path) -> None:
    db, attachments, workshop = _make_payload(tmp_path)
    manifest = fresh_manifest(app_version="1.2.3", db_version=18, includes_files=True)

    buffer = io.BytesIO()
    write_snapshot_zip(
        out=buffer,
        manifest=manifest,
        db_path=db,
        attachments_dir=attachments,
        workshop_dir=workshop,
    )
    buffer.seek(0)

    extracted = extract_snapshot_zip(buffer, tmp_path / "out")
    assert extracted.manifest.format_version == CURRENT_FORMAT_VERSION
    assert extracted.manifest.app_version_name == "1.2.3"
    assert extracted.manifest.db_version == 18
    assert extracted.manifest.includes_files is True
    assert extracted.manifest.export_epoch_ms > 0

    out_db = tmp_path / "out" / DB_DIR_PREFIX / "optimalx.db"
    assert out_db.read_bytes() == db.read_bytes()

    assert (tmp_path / "out" / FILES_ATTACHMENTS_PREFIX / "note-1.txt").read_text() == "first note"
    assert (
        tmp_path / "out" / FILES_ATTACHMENTS_PREFIX / "subdir" / "note-2.txt"
    ).read_text() == "second note"
    assert (
        tmp_path / "out" / FILES_WORKSHOP_PREFIX / "project-7" / "index.html"
    ).read_text() == "<h1>hi</h1>"


def test_missing_attachments_and_workshop_are_optional(tmp_path: Path) -> None:
    db = tmp_path / "optimalx.db"
    db.write_bytes(b"SQLite format 3\x00")
    manifest = fresh_manifest("0.0.1", 1, includes_files=False)

    buffer = io.BytesIO()
    write_snapshot_zip(buffer, manifest, db, attachments_dir=None, workshop_dir=None)
    buffer.seek(0)

    extracted = extract_snapshot_zip(buffer, tmp_path / "out")
    assert any(name.startswith(DB_DIR_PREFIX) for name in extracted.entry_names)
    assert not any(name.startswith(FILES_ATTACHMENTS_PREFIX) for name in extracted.entry_names)
    assert not any(name.startswith(FILES_WORKSHOP_PREFIX) for name in extracted.entry_names)


def test_extract_rejects_zip_slip(tmp_path: Path) -> None:
    """An archive entry like ``../escape.txt`` must not be allowed to
    write outside the destination directory.
    """
    malicious = tmp_path / "evil.zip"
    with zipfile.ZipFile(malicious, "w") as zf:
        zf.writestr(MANIFEST_ENTRY, json.dumps({"formatVersion": 1}))
        zf.writestr("../escape.txt", "should not write")
    with pytest.raises(ZipSlipError):
        extract_snapshot_zip(malicious.read_bytes(), tmp_path / "out")


def test_extract_missing_manifest_reports_error(tmp_path: Path) -> None:
    archive = tmp_path / "no_manifest.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("database/optimalx.db", b"\x00" * 16)
    with pytest.raises(ArchiveError, match=r"missing manifest\.json"):
        extract_snapshot_zip(archive.read_bytes(), tmp_path / "out")


def test_extract_invalid_manifest_reports_parse_error(tmp_path: Path) -> None:
    archive = tmp_path / "bad_manifest.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr(MANIFEST_ENTRY, "not json")
    with pytest.raises(ArchiveError, match="could not be parsed"):
        extract_snapshot_zip(archive.read_bytes(), tmp_path / "out")


def test_manifest_unknown_fields_ignored(tmp_path: Path) -> None:
    """Forward compatibility: a phone running a newer build that has added
    a manifest field must still produce a snapshot the desktop can open.
    """
    archive = tmp_path / "future.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr(
            MANIFEST_ENTRY,
            json.dumps(
                {
                    "formatVersion": 1,
                    "appVersionName": "9.9.9",
                    "dbVersion": 99,
                    "newFieldFromTheFuture": "carrots",
                    "anotherNestedThing": {"k": "v"},
                }
            ),
        )
    extracted = extract_snapshot_zip(archive.read_bytes(), tmp_path / "out")
    assert extracted.manifest.app_version_name == "9.9.9"
    assert extracted.manifest.db_version == 99


def test_manifest_missing_optional_fields_uses_defaults(tmp_path: Path) -> None:
    archive = tmp_path / "minimal.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr(MANIFEST_ENTRY, json.dumps({"formatVersion": 1}))
    extracted = extract_snapshot_zip(archive.read_bytes(), tmp_path / "out")
    assert extracted.manifest.format_version == 1
    assert extracted.manifest.app_version_name == ""
    assert extracted.manifest.db_version == 0
    assert extracted.manifest.includes_files is False
    assert extracted.manifest.export_epoch_ms == 0


def test_wrapped_manifest_resolves_to_shallowest(tmp_path: Path) -> None:
    """If someone re-archived a snapshot into ``wrapper/``, the extractor
    should still find the shallowest manifest and parse it.
    """
    archive = tmp_path / "wrapped.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        # Note: no top-level manifest. Only one inside ``wrapper/``.
        zf.writestr(f"wrapper/{MANIFEST_ENTRY}", json.dumps({"formatVersion": 1, "appVersionName": "x"}))
    extracted = extract_snapshot_zip(archive.read_bytes(), tmp_path / "out")
    assert extracted.manifest.app_version_name == "x"


def test_read_manifest_from_extracted_folder(tmp_path: Path) -> None:
    snapshot = tmp_path / "snapshot"
    snapshot.mkdir()
    (snapshot / MANIFEST_ENTRY).write_text(
        json.dumps({"formatVersion": 1, "appVersionName": "extract-test"})
    )
    manifest = read_manifest(snapshot)
    assert manifest.app_version_name == "extract-test"


def test_read_manifest_rejects_missing(tmp_path: Path) -> None:
    snapshot = tmp_path / "snapshot"
    snapshot.mkdir()
    with pytest.raises(ArchiveError):
        read_manifest(snapshot)


def test_manifest_roundtrip_via_json(tmp_path: Path) -> None:
    """to_json -> from_json must be the identity for all populated fields."""
    original = Manifest(
        format_version=1,
        export_epoch_ms=1_700_000_000_000,
        app_version_name="rt",
        db_version=12,
        includes_files=True,
    )
    revived = Manifest.from_json(original.to_json())
    assert revived == original
