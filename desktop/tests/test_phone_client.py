"""Tests for the async phone client.

We stand up a tiny aiohttp test server that mimics the phone's
``/v1/status`` and ``/v1/bundles/full`` responses, then drive
:class:`PhoneClient` against it.
"""

from __future__ import annotations

import io
import zipfile
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from optimalx_link import LINK_PROTOCOL_VERSION
from optimalx_link.phone_client import PhoneClient, PhoneClientError, PhoneConnection

TOKEN = "phone-tok-xyz"


def _build_phone_app() -> web.Application:
    app = web.Application()

    async def handle_status(request: web.Request) -> web.Response:
        if request.headers.get("Authorization") != f"Bearer {TOKEN}":
            return web.json_response({"error": "Unauthorized"}, status=401)
        return web.json_response(
            {
                "appVersion": "1.4.2",
                "dbVersion": 18,
                "formatVersion": 1,
                "linkVersion": LINK_PROTOCOL_VERSION,
                "dbFileSize": 4096,
                "workshopProjectCount": 2,
                "attachmentCount": 5,
            }
        )

    async def handle_bundle(request: web.Request) -> web.Response:
        if request.headers.get("Authorization") != f"Bearer {TOKEN}":
            return web.json_response({"error": "Unauthorized"}, status=401)
        # Hand-build a tiny zip; the client does not parse it, only writes it
        # to disk, so any well-formed zip is enough for these tests.
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("manifest.json", '{"formatVersion": 1}')
            zf.writestr("database/optimalx.db", b"\x00" * 64)
        return web.Response(body=buf.getvalue(), content_type="application/zip")

    app.router.add_get("/v1/status", handle_status)
    app.router.add_get("/v1/bundles/full", handle_bundle)
    return app


@pytest.fixture
async def fake_phone() -> AsyncIterator[TestServer]:
    server = TestServer(_build_phone_app())
    async with server:
        async with TestClient(server):
            yield server


async def _client_for(fake_phone: TestServer, token: str = TOKEN) -> PhoneClient:
    host = fake_phone.host
    port = fake_phone.port
    assert host is not None and port is not None
    return PhoneClient(PhoneConnection(host=host, port=port, token=token))


async def test_fetch_status_parses_body(fake_phone: TestServer) -> None:
    client = await _client_for(fake_phone)
    async with client:
        status = await client.fetch_status()
    assert status.app_version == "1.4.2"
    assert status.db_version == 18
    assert status.link_version == LINK_PROTOCOL_VERSION
    assert status.workshop_project_count == 2


async def test_fetch_status_401_surfaces_friendly_error(fake_phone: TestServer) -> None:
    client = await _client_for(fake_phone, token="wrong")
    async with client:
        with pytest.raises(PhoneClientError, match="bearer token"):
            await client.fetch_status()


async def test_download_bundle_writes_full_payload(fake_phone: TestServer, tmp_path: Path) -> None:
    client = await _client_for(fake_phone)
    dest = tmp_path / "snapshot.zip"
    async with client:
        n = await client.download_bundle_to(dest)
    assert dest.is_file()
    assert dest.stat().st_size == n
    assert dest.read_bytes()[:2] == b"PK"


async def test_download_bundle_no_partial_on_auth_failure(
    fake_phone: TestServer, tmp_path: Path
) -> None:
    client = await _client_for(fake_phone, token="wrong")
    dest = tmp_path / "snapshot.zip"
    async with client:
        with pytest.raises(PhoneClientError):
            await client.download_bundle_to(dest)
    # Neither the final nor the .part file should remain.
    assert not dest.exists()
    assert not dest.with_suffix(dest.suffix + ".part").exists()


async def test_stream_bundle_chunks(fake_phone: TestServer) -> None:
    client = await _client_for(fake_phone)
    received = bytearray()
    async with client:
        async for chunk in client.stream_bundle(chunk_size=32):
            received.extend(chunk)
    assert received[:2] == b"PK"


async def test_using_client_outside_context_raises(fake_phone: TestServer) -> None:
    client = await _client_for(fake_phone)
    with pytest.raises(RuntimeError, match="async with"):
        _ = client.session


# ---------------------------------------------------------------------------
# Restore (POST /v1/restore/full + GET /v1/jobs/{id})
# ---------------------------------------------------------------------------


def _build_phone_restore_app(
    *,
    final_status: str = "OK",
    confirm_after_polls: int = 1,
    fail_validation: bool = False,
) -> web.Application:
    """A fake phone that walks one restore job through its lifecycle.

    ``confirm_after_polls`` controls how many GET /v1/jobs/{id} requests
    are returned in PENDING_CONFIRM before the server transitions to
    ``final_status``. Setting ``fail_validation`` makes POST return a
    FAILED job up front so the desktop's "validation failure" path is
    covered.
    """
    state: dict = {"poll_count": 0, "received_bytes": 0}

    async def handle_post(request: web.Request) -> web.Response:
        if request.headers.get("Authorization") != f"Bearer {TOKEN}":
            return web.json_response({"error": "Unauthorized"}, status=401)
        # Drain the body so the upload path exercises Content-Length end-to-end.
        body = await request.read()
        state["received_bytes"] = len(body)
        if fail_validation:
            return web.json_response({"jobId": "job-1", "status": "FAILED"}, status=202)
        return web.json_response({"jobId": "job-1", "status": "PENDING_CONFIRM"}, status=202)

    async def handle_job(request: web.Request) -> web.Response:
        if request.headers.get("Authorization") != f"Bearer {TOKEN}":
            return web.json_response({"error": "Unauthorized"}, status=401)
        if request.match_info["job_id"] != "job-1":
            return web.json_response({"error": "unknown job"}, status=404)
        state["poll_count"] += 1
        if fail_validation:
            return web.json_response(
                {
                    "id": "job-1",
                    "status": "FAILED",
                    "message": "Backup is missing manifest.json.",
                    "callerAddress": "127.0.0.1",
                    "createdAtMs": 0,
                    "snapshotEpochMs": 0,
                    "appVersion": "",
                    "dbVersion": 0,
                }
            )
        if state["poll_count"] <= confirm_after_polls:
            status = "PENDING_CONFIRM"
            message = ""
        else:
            status = final_status
            message = "applied" if status == "OK" else "rejected"
        return web.json_response(
            {
                "id": "job-1",
                "status": status,
                "message": message,
                "callerAddress": "127.0.0.1",
                "createdAtMs": 1700000000000,
                "snapshotEpochMs": 1700000000000,
                "appVersion": "9.9.9",
                "dbVersion": 18,
            }
        )

    app = web.Application()
    app[_STATE_KEY] = state
    app.router.add_post("/v1/restore/full", handle_post)
    app.router.add_get("/v1/jobs/{job_id}", handle_job)
    return app


