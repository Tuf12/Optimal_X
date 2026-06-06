"""Glue between Qt's event loop and asyncio.

The GUI thread runs Qt's event loop. aiohttp wants its own asyncio loop.
Rather than pull in ``qasync`` (small but extra dep), we spin up one asyncio
loop on a worker thread and let the GUI submit coroutines via
``run_coroutine_threadsafe``. Results are marshalled back to the GUI thread
through Qt signals which is the only thread-safe way to touch widgets.

This module is GUI-agnostic — no PySide6 import — so it stays unit-testable
on its own.
"""

from __future__ import annotations

import asyncio
import threading
from collections.abc import Awaitable, Callable
from concurrent.futures import Future
from typing import Any


class AsyncBridge:
    """Single asyncio event loop running on a dedicated daemon thread.

    Typical use::

        bridge = AsyncBridge()
        bridge.start()
        future = bridge.submit(some_coro())
        future.add_done_callback(lambda f: print(f.result()))
        ...
        bridge.stop()

    Only one bridge per process is needed; the GUI's :class:`AppController`
    constructs it on launch and shuts it down on close.
    """

    def __init__(self) -> None:
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        self._ready = threading.Event()

    @property
    def loop(self) -> asyncio.AbstractEventLoop:
        if self._loop is None:
            raise RuntimeError("AsyncBridge must be started before use")
        return self._loop

    def start(self) -> None:
        """Spawn the worker thread and block until the loop is running."""
        if self._thread is not None:
            return
        self._thread = threading.Thread(
            target=self._run,
            name="optimalx-link-asyncio",
            daemon=True,
        )
        self._thread.start()
        # Wait for `_run` to publish the loop reference. Without this guard,
        # an immediate `submit()` from the caller would race the loop setup.
        if not self._ready.wait(timeout=5.0):
            raise RuntimeError("AsyncBridge worker failed to start within 5s")

    def stop(self, timeout: float = 5.0) -> None:
        """Tear down the loop. Idempotent."""
        if self._loop is None or self._thread is None:
            return
        loop = self._loop
        thread = self._thread
        self._loop = None
        self._thread = None
        loop.call_soon_threadsafe(loop.stop)
        thread.join(timeout=timeout)

    def submit(self, coro: Awaitable[Any]) -> Future:
        """Schedule ``coro`` on the worker loop and return a
        :class:`concurrent.futures.Future` that fires when it completes.

        Callers in the GUI thread should attach ``add_done_callback`` to
        marshal results back via a Qt signal — Qt signals are the only
        thread-safe way to touch widgets from a non-GUI thread.
        """
        if self._loop is None:
            raise RuntimeError("AsyncBridge must be started before submit()")
        return asyncio.run_coroutine_threadsafe(coro, self._loop)

    def call(self, fn: Callable[[], Any]) -> None:
        """Schedule a synchronous callable to run on the loop thread.

        Use sparingly — submit a coroutine via :meth:`submit` when you want
        results back. Provided for fire-and-forget loop-side bookkeeping.
        """
        if self._loop is None:
            raise RuntimeError("AsyncBridge must be started before call()")
        self._loop.call_soon_threadsafe(fn)

    def _run(self) -> None:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self._loop = loop
        self._ready.set()
        try:
            loop.run_forever()
        finally:
            try:
                pending = asyncio.all_tasks(loop)
                for task in pending:
                    task.cancel()
                if pending:
                    loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            finally:
                loop.close()


__all__ = ["AsyncBridge"]
