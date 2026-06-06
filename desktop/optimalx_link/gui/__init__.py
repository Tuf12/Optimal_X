"""PySide6 GUI for OptimalX Link.

These modules are only imported when ``main()`` actually launches the GUI.
Importing :mod:`optimalx_link` itself does not pull PySide6 in, so the
non-GUI surfaces (server, archive, snapshot library) can be reused from
scripts or future automation without a Qt install.
"""
