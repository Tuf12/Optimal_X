"""Top-level QMainWindow for OptimalX Link.

Composes the three sub-widgets (:class:`ConnectionPanel`,
:class:`SnapshotListView`, :class:`ActivityLogWidget`) plus a detail pane
that shows the currently selected snapshot's manifest summary.

Widget tree only — see :class:`optimalx_link.app_controller.AppController`
for the wiring between UI signals, the async bridge, the phone client, and
the desktop server.
"""

from __future__ import annotations

from PySide6.QtCore import Signal
from PySide6.QtGui import QFont
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QPlainTextEdit,
    QPushButton,
    QSizePolicy,
    QSplitter,
    QVBoxLayout,
    QWidget,
)

from ..archive import manifest_as_pretty_json
from ..snapshot_lib import SnapshotEntry
from .activity_log import ActivityLogWidget
from .connection_panel import ConnectionPanel
from .snapshot_list import SnapshotListView


class MainWindow(QMainWindow):
    """Composition root for the GUI.

    Exposes the child widgets as public attributes so :class:`AppController`
    can wire signal handlers without reaching through getters.
    """

    open_in_files_clicked = Signal(object)  # SnapshotEntry
    restore_to_phone_clicked = Signal(object)  # SnapshotEntry — Phase 4
    delete_clicked = Signal(object)  # SnapshotEntry

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle("OptimalX Link")
        self.resize(1100, 700)
        self._build_ui()

    # ------------------------------------------------------------------
    # External API
    # ------------------------------------------------------------------

    def set_detail(self, entry: SnapshotEntry | None) -> None:
        """Update the right pane to describe ``entry``, or clear it when
        None. Hides the action buttons when nothing is selected so the user
        can't fire actions against an empty selection.
        """
        if entry is None:
            self._header.setText("No snapshot selected")
            self._summary.setText("")
            self._manifest_view.setPlainText("")
            self._open_button.setEnabled(False)
            self._restore_button.setEnabled(False)
            self._delete_button.setEnabled(False)
            self._current_entry = None
            return
        self._current_entry = entry
        self._header.setText(entry.id)
        self._summary.setText(
            f"App version: {entry.manifest.app_version_name or '—'}\n"
            f"DB version:  {entry.manifest.db_version}\n"
            f"Format:      v{entry.manifest.format_version}\n"
            f"Projects:    {entry.workshop_project_count}\n"
            f"Attachments: {entry.attachment_count}\n"
            f"Path:        {entry.path}"
        )
        self._manifest_view.setPlainText(manifest_as_pretty_json(entry.manifest))
        self._open_button.setEnabled(True)
        self._restore_button.setEnabled(True)
        self._restore_button.setToolTip(
            "Push this snapshot to the connected phone. The phone must explicitly accept on its Link screen."
        )
        self._delete_button.setEnabled(True)

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _build_ui(self) -> None:
        central = QWidget(self)
        self.setCentralWidget(central)
        outer = QVBoxLayout(central)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)

        self.connection_panel = ConnectionPanel()
        outer.addWidget(self.connection_panel)

        splitter = QSplitter()
        splitter.setHandleWidth(2)
        outer.addWidget(splitter, stretch=1)

        # Left pane: snapshot list.
        self.snapshot_list = SnapshotListView()
        self.snapshot_list.selection_changed.connect(self.set_detail)
        splitter.addWidget(self.snapshot_list)

        # Right pane: detail.
        right = QWidget()
        right_layout = QVBoxLayout(right)
        right_layout.setContentsMargins(12, 8, 12, 8)

        self._header = QLabel("No snapshot selected")
        header_font = QFont()
        header_font.setPointSize(14)
        header_font.setBold(True)
        self._header.setFont(header_font)
        right_layout.addWidget(self._header)

        self._summary = QLabel("")
        self._summary.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Preferred)
        self._summary.setStyleSheet("font-family: monospace; color: #444;")
        right_layout.addWidget(self._summary)

        right_layout.addSpacing(8)
        right_layout.addWidget(QLabel("manifest.json"))
        self._manifest_view = QPlainTextEdit()
        self._manifest_view.setReadOnly(True)
        self._manifest_view.setStyleSheet("font-family: monospace;")
        right_layout.addWidget(self._manifest_view, stretch=1)

        button_row = QHBoxLayout()
        self._open_button = QPushButton("Open in Files")
        self._open_button.setEnabled(False)
        self._open_button.clicked.connect(self._emit_open)
        button_row.addWidget(self._open_button)

        self._restore_button = QPushButton("Restore to phone")
        self._restore_button.setEnabled(False)
        self._restore_button.clicked.connect(self._emit_restore)
        button_row.addWidget(self._restore_button)

        button_row.addStretch(1)

        self._delete_button = QPushButton("Delete snapshot")
        self._delete_button.setEnabled(False)
        self._delete_button.clicked.connect(self._emit_delete)
        button_row.addWidget(self._delete_button)

        right_layout.addLayout(button_row)
        splitter.addWidget(right)
        splitter.setStretchFactor(0, 0)
        splitter.setStretchFactor(1, 1)
        splitter.setSizes([300, 800])

        self.activity_log = ActivityLogWidget()
        outer.addWidget(self.activity_log)

        self._current_entry: SnapshotEntry | None = None

    def _emit_open(self) -> None:
        if self._current_entry is not None:
            self.open_in_files_clicked.emit(self._current_entry)

    def _emit_restore(self) -> None:
        if self._current_entry is not None:
            self.restore_to_phone_clicked.emit(self._current_entry)

    def _emit_delete(self) -> None:
        if self._current_entry is not None:
            self.delete_clicked.emit(self._current_entry)


__all__ = ["MainWindow"]
