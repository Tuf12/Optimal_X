"""Desktop-side HTTP server.

Spec: ../app/docs/architecture/OPTIMALX_LINK.md §http-endpoint-surface (desktop column).

Exposes the desktop's snapshot library to the phone via the same bearer-token
scheme used in the opposite direction. The server runs in-process with the
GUI: a Qt event loop hosts the aiohttp loop side-by-side using ``qasync``,
or — when the GUI isn't running (tests, headless smoke) — aiohttp drives
itself.

Endpoints:

    GET    /v1/status                       summary counters
    GET    /v1/snapshots                    array of SnapshotEntry summaries
    GET    /v1/snapshots/{id}/bundle        streams the snapshot folder as a zip
    POST   /v1/snapshots                    ingests a zip body, creates a snapshot folder
    DELETE /v1/snapshots/{id}               removes a snapshot folder
"""

from __future__ import annotations

import asyncio
import tempfile
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path

from aiohttp import web

from . import LINK_PROTOCOL_VERSION
from .snapshot_lib import (
    delete_snapshot,
    get_snapshot,
    import_snapshot_from_zip,
    list_snapshots,
    stream_snapshot_zip,
)

# Activity log callback contract — same shape the phone-side server uses
# so the GUI can plug both into one widget.
ActivitySink = Callable[[str], None]


@dataclass(frozen=True)
class ServerDeps:
    """Everything the routes need to do their job. Passed in via the
    application's state dict so a single :class:`DesktopServer` instance
    can be reconfigured (e.g., when the user changes the snapshots
    directory) without re-registering routes.
    """

    snapshots_dir: Path
    token: str
    on_activity: ActivitySink


_DEPS_KEY = web.AppKey("optimalx_link.deps", ServerDeps)


def _deps(request: web.Request) -> ServerDeps:
    return request.app[_DEPS_KEY]


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------


@web.middleware
async def bearer_auth_middleware(
    request: web.Request,
    handler: Callable[[web.Request], Awaitable[web.StreamResponse]],
) -> web.StreamResponse:
    """Reject any request missing or carrying the wrong ``Authorization``
    header. The realm matches the phone server's so log diffs are easy.
    """
    deps = _deps(request)
    header = request.headers.get("Authorization", "")
    expected = f"Bearer {deps.token}"
    if not deps.token or header != expected:
        deps.on_activity(f"401 {request.method} {request.path}")
        return web.json_response(
            {"error": "Unauthorized"},
            status=401,
            headers={"WWW-Authenticate": 'Bearer realm="OptimalX Link"'},
        )
    return await handler(request)


# ---------------------------------------------------------------------------
# Route handlers
# ---------------------------------------------------------------------------


async def handle_status(request: web.Request) -> web.Response:
    deps = _deps(request)
    entries = list_snapshots(deps.snapshots_dir)
    latest_epoch_ms = entries[0].epoch_ms if entries else 0
    deps.on_activity("GET /v1/status")
    return web.json_response(
        {
            "linkVersion": LINK_PROTOCOL_VERSION,
            "snapshotCount": len(entries),
            "latestSnapshotEpochMs": latest_epoch_ms,
        }
    )


async def handle_list_snapshots(request: web.Request) -> web.Response:
    deps = _deps(request)
    entries = list_snapshots(deps.snapshots_dir)
    deps.on_activity(f"GET /v1/snapshots -> {len(entries)} entries")
    return web.json_response([entry.to_json() for entry in entries])


async def handle_get_bundle(request: web.Request) -> web.StreamResponse:
    deps = _deps(request)
    snapshot_id = request.match_info["snapshot_id"]
    try:
        entry = get_snapshot(deps.snapshots_dir, snapshot_id)
    except KeyError:
        deps.on_activity(f"404 GET /v1/snapshots/{snapshot_id}/bundle")
        return web.json_response({"error": "snapshot not found"}, status=404)

    response = web.StreamResponse(
        status=200,
        headers={
            "Content-Type": "application/zip",
            "Content-Disposition": f'attachment; filename="optimalx-snapshot-{entry.id}.zip"',
        },
    )
    await response.prepare(request)

    # Pipe the synchronous zip writer through a small streaming adapter so we
    # don't load the whole zip into memory. aiohttp's StreamResponse.write
    # is awaitable; the adapter buffers small chunks before flushing.
    loop = asyncio.get_running_loop()
    adapter = _StreamingResponseSink(response, loop)
    try:
        await loop.run_in_executor(None, stream_snapshot_zip, entry, adapter)
        await adapter.aflush()
    finally:
        await response.write_eof()
    deps.on_activity(f"GET /v1/snapshots/{snapshot_id}/bundle ({entry.size_bytes} bytes)")
    return response


async def handle_post_snapshot(request: web.Request) -> web.Response:
    deps = _deps(request)
    # Stream the upload body to a temp file rather than buffering it in RAM —
    # phone snapshots can be hundreds of MB if the user has many attachments.
    with tempfile.NamedTemporaryFile(
        prefix="optimalx-link-upload-",
        suffix=".zip",
        delete=False,
        dir=str(deps.snapshots_dir.parent),
    ) as tmp:
        tmp_path = Path(tmp.name)
        async for chunk in request.content.iter_chunked(64 * 1024):
            tmp.write(chunk)
    try:
        with open(tmp_path, "rb") as fh:
            entry = import_snapshot_from_zip(deps.snapshots_dir, fh)
    except Exception as exc:
        deps.on_activity(f"400 POST /v1/snapshots ({exc})")
        return web.json_response({"error": str(exc)}, status=400)
    finally:
        tmp_path.unlink(missing_ok=True)
    deps.on_activity(f"POST /v1/snapshots -> {entry.id}")
    return web.json_response(
        {"id": entry.id, "epochMs": entry.epoch_ms},
        status=201,
    )


