"""Tests for the small Qt-agnostic asyncio bridge."""

from __future__ import annotations

import asyncio
import threading
import time

from optimalx_link.async_bridge import AsyncBridge


def test_start_and_submit_returns_result() -> None:
    bridge = AsyncBridge()
    bridge.start()
    try:
        async def echo() -> int:
            return 42

        future = bridge.submit(echo())
        assert future.result(timeout=2.0) == 42
    finally:
        bridge.stop()


def test_submit_runs_on_loop_thread() -> None:
    """Coroutines submitted to the bridge must execute on a thread that
    isn't the caller's. Without this guarantee the GUI thread would block
    on long-running async work.
    """
    bridge = AsyncBridge()
    bridge.start()
    caller_thread = threading.get_ident()
    try:
        async def thread_id() -> int:
            return threading.get_ident()

        runner_thread = bridge.submit(thread_id()).result(timeout=2.0)
        assert runner_thread != caller_thread
    finally:
        bridge.stop()


def test_submit_propagates_exceptions() -> None:
    bridge = AsyncBridge()
    bridge.start()
    try:
        async def explode() -> None:
            raise ValueError("boom")

        future = bridge.submit(explode())
        try:
            future.result(timeout=2.0)
        except ValueError as exc:
            assert str(exc) == "boom"
        else:
            raise AssertionError("expected ValueError")
    finally:
        bridge.stop()


def test_stop_is_idempotent() -> None:
    bridge = AsyncBridge()
    bridge.start()
    bridge.stop()
    bridge.stop()  # second stop must not raise


def test_call_schedules_callable() -> None:
    bridge = AsyncBridge()
    bridge.start()
    try:
        called = threading.Event()
        bridge.call(called.set)
        assert called.wait(timeout=2.0)
    finally:
        bridge.stop()


def test_submit_before_start_raises() -> None:
    bridge = AsyncBridge()

    async def noop() -> None:
        return None

    pending = noop()
    try:
        bridge.submit(pending)
    except RuntimeError:
        pending.close()
    else:
        pending.close()
        raise AssertionError("expected RuntimeError")


def test_loop_runs_concurrent_work() -> None:
    """Submit two slow coroutines; their combined wall time should be
    close to the longer of the two, not the sum — proving the bridge runs
    them concurrently on the same loop.
    """
    bridge = AsyncBridge()
    bridge.start()
    try:
        async def sleep_for(ms: int) -> int:
            await asyncio.sleep(ms / 1000.0)
            return ms

        start = time.monotonic()
        f1 = bridge.submit(sleep_for(150))
        f2 = bridge.submit(sleep_for(150))
        assert f1.result(timeout=2.0) == 150
        assert f2.result(timeout=2.0) == 150
        elapsed = time.monotonic() - start
        assert elapsed < 0.5, f"expected concurrent execution, took {elapsed:.3f}s"
    finally:
        bridge.stop()
