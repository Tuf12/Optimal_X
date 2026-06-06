"""Glue between the GUI, the AsyncBridge, the phone client, and the desktop
server.

Lives outside ``gui/`` so the GUI modules stay pure presentation — they emit
signals, this controller decides what to do with them. Keeps PySide6 imports
out of the lower layers so the server / archive / snapshot_lib stay
unit-testable without a Qt install.
"""

from __future__ import annotations

import subprocess
import tempfile
import time
from concurrent.futures import Future
from pathlib import Path
from typing import Any

from PySide6.QtCore import QObject, Signal, Slot

from . import DESKTOP_DEFAULT_PORT
from .async_bridge import AsyncBridge
from .config import DesktopConfig, load_config, save_config, update_config
from .gui.main_window import MainWindow
from .phone_client import (
    PhoneClient,
    PhoneClientError,
    PhoneConnection,
    RestoreJob,
)
from .server import DesktopServer, ServerDeps
from .snapshot_lib import (
    SnapshotEntry,
    delete_snapshot,
    import_snapshot_from_zip,
    list_snapshots,
    stream_snapshot_zip,
)


class AppController(QObject):
    """Owns the cross-cutting state. One instance per process; constructed
    in ``__main__.main`` and parented to the QApplication.

    Activity events from any subsystem (server, phone client, internal
    logic) funnel through :pyattr:`_activity_event` and onto the GUI
    activity log via a Qt signal, which guarantees the touch happens on
    the GUI thread.
    """

    _activity_event = Signal(str)
    _refresh_event = Signal()
    _status_ok = Signal(str)
    _status_error = Signal(str)
    _busy_event = Signal(bool)

    def __init__(self, window: MainWindow, parent: QObject | None = None) -> None:
        super().__init__(parent)
        self._window = window
        self._config: DesktopConfig = load_config()
        self._snapshots_dir = Path(self._config.snapshots_dir)
        self._snapshots_dir.mkdir(parents=True, exist_ok=True)
        self._bridge = AsyncBridge()
        self._server: DesktopServer | None = None
        self._wire_signals()
        self._apply_initial_state()

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def start(self) -> None:
        """Spin up the async bridge, start the desktop server, refresh the
        snapshot list. Called from ``main()`` after the window is shown.
        """
        self._bridge.start()
        deps = ServerDeps(
            snapshots_dir=self._snapshots_dir,
            token=self._config.desktop_token,
            on_activity=self._emit_activity,
        )
        self._server = DesktopServer(deps, port=self._config.desktop_port or DESKTOP_DEFAULT_PORT)
        self._submit(self._server.start())
        self._emit_activity(
            f"Snapshots: {self._snapshots_dir}  (desktop token persisted to ~/.config/OptimalX-Link/config.json)"
        )
        self._refresh_snapshots_now()

    def stop(self) -> None:
        """Tear down server + bridge. Idempotent; safe to call multiple
        times during shutdown handling.
        """
        if self._server is not None:
            try:
                future = self._submit(self._server.stop())
                future.result(timeout=2.0)
            except Exception:
                pass
            self._server = None
        self._bridge.stop()

    # ------------------------------------------------------------------
    # Signal wiring
    # ------------------------------------------------------------------

    def _wire_signals(self) -> None:
        # GUI -> controller
        self._window.connection_panel.test_clicked.connect(self._on_test_clicked)
        self._window.connection_panel.update_clicked.connect(self._on_update_clicked)
        self._window.connection_panel.fields_changed.connect(self._on_fields_changed)
        self._window.open_in_files_clicked.connect(self._on_open_in_files)
        self._window.restore_to_phone_clicked.connect(self._on_restore_clicked)
        self._window.delete_clicked.connect(self._on_delete_clicked)
        # Controller -> GUI (thread-safe activity log)
        self._activity_event.connect(self._window.activity_log.append_line)
        self._refresh_event.connect(self._refresh_snapshots_now)
        self._status_ok.connect(self._window.connection_panel.set_status_ok)
        self._status_error.connect(self._window.connection_panel.set_status_error)
        self._busy_event.connect(self._window.connection_panel.set_busy)

    def _apply_initial_state(self) -> None:
        cp = self._window.connection_panel
        cp.set_initial(self._config.last_phone_host, self._config.last_phone_port, token="")
        self._window.set_detail(None)

    # ------------------------------------------------------------------
    # GUI event handlers (run on the GUI thread)
    # ------------------------------------------------------------------

    @Slot(str, int, str)
    def _on_fields_changed(self, host: str, port: int, _token: str) -> None:
        # Persist host/port (but not token) on every change so a crashed
        # session still restores the most recent connection.
        if host == self._config.last_phone_host and port == self._config.last_phone_port:
            return
        self._config = update_config(self._config, last_phone_host=host, last_phone_port=port)
        save_config(self._config)

    @Slot(str, int, str)
    def _on_test_clicked(self, host: str, port: int, token: str) -> None:
        if not host or not token:
            self._status_error.emit("Enter phone host and token first.")
            return
        self._emit_activity(f"Testing connection to {host}:{port} …")
        self._busy_event.emit(True)
        connection = PhoneConnection(host=host, port=port, token=token)
        future = self._submit(self._fetch_status(connection))
        future.add_done_callback(self._on_test_complete)

    @Slot()
    def _on_update_clicked(self) -> None:
        host, port, token = self._window.connection_panel.values()
        if not host or not token:
            self._status_error.emit("Enter phone host and token first.")
            return
        self._emit_activity(f"Pulling snapshot from {host}:{port} …")
        self._busy_event.emit(True)
        connection = PhoneConnection(host=host, port=port, token=token)
        future = self._submit(self._pull_snapshot(connection))
        future.add_done_callback(self._on_update_complete)

    @Slot(object)
    def _on_open_in_files(self, entry: SnapshotEntry) -> None:
        try:
            subprocess.Popen(["xdg-open", str(entry.path)])
            self._emit_activity(f"xdg-open {entry.path}")
        except OSError as exc:
            self._status_error.emit(f"Could not open file manager: {exc}")

    @Slot(object)
    def _on_restore_clicked(self, entry: SnapshotEntry) -> None:
        host, port, token = self._window.connection_panel.values()
        if not host or not token:
            self._status_error.emit("Enter phone host and token before restoring.")
            return
        self._emit_activity(
            f"Pushing snapshot {entry.id} to {host}:{port} for restore "
            f"(phone must Accept on the Link screen)…"
        )
        self._busy_event.emit(True)
        connection = PhoneConnection(host=host, port=port, token=token)
        future = self._submit(self._push_restore(connection, entry))
        future.add_done_callback(self._on_restore_complete)

    @Slot(object)
    def _on_delete_clicked(self, entry: SnapshotEntry) -> None:
        try:
            delete_snapshot(self._snapshots_dir, entry.id)
            self._emit_activity(f"Deleted snapshot {entry.id}")
        except KeyError:
            self._emit_activity(f"Snapshot {entry.id} already gone")
        self._refresh_snapshots_now()

    # ------------------------------------------------------------------
    # Async work (run on the bridge thread)
    # ------------------------------------------------------------------

    async def _fetch_status(self, connection: PhoneConnection) -> dict:
        async with PhoneClient(connection) as client:
            status = await client.fetch_status()
        return {
            "host": connection.host,
            "port": connection.port,
            "status": status,
        }

    async def _pull_snapshot(self, connection: PhoneConnection) -> SnapshotEntry:
        async with PhoneClient(connection) as client:
            with tempfile.NamedTemporaryFile(
                prefix="optimalx-link-pull-",
                suffix=".zip",
                delete=False,
                dir=str(self._snapshots_dir.parent),
            ) as tmp:
                tmp_path = Path(tmp.name)
            try:
                await client.download_bundle_to(tmp_path)
                with open(tmp_path, "rb") as fh:
                    entry = import_snapshot_from_zip(self._snapshots_dir, fh)
            finally:
                tmp_path.unlink(missing_ok=True)
        return entry

    async def _push_restore(
        self,
        connection: PhoneConnection,
        entry: SnapshotEntry,
    ) -> RestoreJob:
        """Materialize ``entry`` into a temporary zip and POST it to the
        phone's ``/v1/restore/full`` endpoint, then poll the job until it
        reaches a terminal state. Returns the final job so the completion
        callback can format an activity-log message.
        """
        with tempfile.NamedTemporaryFile(
            prefix=f"optimalx-link-restore-{entry.id}-",
            suffix=".zip",
            delete=False,
            dir=str(self._snapshots_dir.parent),
        ) as tmp:
            tmp_path = Path(tmp.name)
        try:
            # Rebuild the on-disk snapshot folder into a single zip stream.
            # We re-use the same writer the GET /v1/snapshots/{id}/bundle
            # endpoint uses so the desktop and phone see the exact same
            # archive layout. The zip writer is blocking, but it runs on
            # the bridge thread (not the GUI) so the dropdown stays
            # responsive.
            with open(tmp_path, "wb") as fh:
                stream_snapshot_zip(entry, fh)

            async with PhoneClient(connection) as client:
                initial = await client.push_restore(tmp_path)
                if not initial.id:
                    raise PhoneClientError(
                        "Phone did not return a job id; restore status unknown."
                    )
                self._emit_activity(
                    f"Phone accepted restore job {initial.id} — waiting for confirm tap…"
                )
                # Poll until terminal. The phone's confirm timeout is 5
                # minutes; we cap the desktop poll at 6 minutes so we
                # don't loop forever on a hung phone.
                deadline = time.monotonic() + 360.0
                final = initial
                while True:
                    if final.is_terminal:
                        break
                    if time.monotonic() >= deadline:
                        break
                    final = await client.poll_job(initial.id)
                return final
        finally:
            tmp_path.unlink(missing_ok=True)

    # ------------------------------------------------------------------
    # Future completion callbacks (run on the bridge thread — must
    # marshal back to the GUI via signals only)
    # ------------------------------------------------------------------

    def _on_test_complete(self, future: Future) -> None:
        try:
            result: dict = future.result()
        except PhoneClientError as exc:
            self._status_error.emit(str(exc))
            self._emit_activity(f"Connection test failed: {exc}")
            self._busy_event.emit(False)
            return
        except Exception as exc:
            self._status_error.emit(f"Unexpected error: {exc}")
            self._emit_activity(f"Connection test crashed: {exc}")
            self._busy_event.emit(False)
            return
        status = result["status"]
        self._status_ok.emit(
            f"OK — app v{status.app_version}, db v{status.db_version}, "
            f"format v{status.format_version}",
        )
        self._emit_activity(
            f"phone /v1/status: app v{status.app_version}, db v{status.db_version}, "
            f"{status.workshop_project_count} projects, {status.attachment_count} attachments"
        )
        self._busy_event.emit(False)

    def _on_restore_complete(self, future: Future) -> None:
        try:
            job: RestoreJob = future.result()
        except PhoneClientError as exc:
            self._status_error.emit(str(exc))
            self._emit_activity(f"Restore push failed: {exc}")
            self._busy_event.emit(False)
            return
        except Exception as exc:
            self._status_error.emit(f"Unexpected error: {exc}")
            self._emit_activity(f"Restore push crashed: {exc}")
            self._busy_event.emit(False)
            return
        # Surface the final state to both the status indicator and the
        # activity log. The user already knows what they pushed; what
        # matters here is whether the phone accepted it.
        if job.status == "OK":
            self._status_ok.emit(f"Phone applied job {job.id}")
            self._emit_activity(
                f"Phone applied restore {job.id}"
                + (f" — {job.message}" if job.message else "")
            )
        elif job.status == "REJECTED":
            self._status_error.emit(f"Phone rejected job {job.id}")
            self._emit_activity(
                f"Phone rejected restore {job.id}"
                + (f" — {job.message}" if job.message else "")
            )
        elif job.status == "FAILED":
            self._status_error.emit(f"Phone failed job {job.id}")
            self._emit_activity(
                f"Phone failed restore {job.id}"
                + (f" — {job.message}" if job.message else "")
            )
        else:
            # Non-terminal — almost always means our poll timed out before
            # the user tapped Accept. Surface it so they know to retry.
            self._status_error.emit(f"Restore {job.id} still {job.status}")
            self._emit_activity(
                f"Stopped polling restore {job.id} in state {job.status} "
                "(check the phone Link screen and retry)."
            )
        self._busy_event.emit(False)

    def _on_update_complete(self, future: Future) -> None:
        try:
            entry: SnapshotEntry = future.result()
        except PhoneClientError as exc:
            self._status_error.emit(str(exc))
            self._emit_activity(f"Pull failed: {exc}")
            self._busy_event.emit(False)
            return
        except Exception as exc:
            self._status_error.emit(f"Unexpected error: {exc}")
            self._emit_activity(f"Pull crashed: {exc}")
            self._busy_event.emit(False)
            return
        self._status_ok.emit(f"Saved snapshot {entry.id}")
        self._emit_activity(
            f"New snapshot {entry.id}  ({entry.size_bytes:,} bytes, "
            f"db v{entry.manifest.db_version}, app v{entry.manifest.app_version_name})"
        )
        self._refresh_event.emit()
        self._busy_event.emit(False)

    # ------------------------------------------------------------------
    # Misc helpers
    # ------------------------------------------------------------------

    def _emit_activity(self, message: str) -> None:
        """Thread-safe activity sink. The :pyattr:`_activity_event` signal
        marshals the message onto the GUI thread before the widget is touched.
        """
        if message:
            self._activity_event.emit(message)

    @Slot()
    def _refresh_snapshots_now(self) -> None:
        entries = list_snapshots(self._snapshots_dir)
        self._window.snapshot_list.set_entries(entries)
        if not entries:
            self._window.set_detail(None)

    def _submit(self, coro: Any) -> Future:
        return self._bridge.submit(coro)


__all__ = ["AppController"]
