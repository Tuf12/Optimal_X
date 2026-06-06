# OptimalX Link — desktop client

Linux Mint / desktop side of the [OptimalX Link](../app/docs/architecture/OPTIMALX_LINK.md) snapshot bridge.
PySide6 GUI + an embedded `aiohttp` server in the same process. The GUI lets you pull, push,
and restore full app-state snapshots between the OptimalX Android app and your PC.

> **Status:** Phase 3 of the [implementation plan](../app/docs/implementation/OPTIMALX_LINK_IMPLEMENTATION_PLAN.md). Read-only operations (status + pull-from-phone) are functional; restore-to-phone arrives in Phase 4.

---

## What this does

- Lists every snapshot you've ever pulled from the phone, newest first.
- Pulls a fresh snapshot from the phone on demand (**Update from phone**).
- Stores each snapshot as a plain folder under `~/OptimalX-Link/snapshots/<timestamp>/` — open in your file manager, grep through workshop files, copy out individual attachments, anything you want.
- Hosts a small HTTP server on `localhost:17833` that the phone can call back into (used in Phase 4 for **Import from desktop** and **Push from phone**).

The wire format is identical to the SAF `.zip` backup OptimalX produces; that's the same code path the phone uses for `Settings → Data backup → Export`.

---

## Install

This package targets Python 3.12+ on Linux. Recommended setup uses a venv so you don't fight your distro's packaging:

```bash
cd desktop
python3.12 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -e ".[dev]"
```

`-e` (editable) is recommended while we're still in early phases — edits to the source take effect on next launch, no reinstall.

### Optional: desktop entry

To get an icon in the application menu (Linux Mint / GNOME / KDE):

```bash
xdg-desktop-menu install --novendor share/applications/optimalx-link.desktop
```

---

## Run

```bash
optimalx-link            # if the venv is active
# or
python -m optimalx_link  # equivalent
```

First launch creates `~/.config/OptimalX-Link/config.json` (chmod 600) and `~/OptimalX-Link/snapshots/`.

---

## How to pair with the phone

1. On the phone, open **Settings → OptimalX Link**.
2. The Link screen shows an IP, port, and bearer token. Either:
   - Type the host (`<phone-ip>:17832`) and token into the desktop's connection panel, or
   - Scan the QR — it encodes `optimalx-link://<ip>:<port>?token=<token>` (Phase 3.5; manual entry works today).
3. Click **Test connection**. A green dot means the desktop reached the phone and the bearer was accepted.
4. **Update from phone** pulls a fresh snapshot. New entries appear at the top of the snapshot list.

---

## Snapshot layout on disk

```
~/OptimalX-Link/snapshots/
└── 2026-05-26_22-12-04/
    ├── manifest.json           ← human-readable, matches BackupManifest in app/
    ├── database/
    │   └── optimalx.db
    └── files/
        ├── optimalx_files/     ← attachments
        └── workshop/           ← workshop projects
            └── <project_id>/
```

Open `manifest.json` to confirm `formatVersion`, `appVersion`, and `dbVersion` before restoring. Everything under a snapshot folder is plain files — deleting the folder deletes that snapshot.

---

## Development

Run the test suite:

```bash
pytest
```

Lint:

```bash
ruff check .
```

Tests are JVM-free; they verify the archive codec, snapshot library, and HTTP server endpoints with `aiohttp.test_utils`. The GUI is not exercised in CI.

---

## Layout

```
desktop/
├── pyproject.toml
├── README.md                        ← this file
├── optimalx_link/
│   ├── __init__.py
│   ├── __main__.py                  app entry point
│   ├── config.py                    ~/.config/OptimalX-Link/config.json
│   ├── archive.py                   write_snapshot_zip / extract_snapshot_zip / read_manifest
│   ├── snapshot_lib.py              CRUD on ~/OptimalX-Link/snapshots/
│   ├── phone_client.py              async HTTP client for the phone
│   ├── server.py                    aiohttp app exposing /v1/* to the phone
│   └── gui/
│       ├── main_window.py           QMainWindow
│       ├── snapshot_list.py         left-pane list of snapshots
│       ├── connection_panel.py      top-pane phone URL + token
│       └── activity_log.py          bottom-pane log
├── tests/                           pytest
└── share/applications/              .desktop file for menu integration
```

Everything outside `optimalx_link/gui/` is GUI-free and unit-testable on a headless box.
