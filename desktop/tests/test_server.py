"""Integration tests for the desktop's aiohttp server.

Uses ``aiohttp.test_utils.TestServer`` so we don't bind to a real port and
each test gets its own application instance. Auth is the canonical
``Bearer <token>`` scheme.
"""

from __future__ import annotations

import io
from pathlib import Path

import pytest
from aiohttp.test_utils import TestClient, TestServer

from optimalx_link import LINK_PROTOCOL_VERSION
from optimalx_link.archive import fresh_manifest, write_snapshot_zip
from optimalx_link.server import ServerDeps, build_app
from optimalx_link.snapshot_lib import import_snapshot_from_zip, list_snapshots

TOKEN = "test-token-abc123"


def _build_zip(workdir: Path, *, app_version: str = "1.0.0", db_version: int = 18) -> bytes:
    db_file = workdir / "optimalx.db"
    db_file.write_bytes(b"SQLite format 3\x00" + b"\x00" * 32)
    workshop = workdir / "workshop"
    workshop.mkdir(exist_ok=True)
    (workshop / "project-1").mkdir(exist_ok=True)
    (workshop / "project-1" / "index.html").write_text("<h1>hi</h1>")
    buf = io.BytesIO()
    write_snapshot_zip(
        out=buf,
        manifest=fresh_manifest(app_version, db_version, includes_files=True),
        db_path=db_file,
        attachments_dir=None,
        workshop_dir=workshop,
    )
    return buf.getvalue()


def _activity_recorder() -> tuple[list[str], object]:
    captured: list[str] = []

    def sink(message: str) -> None:
        captured.append(message)

    return captured, sink


@pytest.fixture
async def client(tmp_path: Path):
    library = tmp_path / "library"
    library.mkdir()
    captured, sink = _activity_recorder()
    deps = ServerDeps(snapshots_dir=library, token=TOKEN, on_activity=sink)
    server = TestServer(build_app(deps))
    async with server:
        async with TestClient(server) as c:
            # Stash helpers on the client so tests can read activity / disk
            # without re-plumbing fixtures.
            c.library = library  # type: ignore[attr-defined]
            c.activity = captured  # type: ignore[attr-defined]
            c.workdir = tmp_path / "work"  # type: ignore[attr-defined]
            c.workdir.mkdir()  # type: ignore[attr-defined]
            yield c


def _auth(token: str = TOKEN) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def test_status_requires_bearer(client: TestClient) -> None:
    resp = await client.get("/v1/status")
    assert resp.status == 401
    assert resp.headers.get("WWW-Authenticate", "").startswith("Bearer")


async def test_status_rejects_wrong_token(client: TestClient) -> None:
    resp = await client.get("/v1/status", headers=_auth("nope"))
    assert resp.status == 401


async def test_status_returns_expected_shape(client: TestClient) -> None:
    resp = await client.get("/v1/status", headers=_auth())
    assert resp.status == 200
    body = await resp.json()
    assert body["linkVersion"] == LINK_PROTOCOL_VERSION
    assert body["snapshotCount"] == 0
    assert body["latestSnapshotEpochMs"] == 0


async def test_list_snapshots_empty(client: TestClient) -> None:
    resp = await client.get("/v1/snapshots", headers=_auth())
    assert resp.status == 200
    assert await resp.json() == []


async def test_post_then_list_round_trip(client: TestClient) -> None:
    payload = _build_zip(client.workdir, app_version="2.0", db_version=42)

    resp = await client.post("/v1/snapshots", headers=_auth(), data=payload)
    assert resp.status == 201
    created = await resp.json()
    assert "id" in created
    assert created["epochMs"] > 0

    resp = await client.get("/v1/snapshots", headers=_auth())
    body = await resp.json()
    assert len(body) == 1
    assert body[0]["id"] == created["id"]
    assert body[0]["appVersion"] == "2.0"
    assert body[0]["dbVersion"] == 42

    status_resp = await client.get("/v1/status", headers=_auth())
    assert (await status_resp.json())["snapshotCount"] == 1


async def test_get_bundle_streams_re_parseable_zip(client: TestClient, tmp_path: Path) -> None:
    payload = _build_zip(client.workdir, app_version="3.3", db_version=7)
    create = await client.post("/v1/snapshots", headers=_auth(), data=payload)
    snapshot_id = (await create.json())["id"]

    resp = await client.get(f"/v1/snapshots/{snapshot_id}/bundle", headers=_auth())
    assert resp.status == 200
    body = await resp.read()
    assert resp.headers["Content-Type"] == "application/zip"
    assert body[:2] == b"PK"  # local file header magic

    # Re-import into a fresh library to prove the bytes are a valid snapshot.
    other = tmp_path / "library2"
    other.mkdir()
    revived = import_snapshot_from_zip(other, body)
    assert revived.manifest.app_version_name == "3.3"
    assert revived.manifest.db_version == 7


async def test_get_bundle_404_for_missing(client: TestClient) -> None:
    resp = await client.get("/v1/snapshots/2026-01-01_00-00-00/bundle", headers=_auth())
    assert resp.status == 404


async def test_delete_snapshot(client: TestClient) -> None:
    payload = _build_zip(client.workdir)
    create = await client.post("/v1/snapshots", headers=_auth(), data=payload)
    snapshot_id = (await create.json())["id"]

    resp = await client.delete(f"/v1/snapshots/{snapshot_id}", headers=_auth())
    assert resp.status == 200
    body = await resp.json()
    assert body["deleted"] is True
    assert body["id"] == snapshot_id

    # Library is empty again.
    assert list_snapshots(client.library) == []


async def test_delete_missing_returns_404(client: TestClient) -> None:
    resp = await client.delete("/v1/snapshots/2026-01-01_00-00-00", headers=_auth())
    assert resp.status == 404


async def test_post_malformed_zip_returns_400(client: TestClient) -> None:
    resp = await client.post("/v1/snapshots", headers=_auth(), data=b"not a zip at all")
    assert resp.status == 400
    body = await resp.json()
    assert "error" in body
    # The library must remain empty after a failed upload.
    assert list_snapshots(client.library) == []


async def test_activity_log_records_endpoints(client: TestClient) -> None:
    await client.get("/v1/status", headers=_auth())
    assert any("GET /v1/status" in line for line in client.activity)

    # 401 lines should also appear when auth fails.
    await client.get("/v1/status")
    assert any("401" in line for line in client.activity)


async def test_bundle_request_without_auth_rejected(client: TestClient) -> None:
    resp = await client.get("/v1/snapshots/anything/bundle")
    assert resp.status == 401
