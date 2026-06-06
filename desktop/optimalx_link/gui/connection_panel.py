"""Top connection panel.

Holds the phone URL field, token field (masked), Test connection button,
and Update from phone button. Stays read-only-looking when no connection
has been attempted — same accent dot pattern the Android Link screen uses.

The widget is purely presentational: it emits signals when the user clicks
buttons or changes fields; the :class:`AppController` decides what to do
(submit work to the AsyncBridge, etc.).
"""

from __future__ import annotations

from PySide6.QtCore import Signal
from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QSizePolicy,
    QVBoxLayout,
    QWidget,
)

# Color palette aligned with the phone Link screen's status dot. Three states:
# unknown (gray), ok (green), error (red).
_DOT_UNKNOWN = QColor(0x88, 0x88, 0x88)
_DOT_OK = QColor(0x4A, 0xDE, 0x80)
_DOT_ERROR = QColor(0xEF, 0x44, 0x44)


class _StatusDot(QFrame):
    """10x10 colored square indicating phone reachability."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setFixedSize(12, 12)
        self.setFrameShape(QFrame.NoFrame)
        self.setAutoFillBackground(True)
        self._set_color(_DOT_UNKNOWN)

    def set_unknown(self) -> None:
        self._set_color(_DOT_UNKNOWN)

    def set_ok(self) -> None:
        self._set_color(_DOT_OK)

    def set_error(self) -> None:
        self._set_color(_DOT_ERROR)

    def _set_color(self, color: QColor) -> None:
        pal = self.palette()
        pal.setColor(QPalette.Window, color)
        self.setPalette(pal)


class ConnectionPanel(QWidget):
    """Top toolbar bound to ``MainWindow``."""

    test_clicked = Signal(str, int, str)  # host, port, token
    update_clicked = Signal()
    fields_changed = Signal(str, int, str)  # host, port, token

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self._build_ui()
        self._wire_signals()

    # ------------------------------------------------------------------
    # External API
    # ------------------------------------------------------------------

    def set_initial(self, host: str, port: int, token: str = "") -> None:
        """Pre-fill fields from persisted config without triggering signals.
        Useful on launch so the user sees the previous session's settings.
        """
        with _block_signals(self._host_field, self._port_field, self._token_field):
            self._host_field.setText(host)
            self._port_field.setText(str(port))
            self._token_field.setText(token)
        self._dot.set_unknown()
        self._status_label.setText("")

    def set_status_ok(self, message: str) -> None:
        self._dot.set_ok()
        self._status_label.setText(message)

    def set_status_error(self, message: str) -> None:
        self._dot.set_error()
        self._status_label.setText(message)

    def set_status_unknown(self, message: str = "") -> None:
        self._dot.set_unknown()
        self._status_label.setText(message)

    def set_busy(self, busy: bool) -> None:
        """Disable buttons while a request is in flight. Prevents the user
        from queueing five **Update from phone** clicks if the network is
        slow.
        """
        self._test_button.setEnabled(not busy)
        self._update_button.setEnabled(not busy)

    def values(self) -> tuple[str, int, str]:
        host = self._host_field.text().strip()
        try:
            port = int(self._port_field.text().strip() or "17832")
        except ValueError:
            port = 17832
        token = self._token_field.text().strip()
        return host, port, token

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _build_ui(self) -> None:
        outer = QVBoxLayout(self)
        outer.setContentsMargins(8, 8, 8, 4)

        row = QHBoxLayout()
        row.setSpacing(8)

        row.addWidget(QLabel("Phone:"))
        self._host_field = QLineEdit()
        self._host_field.setPlaceholderText("192.168.1.42")
        self._host_field.setMinimumWidth(140)
        row.addWidget(self._host_field, stretch=2)

        row.addWidget(QLabel(":"))
        self._port_field = QLineEdit()
        self._port_field.setPlaceholderText("17832")
        self._port_field.setFixedWidth(64)
        row.addWidget(self._port_field)

        row.addWidget(QLabel("Token:"))
        self._token_field = QLineEdit()
        self._token_field.setEchoMode(QLineEdit.Password)
        self._token_field.setMinimumWidth(180)
        row.addWidget(self._token_field, stretch=3)

        self._test_button = QPushButton("Test connection")
        row.addWidget(self._test_button)

        self._update_button = QPushButton("Update from phone")
        self._update_button.setDefault(True)
        row.addWidget(self._update_button)

        outer.addLayout(row)

        status_row = QHBoxLayout()
        self._dot = _StatusDot()
        status_row.addWidget(self._dot)
        self._status_label = QLabel("")
        status_row.addWidget(self._status_label, stretch=1)
        outer.addLayout(status_row)

    def _wire_signals(self) -> None:
        self._test_button.clicked.connect(self._on_test_clicked)
        self._update_button.clicked.connect(self.update_clicked.emit)
        for field in (self._host_field, self._port_field, self._token_field):
            field.textChanged.connect(self._emit_changed)

    def _on_test_clicked(self) -> None:
        host, port, token = self.values()
        self.test_clicked.emit(host, port, token)

    def _emit_changed(self, _text: str = "") -> None:
        host, port, token = self.values()
        self.fields_changed.emit(host, port, token)


class _block_signals:
    """Context manager that suspends ``blockSignals`` on every passed widget.

    PySide6's own ``QSignalBlocker`` exists but only takes one target; this
    tiny wrapper handles N widgets in one ``with`` block.
    """

    def __init__(self, *widgets: QWidget) -> None:
        self._widgets = widgets
        self._prev: list[bool] = []

    def __enter__(self) -> _block_signals:
        self._prev = [w.blockSignals(True) for w in self._widgets]
        return self

    def __exit__(self, *exc_info) -> None:
        for w, prev in zip(self._widgets, self._prev, strict=True):
            w.blockSignals(prev)


__all__ = ["ConnectionPanel"]