async def handle_delete_snapshot(request: web.Request) -> web.Response:
    deps = _deps(request)
    snapshot_id = request.match_info["snapshot_id"]
    try:
        delete_snapshot(deps.snapshots_dir, snapshot_id)
    except KeyError:
        deps.on_activity(f"404 DELETE /v1/snapshots/{snapshot_id}")
        return web.json_response({"error": "snapshot not found"}, status=404)
    deps.on_activity(f"DELETE /v1/snapshots/{snapshot_id}")
    return web.json_response({"id": snapshot_id, "deleted": True})


# ---------------------------------------------------------------------------
# App builder
# ---------------------------------------------------------------------------


def build_app(deps: ServerDeps) -> web.Application:
    """Construct the aiohttp application with all routes wired.

    Built as a free function so tests can hit the routes via
    ``aiohttp.test_utils.TestServer`` without instantiating
    :class:`DesktopServer`.
    """
    app = web.Application(middlewares=[bearer_auth_middleware])
    app[_DEPS_KEY] = deps
    app.router.add_get("/v1/status", handle_status)
    app.router.add_get("/v1/snapshots", handle_list_snapshots)
    app.router.add_get("/v1/snapshots/{snapshot_id}/bundle", handle_get_bundle)
    app.router.add_post("/v1/snapshots", handle_post_snapshot)
    app.router.add_delete("/v1/snapshots/{snapshot_id}", handle_delete_snapshot)
    return app


class DesktopServer:
    """Lifecycle wrapper around :func:`build_app`. The GUI's main window owns
    one of these; ``start`` is called once on launch, ``stop`` on close.

    Listens on ``127.0.0.1`` rather than ``0.0.0.0`` — Phase 3 deliberately
    keeps the desktop server loopback-only. In Phase 4 we'll surface a
    "Listen on LAN" toggle when phone-initiated push becomes the primary
    flow; until then the desktop is always the *caller* and the phone the
    *server*, so binding off-host serves no purpose.
    """

    def __init__(self, deps: ServerDeps, host: str = "127.0.0.1", port: int = 17833) -> None:
        self._deps = deps
        self._host = host
        self._port = port
        self._runner: web.AppRunner | None = None
        self._site: web.TCPSite | None = None

    @property
    def url(self) -> str:
        return f"http://{self._host}:{self._port}"

    async def start(self) -> None:
        app = build_app(self._deps)
        runner = web.AppRunner(app, handle_signals=False)
        await runner.setup()
        site = web.TCPSite(runner, host=self._host, port=self._port)
        await site.start()
        self._runner = runner
        self._site = site
        self._deps.on_activity(f"Desktop server listening on {self.url}")

    async def stop(self) -> None:
        if self._site is not None:
            await self._site.stop()
            self._site = None
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
        self._deps.on_activity("Desktop server stopped")


# ---------------------------------------------------------------------------
# Streaming adapter
# ---------------------------------------------------------------------------


class _StreamingResponseSink:
    """Bridges :func:`stream_snapshot_zip`'s synchronous ``BinaryIO``
    interface to an async :class:`aiohttp.web.StreamResponse`.

    The synchronous writer calls ``write(bytes)`` repeatedly; we schedule
    each chunk back onto the event loop via ``run_coroutine_threadsafe``.
    Chunks are buffered up to ``_FLUSH_BYTES`` before each cross-thread hop
    so we don't pay one round-trip per zlib block (≈ few KB each).
    """

    _FLUSH_BYTES = 256 * 1024

    def __init__(self, response: web.StreamResponse, loop: asyncio.AbstractEventLoop) -> None:
        self._response = response
        self._loop = loop
        self._buffer = bytearray()

    def write(self, data: bytes) -> int:
        self._buffer.extend(data)
        if len(self._buffer) >= self._FLUSH_BYTES:
            self._flush_now()
        return len(data)

    def flush(self) -> None:
        if self._buffer:
            self._flush_now()

    def _flush_now(self) -> None:
        chunk = bytes(self._buffer)
        self._buffer.clear()
        future = asyncio.run_coroutine_threadsafe(self._response.write(chunk), self._loop)
        # Block the executor thread until the bytes are accepted by aiohttp.
        # This applies backpressure: if the client is slow, the executor
        # thread stalls and the zip writer naturally pauses.
        future.result()

    async def aflush(self) -> None:
        """Flush from the loop side (no executor hop). Used after the
        synchronous writer returns to push the final partial buffer.
        """
        if self._buffer:
            chunk = bytes(self._buffer)
            self._buffer.clear()
            await self._response.write(chunk)


__all__ = [
    "ActivitySink",
    "DesktopServer",
    "ServerDeps",
    "build_app",
]


# Suppress lint on `time` — imported for future use in jobs endpoints (Phase 4).
_ = time
