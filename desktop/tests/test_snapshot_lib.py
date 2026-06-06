"""Tests for the on-disk snapshot library."""

from __future__ import annotations

import io
from pathlib import Path

import pytest

from optimalx_link.archive import fresh_manifest, write_snapshot_zip
from optimalx_link.snapshot_lib import (
    delete_snapshot,
    get_snapshot,
    import_snapshot_from_zip,
    list_snapshots,
    make_snapshot_id,
    parse_snapshot_id_to_epoch_ms,
    reserve_snapshot_dir,
    stream_snapshot_zip,
)


def _build_zip(tmp_path: Path, app_version: str, db_version: int) -> bytes:
    db_file = tmp_path / "optimalx.db"
    db_file.write_bytes(b"SQLite format 3\x00" + b"\x00" * 32)
    workshop = tmp_path / "workshop"
    workshop.mkdir(exist_ok=True)
    project = workshop / "project-a"
    project.mkdir(exist_ok=True)
    (project / "file.txt").write_text("hello")

    buf = io.BytesIO()
    write_snapshot_zip(
        out=buf,
        manifest=fresh_manifest(app_version, db_version, includes_files=True),
        db_path=db_file,
        attachments_dir=None,
        workshop_dir=workshop,
    )
    return buf.getvalue()


def test_make_snapshot_id_roundtrips(tmp_path: Path) -> None:
    epoch_ms = 1_700_000_000_000
    snap_id = make_snapshot_id(epoch_ms)
    parsed = parse_snapshot_id_to_epoch_ms(snap_id)
    assert parsed == epoch_ms


def test_reserve_avoids_collisions(tmp_path: Path) -> None:
    a = reserve_snapshot_dir(tmp_path, epoch_ms=1_700_000_000_000)
    b = reserve_snapshot_dir(tmp_path, epoch_ms=1_700_000_000_000)
    assert a != b
    # Both should be directories under the same root.
    assert a.parent == tmp_path
    assert b.parent == tmp_path


def test_list_snapshots_empty(tmp_path: Path) -> None:
    assert list_snapshots(tmp_path) == []
    assert list_snapshots(tmp_path / "does-not-exist") == []


def test_import_lists_and_deletes_snapshot(tmp_path: Path) -> None:
    library = tmp_path / "library"
    library.mkdir()
    workdir = tmp_path / "work"
    workdir.mkdir()

    zip_bytes = _build_zip(workdir, "1.0.0", 18)
    entry = import_snapshot_from_zip(library, zip_bytes)

    assert entry.manifest.app_version_name == "1.0.0"
    assert entry.manifest.db_version == 18
    assert entry.workshop_project_count == 1
    assert entry.size_bytes > 0

    entries = list_snapshots(library)
    assert len(entries) == 1
    assert entries[0].id == entry.id

    delete_snapshot(library, entry.id)
    assert list_snapshots(library) == []


def test_list_sorts_newest_first(tmp_path: Path) -> None:
    library = tmp_path / "library"
    library.mkdir()
    workdir = tmp_path / "work"
    workdir.mkdir()

    # Build three snapshots and force distinct epochs so the order is
    # deterministic regardless of test execution speed.
    for app_version in ["a", "b", "c"]:
        import_snapshot_from_zip(library, _build_zip(workdir, app_version, 1))

    entries = list_snapshots(library)
    assert len(entries) == 3
    epochs = [e.epoch_ms for e in entries]
    assert epochs == sorted(epochs, reverse=True)


def test_get_snapshot_rejects_path_traversal(tmp_path: Path) -> None:
    with pytest.raises(KeyError):
        get_snapshot(tmp_path, "../outside")
    with pytest.raises(KeyError):
        get_snapshot(tmp_path, "subdir/inner")
    with pytest.raises(KeyError):
        get_snapshot(tmp_path, "..")
    with pytest.raises(KeyError):
        get_snapshot(tmp_path, "")


def test_get_snapshot_404_for_missing(tmp_path: Path) -> None:
    library = tmp_path / "library"
    library.mkdir()
    with pytest.raises(KeyError):
        get_snapshot(library, "2026-01-01_00-00-00")


def test_delete_missing_raises(tmp_path: Path) -> None:
    library = tmp_path / "library"
    library.mkdir()
    with pytest.raises(KeyError):
        delete_snapshot(library, "2026-01-01_00-00-00")


def test_stream_snapshot_zip_is_reimportable(tmp_path: Path) -> None:
    """A snapshot saved into the library should be re-zippable into bytes
    that another caller can extract back into a fresh library. This is the
    code path used by `GET /v1/snapshots/{id}/bundle`.
    """
    library = tmp_path / "library"
    library.mkdir()
    workdir = tmp_path / "work"
    workdir.mkdir()

    original = import_snapshot_from_zip(library, _build_zip(workdir, "stream-test", 5))

    buf = io.BytesIO()
    stream_snapshot_zip(original, buf)
    buf.seek(0)

    library2 = tmp_path / "library2"
    library2.mkdir()
    revived = import_snapshot_from_zip(library2, buf.getvalue())

    assert revived.manifest.app_version_name == "stream-test"
    assert revived.manifest.db_version == 5
    assert revived.workshop_project_count == 1


def test_corrupt_snapshot_folder_is_skipped(tmp_path: Path) -> None:
    """A folder without a manifest must not break enumeration of the
    library's other valid snapshots.
    """
    library = tmp_path / "library"
    library.mkdir()
    workdir = tmp_path / "work"
    workdir.mkdir()

    import_snapshot_from_zip(library, _build_zip(workdir, "good", 1))
    (library / "2026-01-01_00-00-00-broken").mkdir()
    # No manifest.json inside the "broken" folder.

    entries = list_snapshots(library)
    assert len(entries) == 1
    assert entries[0].manifest.app_version_name == "good"