_STATE_KEY: web.AppKey[dict] = web.AppKey("state", dict)


@pytest.fixture
async def fake_phone_restore_ok(tmp_path: Path) -> AsyncIterator[TestServer]:
    app = _build_phone_restore_app(final_status="OK", confirm_after_polls=2)
    server = TestServer(app)
    async with server:
        async with TestClient(server):
            yield server


@pytest.fixture
async def fake_phone_restore_rejected(tmp_path: Path) -> AsyncIterator[TestServer]:
    app = _build_phone_restore_app(final_status="REJECTED", confirm_after_polls=1)
    server = TestServer(app)
    async with server:
        async with TestClient(server):
            yield server


@pytest.fixture
async def fake_phone_restore_failed(tmp_path: Path) -> AsyncIterator[TestServer]:
    app = _build_phone_restore_app(fail_validation=True)
    server = TestServer(app)
    async with server:
        async with TestClient(server):
            yield server


def _make_zip(path: Path, payload: bytes = b"PKfake-snapshot-bytes") -> None:
    """Write a tiny non-empty file in place of a real snapshot zip. The fake
    phone doesn't extract it; it just counts incoming bytes."""
    path.write_bytes(payload)


async def test_push_restore_returns_pending_confirm(
    fake_phone_restore_ok: TestServer, tmp_path: Path
) -> None:
    zip_path = tmp_path / "snapshot.zip"
    _make_zip(zip_path, b"PKzz" * 32)
    host = fake_phone_restore_ok.host
    port = fake_phone_restore_ok.port
    assert host is not None and port is not None
    async with PhoneClient(PhoneConnection(host=host, port=port, token=TOKEN)) as client:
        initial = await client.push_restore(zip_path)
    assert initial.id == "job-1"
    assert initial.status == "PENDING_CONFIRM"
    assert fake_phone_restore_ok.app[_STATE_KEY]["received_bytes"] == zip_path.stat().st_size


async def test_wait_for_terminal_resolves_ok(
    fake_phone_restore_ok: TestServer, tmp_path: Path
) -> None:
    zip_path = tmp_path / "snapshot.zip"
    _make_zip(zip_path)
    host = fake_phone_restore_ok.host
    port = fake_phone_restore_ok.port
    assert host is not None and port is not None
    async with PhoneClient(PhoneConnection(host=host, port=port, token=TOKEN)) as client:
        await client.push_restore(zip_path)
        final = await client.wait_for_terminal("job-1", interval=0.01, max_total=5.0)
    assert final.status == "OK"
    assert final.is_terminal
    assert final.app_version == "9.9.9"


async def test_wait_for_terminal_resolves_rejected(
    fake_phone_restore_rejected: TestServer, tmp_path: Path
) -> None:
    zip_path = tmp_path / "snapshot.zip"
    _make_zip(zip_path)
    host = fake_phone_restore_rejected.host
    port = fake_phone_restore_rejected.port
    assert host is not None and port is not None
    async with PhoneClient(PhoneConnection(host=host, port=port, token=TOKEN)) as client:
        await client.push_restore(zip_path)
        final = await client.wait_for_terminal("job-1", interval=0.01, max_total=5.0)
    assert final.status == "REJECTED"
    assert final.is_terminal


async def test_push_restore_validation_failure_surfaces_terminal(
    fake_phone_restore_failed: TestServer, tmp_path: Path
) -> None:
    zip_path = tmp_path / "snapshot.zip"
    _make_zip(zip_path, b"\x00" * 16)
    host = fake_phone_restore_failed.host
    port = fake_phone_restore_failed.port
    assert host is not None and port is not None
    async with PhoneClient(PhoneConnection(host=host, port=port, token=TOKEN)) as client:
        initial = await client.push_restore(zip_path)
        assert initial.status == "FAILED"
        final = await client.wait_for_terminal("job-1", interval=0.01, max_total=2.0)
    assert final.status == "FAILED"
    assert "manifest" in final.message


async def test_push_restore_missing_zip_raises(tmp_path: Path) -> None:
    # No server needed — the file check happens before we hit the network.
    client = PhoneClient(PhoneConnection(host="127.0.0.1", port=1, token=TOKEN))
    async with client:
        with pytest.raises(PhoneClientError, match="not found"):
            await client.push_restore(tmp_path / "missing.zip")


async def test_poll_job_unknown_id_raises(fake_phone_restore_ok: TestServer) -> None:
    host = fake_phone_restore_ok.host
    port = fake_phone_restore_ok.port
    assert host is not None and port is not None
    async with PhoneClient(PhoneConnection(host=host, port=port, token=TOKEN)) as client:
        with pytest.raises(PhoneClientError, match="forgot job"):
            await client.poll_job("does-not-exist")
