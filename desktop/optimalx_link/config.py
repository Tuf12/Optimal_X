"""Persistent desktop-app configuration.

Spec: ../app/docs/architecture/OPTIMALX_LINK.md §security-model.

Stores the desktop's own bearer token (used by the phone when it calls into
the desktop), the most recently used phone connection info, and the location
of the on-disk snapshot library. Lives in ``~/.config/OptimalX-Link/config.json``
with mode 600 so other users on a shared box can't read the tokens.

The desktop token is generated on first run and persists across launches so
the phone can save it once. The phone-side token is **not** stored: it
rotates every time the OptimalX Link screen opens on the phone, so the
desktop always re-pastes it on each session.
"""

from __future__ import annotations

import json
import os
import secrets
from dataclasses import dataclass, field, replace
from pathlib import Path

from . import DESKTOP_DEFAULT_PORT


def default_config_dir() -> Path:
    """Honor the XDG spec; fall back to ``~/.config`` for hosts that don't
    set ``XDG_CONFIG_HOME``. Linux Mint sets it by default, but other distros
    or minimal environments don't.
    """
    base = os.environ.get("XDG_CONFIG_HOME")
    if base:
        return Path(base) / "OptimalX-Link"
    return Path.home() / ".config" / "OptimalX-Link"


def default_snapshots_dir() -> Path:
    """Snapshots live under the user's home, not under XDG_DATA_HOME, so they
    are discoverable in the user's file manager. The desktop README documents
    this explicitly.
    """
    return Path.home() / "OptimalX-Link" / "snapshots"


@dataclass(frozen=True)
class DesktopConfig:
    """All on-disk persisted state for the desktop app.

    ``host`` / ``port`` describe the most-recently-used phone Link server.
    They are convenience fields for the GUI; the connection panel re-displays
    them on next launch so the user doesn't have to retype.
    """

    desktop_port: int = DESKTOP_DEFAULT_PORT
    desktop_token: str = ""
    last_phone_host: str = ""
    last_phone_port: int = 17832
    snapshots_dir: str = ""

    def normalized(self) -> DesktopConfig:
        """Fill in derived defaults and ensure the desktop token is non-empty.

        Returns a new config — instances are frozen by intent so callers
        rebind explicitly when mutating.
        """
        out = self
        if not out.desktop_token:
            out = replace(out, desktop_token=generate_bearer_token())
        if not out.snapshots_dir:
            out = replace(out, snapshots_dir=str(default_snapshots_dir()))
        return out

    def to_json(self) -> dict:
        return {
            "desktopPort": self.desktop_port,
            "desktopToken": self.desktop_token,
            "lastPhoneHost": self.last_phone_host,
            "lastPhonePort": self.last_phone_port,
            "snapshotsDir": self.snapshots_dir,
        }

    @staticmethod
    def from_json(raw: dict) -> DesktopConfig:
        return DesktopConfig(
            desktop_port=int(raw.get("desktopPort", DESKTOP_DEFAULT_PORT)),
            desktop_token=str(raw.get("desktopToken", "")),
            last_phone_host=str(raw.get("lastPhoneHost", "")),
            last_phone_port=int(raw.get("lastPhonePort", 17832)),
            snapshots_dir=str(raw.get("snapshotsDir", "")),
        )


def generate_bearer_token(byte_length: int = 24) -> str:
    """URL-safe base64 token. 24 raw bytes → 192 bits of entropy → 32 chars.

    Matches the Android side's ``LinkAuthToken.generate`` output shape so
    the two halves are interchangeable when humans are copying tokens
    between them.
    """
    if byte_length <= 0:
        raise ValueError(f"byte_length must be positive, got {byte_length}")
    return secrets.token_urlsafe(byte_length)


def load_config(config_dir: Path | None = None) -> DesktopConfig:
    """Read config from ``config_dir/config.json``, creating it with sensible
    defaults if it's missing. The returned config is always
    :meth:`DesktopConfig.normalized`.

    File permissions are tightened to 600 on every save in case an upstream
    install copied a more permissive template.
    """
    target = (config_dir or default_config_dir()) / "config.json"
    if not target.is_file():
        config = DesktopConfig().normalized()
        save_config(config, config_dir=config_dir)
        return config
    try:
        raw = json.loads(target.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        # Don't blow up on a corrupted config; start fresh and overwrite. This
        # is the kind of failure a user can't fix without help otherwise.
        config = DesktopConfig().normalized()
        save_config(config, config_dir=config_dir)
        return config
    if not isinstance(raw, dict):
        config = DesktopConfig().normalized()
        save_config(config, config_dir=config_dir)
        return config
    return DesktopConfig.from_json(raw).normalized()


def save_config(config: DesktopConfig, config_dir: Path | None = None) -> Path:
    """Atomically persist ``config`` to ``<config_dir>/config.json`` with
    mode 600. Returns the path written. Atomic rename means a crash during
    write cannot leave a half-written config behind.
    """
    target_dir = config_dir or default_config_dir()
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / "config.json"
    tmp = target.with_suffix(".json.tmp")
    body = json.dumps(config.to_json(), indent=2, ensure_ascii=False)
    tmp.write_text(body, encoding="utf-8")
    os.chmod(tmp, 0o600)
    tmp.replace(target)
    # `replace` doesn't preserve the chmod under some filesystems; reassert.
    os.chmod(target, 0o600)
    return target


# Tiny helper that lets callers `update_config(cfg, last_phone_host="...")`
# without importing `dataclasses.replace`. Encourages immutable usage.
def update_config(config: DesktopConfig, **changes) -> DesktopConfig:
    return replace(config, **changes)


__all__ = [
    "DesktopConfig",
    "default_config_dir",
    "default_snapshots_dir",
    "generate_bearer_token",
    "load_config",
    "save_config",
    "update_config",
]


# Suppress lint warning for `field`, imported for symmetry with the rest of
# the codebase even though no fields use it directly.
_ = field
