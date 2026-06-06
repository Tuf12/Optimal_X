"""Async HTTP client for the phone's OptimalX Link server.

Spec: ../app/docs/architecture/OPTIMALX_LINK.md §http-endpoint-surface.

Talks to the endpoints implemented in
``app/src/main/java/com/example/optimalx/data/link/LinkServer.kt``:

    GET  /v1/status                summary JSON
    GET  /v1/bundles/full          streaming snapshot .zip
    POST /v1/restore/full          enqueue a restore job
    GET  /v1/jobs/{id}             poll restore job state
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from dataclasses import dataclass
from pathlib import Path

import aiohttp

# Default request timeout. Bundles can be large (~hundreds of MB if the user
# has many attachments); we set generous limits and let aiohttp's chunked
# transfer handle backpressure. Total=None disables the wall-clock cap so a
# slow Wi-Fi link doesn't kill a long pull.
_DEFAULT_TIMEOUT = aiohttp.ClientTimeout(total=None, connect=10, sock_read=120)


@dataclass(frozen=True)
class PhoneStatus:
    """Parsed body of ``GET /v1/status``.

    Field names mirror the Kotlin ``StatusResponse`` data class so debugging
    a wire shape mismatch is a straight diff between the two definitions.
    """

    app_version: str
    db_version: int
    format_version: int
    link_version: int
    db_file_size: int
    workshop_project_count: int
    attachment_count: int

    @staticmethod
    def from_json(raw: dict) -> PhoneStatus:
        return PhoneStatus(
            app_version=str(raw.get("appVersion", "")),
            db_version=int(raw.get("dbVersion", 0)),
            format_version=int(raw.get("formatVersion", 0)),
            link_version=int(raw.get("linkVersion", 0)),
            db_file_size=int(raw.get("dbFileSize", 0)),
            workshop_project_count=int(raw.get("workshopProjectCount", 0)),
            attachment_count=int(raw.get("attachmentCount", 0)),
        )


class PhoneClientError(Exception):
    """Raised when a phone call fails for a reason the GUI should surface
    verbatim (auth rejected, unreachable, malformed response).
    """


@dataclass(frozen=True)
class PhoneConnection:
    """Where the phone is + how to authenticate. Constructed once per GUI
    "Connect" action so a session always carries consistent credentials.
    """

    host: str
    port: int
    token: str

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    @property
    def auth_header(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}


class PhoneClient:
    """Async client for one phone connection. Construct inside an
    ``async with`` block so the underlying ``aiohttp.ClientSession`` is
    closed deterministically.

    Typical usage::

        async with PhoneClient(conn) as client:
            status = await client.fetch_status()
            await client.download_bundle_to(Path("/tmp/snapshot.zip"))
    """

    def __init__(
        self,
        connection: PhoneConnection,
        timeout: aiohttp.ClientTimeout = _DEFAULT_TIMEOUT,
    ) -> None:
        self._connection = connection
        self._timeout = timeout
        self._session: aiohttp.ClientSession | None = None

    async def __aenter__(self) -> PhoneClient:
        self._session = aiohttp.ClientSession(
            timeout=self._timeout,
            headers=self._connection.auth_header,
        )
        return self

    async def __aexit__(self, *exc_info) -> None:
        if self._session is not None:
            await self._session.close()
            self._session = None

    @property
    def session(self) -> aiohttp.ClientSession:
        if self._session is None:
            raise RuntimeError("PhoneClient must be used inside `async with`")
        return self._session

    async def fetch_status(self) -> PhoneStatus:
        """Hit ``GET /v1/status`` and parse the JSON body.

        Raises:
            PhoneClientError: when the server returns a non-2xx response or
                the body is not valid JSON of the expected shape.
        """
        url = f"{self._connection.base_url}/v1/status"
        try:
            async with self.session.get(url) as resp:
                if resp.status == 401:
                    raise PhoneClientError("Phone rejected the bearer token (401).")
                if resp.status >= 400:
                    body = await resp.text()
                    raise PhoneClientError(
                        f"GET /v1/status returned {resp.status}: {body[:200]}"
                    )
                data = await resp.json(content_type=None)
        except aiohttp.ClientError as exc:
            raise PhoneClientError(f"Could not reach phone: {exc}") from exc
        if not isinstance(data, dict):
            raise PhoneClientError("Status response was not a JSON object.")
        return PhoneStatus.from_json(data)

    async def stream_bundle(self, chunk_size: int = 64 * 1024) -> AsyncIterator[bytes]:
        """Yield ``GET /v1/bundles/full`` body chunks.

        Streaming avoids buffering the whole snapshot (could be hundreds of
        MB) in memory. Callers writing to disk should use the convenience
        :meth:`download_bundle_to` instead.
        """
        url = f"{self._connection.base_url}/v1/bundles/full"
        try:
            async with self.session.get(url) as resp:
                if resp.status == 401:
                    raise PhoneClientError("Phone rejected the bearer token (401).")
                if resp.status >= 400:
                    body = await resp.text()
                    raise PhoneClientError(
                        f"GET /v1/bundles/full returned {resp.status}: {body[:200]}"
                    )
                async for chunk in resp.content.iter_chunked(chunk_size):
                    yield chunk
        except aiohttp.ClientError as exc:
            raise PhoneClientError(f"Snapshot download failed: {exc}") from exc

    async def download_bundle_to(self, dest: Path, chunk_size: int = 64 * 1024) -> int:
        """Persist the snapshot bytes to ``dest``. Returns the byte count
        actually written, useful for progress UI in the GUI.

        ``dest``'s parent is created on demand; the file is written via a
        ``.part`` sibling and atomically renamed so a crash mid-download
        never leaves a truncated zip the GUI mistakes for a complete one.
        """
        dest.parent.mkdir(parents=True, exist_ok=True)
        staging = dest.with_suffix(dest.suffix + ".part")
        bytes_written = 0
        try:
            with open(staging, "wb") as fh:
                async for chunk in self.stream_bundle(chunk_size=chunk_size):
                    fh.write(chunk)
                    bytes_written += len(chunk)
            staging.replace(dest)
        finally:
            if staging.exists():
                staging.unlink(missing_ok=True)
        return bytes_written

    # ------------------------------------------------------------------
    # Restore (POST /v1/restore/full + GET /v1/jobs/{id})
    # ------------------------------------------------------------------

    async def push_restore(self, zip_path: Path, chunk_size: int = 64 * 1024) -> RestoreJob:
        """Stream a snapshot zip to ``POST /v1/restore/full``.

        Returns the initial :class:`RestoreJob` from the phone's response.
        The job will most commonly be in ``PENDING_CONFIRM`` — the user
        still has to tap Accept on the phone before the restore applies.
        Poll :meth:`poll_job` for further transitions.

        Raises:
            PhoneClientError: on auth rejection, network failure, or a
                non-202 response. The desktop GUI logs the message and
                shows a red toast.
        """
        url = f"{self._connection.base_url}/v1/restore/full"
        if not zip_path.is_file():
            raise PhoneClientError(f"Snapshot zip not found: {zip_path}")
        size = zip_path.stat().st_size

        async def file_iter() -> AsyncIterator[bytes]:
            # Read in a worker thread so the asyncio loop isn't blocked on disk.
            loop = asyncio.get_running_loop()
            with open(zip_path, "rb") as fh:
                while True:
                    chunk = await loop.run_in_executor(None, fh.read, chunk_size)
                    if not chunk:
                        return
                    yield chunk

        try:
            async with self.session.post(
                url,
                data=file_iter(),
                headers={
                    "Content-Type": "application/zip",
                    "Content-Length": str(size),
                },
            ) as resp:
                if resp.status == 401:
                    raise PhoneClientError("Phone rejected the bearer token (401).")
                if resp.status not in (200, 202):
                    body = await resp.text()
                    raise PhoneClientError(
                        f"POST /v1/restore/full returned {resp.status}: {body[:200]}"
                    )
                data = await resp.json(content_type=None)
        except aiohttp.ClientError as exc:
            raise PhoneClientError(f"Restore push failed: {exc}") from exc

        if not isinstance(data, dict):
            raise PhoneClientError("Restore response was not a JSON object.")
        return RestoreJob(
            id=str(data.get("jobId", "")),
            status=str(data.get("status", "FAILED")),
            message="",
            caller_address="",
            created_at_ms=0,
            snapshot_epoch_ms=0,
            app_version="",
            db_version=0,
        )

    async def poll_job(self, job_id: str) -> RestoreJob:
        """Hit ``GET /v1/jobs/{job_id}`` and parse the body."""
        url = f"{self._connection.base_url}/v1/jobs/{job_id}"
        try:
            async with self.session.get(url) as resp:
                if resp.status == 404:
                    raise PhoneClientError(f"Phone forgot job '{job_id}'.")
                if resp.status == 401:
                    raise PhoneClientError("Phone rejected the bearer token (401).")
                if resp.status >= 400:
                    body = await resp.text()
                    raise PhoneClientError(
                        f"GET /v1/jobs/{job_id} returned {resp.status}: {body[:200]}"
                    )
                data = await resp.json(content_type=None)
        except aiohttp.ClientError as exc:
            raise PhoneClientError(f"Could not poll job: {exc}") from exc
        if not isinstance(data, dict):
            raise PhoneClientError("Job response was not a JSON object.")
        return RestoreJob.from_json(data)

    async def wait_for_terminal(
        self,
        job_id: str,
        interval: float = 0.75,
        max_total: float = 360.0,
    ) -> RestoreJob:
        """Poll ``job_id`` until it reaches a terminal state or ``max_total``
        seconds elapse. The default 6-minute cap exceeds the phone's
        5-minute confirm-timeout so a user who walks away still ends with
        a deterministic ``REJECTED`` outcome rather than a hung poll.
        """
        deadline = asyncio.get_running_loop().time() + max_total
        while True:
            job = await self.poll_job(job_id)
            if job.status in _TERMINAL_JOB_STATES:
                return job
            now = asyncio.get_running_loop().time()
            if now >= deadline:
                return job
            await asyncio.sleep(min(interval, max(0.0, deadline - now)))


# ---------------------------------------------------------------------------
# RestoreJob model
# ---------------------------------------------------------------------------


_TERMINAL_JOB_STATES = frozenset({"OK", "REJECTED", "FAILED"})


@dataclass(frozen=True)
class RestoreJob:
    """Parsed body of ``GET /v1/jobs/{id}``.

    Field names mirror Kotlin's ``JobStatusResponse``. Unknown / missing
    fields default to empty so additive changes on the phone don't break
    older desktop builds.
    """

    id: str
    status: str
    message: str
    caller_address: str
    created_at_ms: int
    snapshot_epoch_ms: int
    app_version: str
    db_version: int

    @staticmethod
    def from_json(raw: dict) -> RestoreJob:
        return RestoreJob(
            id=str(raw.get("id", "")),
            status=str(raw.get("status", "FAILED")),
            message=str(raw.get("message", "")),
            caller_address=str(raw.get("callerAddress", "")),
            created_at_ms=int(raw.get("createdAtMs", 0)),
            snapshot_epoch_ms=int(raw.get("snapshotEpochMs", 0)),
            app_version=str(raw.get("appVersion", "")),
            db_version=int(raw.get("dbVersion", 0)),
        )

    @property
    def is_terminal(self) -> bool:
        return self.status in _TERMINAL_JOB_STATES


__all__ = [
    "PhoneClient",
    "PhoneClientError",
    "PhoneConnection",
    "PhoneStatus",
    "RestoreJob",
]
