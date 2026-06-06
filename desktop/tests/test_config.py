"""Tests for the desktop config file."""

from __future__ import annotations

import json
import os
from pathlib import Path

from optimalx_link.config import (
    DesktopConfig,
    generate_bearer_token,
    load_config,
    save_config,
    update_config,
)


def test_generate_bearer_token_unique_and_urlsafe() -> None:
    a = generate_bearer_token()
    b = generate_bearer_token()
    assert a != b
    # 24 raw bytes -> URL-safe base64 -> all chars are in the URL-safe alphabet.
    for token in (a, b):
        for ch in token:
            assert ch.isalnum() or ch in "-_"
    # secrets.token_urlsafe with n=24 yields 32 chars.
    assert len(a) == 32


def test_first_load_creates_config(tmp_path: Path) -> None:
    config_dir = tmp_path / "cfg"
    config = load_config(config_dir=config_dir)
    assert config.desktop_token != ""
    assert config.snapshots_dir != ""
    target = config_dir / "config.json"
    assert target.is_file()
    # File must be readable by owner only.
    mode = target.stat().st_mode & 0o777
    assert mode == 0o600, f"expected mode 600, got {oct(mode)}"


def test_load_returns_same_token_across_runs(tmp_path: Path) -> None:
    config_dir = tmp_path / "cfg"
    first = load_config(config_dir=config_dir)
    second = load_config(config_dir=config_dir)
    assert first.desktop_token == second.desktop_token


def test_save_and_load_round_trip(tmp_path: Path) -> None:
    config_dir = tmp_path / "cfg"
    original = load_config(config_dir=config_dir)
    updated = update_config(
        original,
        last_phone_host="10.0.0.42",
        last_phone_port=22112,
    )
    save_config(updated, config_dir=config_dir)
    reloaded = load_config(config_dir=config_dir)
    assert reloaded.last_phone_host == "10.0.0.42"
    assert reloaded.last_phone_port == 22112
    assert reloaded.desktop_token == updated.desktop_token


def test_corrupt_config_is_replaced(tmp_path: Path) -> None:
    config_dir = tmp_path / "cfg"
    config_dir.mkdir()
    target = config_dir / "config.json"
    target.write_text("this is not valid json at all")
    os.chmod(target, 0o600)

    config = load_config(config_dir=config_dir)
    # We should have gotten a fresh, valid config — not crashed.
    assert config.desktop_token != ""
    parsed = json.loads(target.read_text())
    assert parsed["desktopToken"] == config.desktop_token


def test_to_json_uses_camelcase_keys() -> None:
    config = DesktopConfig(
        desktop_port=1234,
        desktop_token="t",
        last_phone_host="h",
        last_phone_port=5,
        snapshots_dir="/x",
    )
    assert set(config.to_json().keys()) == {
        "desktopPort",
        "desktopToken",
        "lastPhoneHost",
        "lastPhonePort",
        "snapshotsDir",
    }
