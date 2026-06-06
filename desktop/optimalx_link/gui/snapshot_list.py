"""Snapshot list model + view.

The left pane of the main window. Each row is one entry from
``~/OptimalX-Link/snapshots/``; rows sort newest-first via
:func:`optimalx_link.snapshot_lib.list_snapshots`.

We use ``QAbstractListModel`` rather than ``QListWidget`` so the underlying
data is a list of dataclasses, not a hand-rolled QListWidgetItem soup.
Selection emits the highlighted :class:`SnapshotEntry` straight through.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from PySide6.QtCore import QAbstractListModel, QModelIndex, Qt, Signal
from PySide6.QtWidgets import QListView, QSizePolicy

from ..snapshot_lib import SnapshotEntry


class SnapshotListModel(QAbstractListModel):
    """Adapts a Python ``list[SnapshotEntry]`` to Qt's model interface."""

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._entries: list[SnapshotEntry] = []

    def set_entries(self, entries: list[SnapshotEntry]) -> None:
        """Replace the current data and notify views."""
        self.beginResetModel()
        self._entries = list(entries)
        self.endResetModel()

    def entry_at(self, row: int) -> SnapshotEntry | None:
        if 0 <= row < len(self._entries):
            return self._entries[row]
        return None

    def rowCount(self, parent: QModelIndex = QModelIndex()) -> int:  # noqa: B008
        if parent.isValid():
            return 0
        return len(self._entries)

    def data(self, index: QModelIndex, role: int = Qt.DisplayRole) -> Any:
        if not index.isValid():
            return None
        entry = self._entries[index.row()]
        if role == Qt.DisplayRole:
            return _format_row(entry)
        if role == Qt.UserRole:
            return entry
        if role == Qt.ToolTipRole:
            return f"{entry.id}\n{entry.path}"
        return None


class SnapshotListView(QListView):
    """List view wired to a :class:`SnapshotListModel`.

    Emits :pyattr:`selection_changed` whenever the user picks a different
    row. Multi-selection is intentionally disabled — every action in the
    detail pane operates on exactly one snapshot.
    """

    selection_changed = Signal(object)  # SnapshotEntry | None

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._model = SnapshotListModel(self)
        self.setModel(self._model)
        self.setSelectionMode(QListView.SingleSelection)
        self.setSizePolicy(QSizePolicy.Preferred, QSizePolicy.Expanding)
        self.setUniformItemSizes(True)
        self.setMinimumWidth(280)
        self.selectionModel().currentChanged.connect(self._on_current_changed)

    def set_entries(self, entries: list[SnapshotEntry]) -> None:
        """Replace list contents and try to keep the same selection by id.

        Without this the row that was selected jumps to whatever index
        happens to coincide after a re-list, which is confusing when the
        user deleted a row above it.
        """
        previous = self.current_entry()
        previous_id = previous.id if previous is not None else None
        self._model.set_entries(entries)
        if previous_id is not None:
            for row, entry in enumerate(entries):
                if entry.id == previous_id:
                    self.setCurrentIndex(self._model.index(row))
                    return
        if entries:
            self.setCurrentIndex(self._model.index(0))
        else:
            self.selection_changed.emit(None)

    def current_entry(self) -> SnapshotEntry | None:
        index = self.currentIndex()
        if not index.isValid():
            return None
        return self._model.entry_at(index.row())

    def _on_current_changed(self, current: QModelIndex, _previous: QModelIndex) -> None:
        if current.isValid():
            self.selection_changed.emit(self._model.entry_at(current.row()))
        else:
            self.selection_changed.emit(None)


def _format_row(entry: SnapshotEntry) -> str:
    """Three-line label rendered into a QListView row.

    Line 1: human timestamp + app version.
    Line 2: db version + size.
    Line 3: project + attachment counts.
    """
    when = datetime.fromtimestamp(entry.epoch_ms / 1000) if entry.epoch_ms else None
    when_str = when.strftime("%Y-%m-%d %H:%M") if when else entry.id
    app = entry.manifest.app_version_name or "—"
    size = _human_bytes(entry.size_bytes)
    return (
        f"{when_str}    {app}\n"
        f"db v{entry.manifest.db_version}    {size}\n"
        f"{entry.workshop_project_count} projects · {entry.attachment_count} attachments"
    )


def _human_bytes(n: int) -> str:
    units = ["B", "KiB", "MiB", "GiB", "TiB"]
    size = float(n)
    for unit in units:
        if size < 1024.0:
            return f"{size:.1f} {unit}"
        size /= 1024.0
    return f"{size:.1f} PiB"


__all__ = ["SnapshotListModel", "SnapshotListView"]
