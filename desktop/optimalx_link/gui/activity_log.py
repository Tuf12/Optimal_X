"""Bottom-pane activity log widget.

Shows the same one-line activity messages the server emits, plus client-side
events (connection tests, snapshot pulls). Backed by a ring buffer so
log volume during a long session can't OOM the process.
"""

from __future__ import annotations

from collections import deque
from datetime import datetime

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QPlainTextEdit, QSizePolicy


class ActivityLogWidget(QPlainTextEdit):
    """Read-only multi-line text view of the most recent activity lines."""

    # Maximum number of lines kept in memory. The widget itself enforces a
    # matching ``maximumBlockCount`` so Qt also trims its display buffer.
    MAX_LINES = 500

    # Emitted whenever a line is appended; primarily used by tests to
    # observe activity without scraping the widget contents.
    line_appended = Signal(str)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self.setReadOnly(True)
        self.setMaximumBlockCount(self.MAX_LINES)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        self.setMinimumHeight(120)
        self.setTextInteractionFlags(
            self.textInteractionFlags() | Qt.TextSelectableByMouse,
        )
        self._buffer: deque[str] = deque(maxlen=self.MAX_LINES)

    def append_line(self, message: str) -> None:
        """Append one activity line with a wall-clock timestamp prefix.

        Safe to call from any thread that already marshalled itself onto
        the GUI thread (i.e., from Qt signal slots). Direct calls from
        non-GUI threads will misbehave — use :meth:`append_line` only
        after a ``QMetaObject.invokeMethod`` hop or via a Qt signal.
        """
        stamp = datetime.now().strftime("%H:%M:%S")
        line = f"{stamp}  {message}"
        self._buffer.append(line)
        self.appendPlainText(line)
        self.line_appended.emit(line)

    def snapshot(self) -> list[str]:
        """Return all buffered lines, oldest first. Used by tests."""
        return list(self._buffer)


__all__ = ["ActivityLogWidget"]
