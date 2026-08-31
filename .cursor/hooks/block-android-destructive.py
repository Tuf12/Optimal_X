#!/usr/bin/env python3
"""Cursor `beforeShellExecution` hook.

Blocks Android commands that wipe the OptimalX Room database on device.

Input  (stdin):  {"command": "...", "cwd": "...", "sandbox": "..."}
Output (stdout): {"permission": "allow" | "deny", "agent_message": "...", "user_message": "..."}

Patterns blocked (only when they appear at the START of a subcommand, so
`echo "gradlew installDebug"` and `git log --grep installDebug` are NOT blocked):

  gradlew install<Variant>              reinstall, can force uninstall on signature/version mismatch
  gradlew uninstall*                    always wipes data
  gradlew connected*AndroidTest|Check  installs APK(s) on device before running instrumented tests
  gradlew deviceAndroidTest|deviceCheck installs APK(s) on device before running instrumented tests
  gradlew ... --uninstall-first         explicit destructive flag
  adb uninstall <pkg>                   wipes data
  adb install <apk>  (without -r)       reinstalling without -r fails on conflict -> uninstall path
  adb shell pm|cmd package uninstall|clear   wipes data
  adb shell rm on app data / optimalx.db     deletes Room database files
  pm uninstall|clear                    already-on-device variant
  adb install -r -d <apk>               reinstalls APK(s) on device
"""

from __future__ import annotations

import json
import re
import shlex
import sys

OPTIMALX_PACKAGE = "com.example.optimalx"

INSTALL_VARIANT_RE = re.compile(r"^:?(?:[A-Za-z0-9_-]+:)*install[A-Z][A-Za-z0-9]*$")
UNINSTALL_TASK_RE = re.compile(r"^:?(?:[A-Za-z0-9_-]+:)*uninstall[A-Za-z0-9]*$")
# Transitive installDebug / install*AndroidTest — can uninstall/reinstall and wipe unbacked-up data.
ON_DEVICE_INSTRUMENTED_TEST_RE = re.compile(
    r"^:?(?:[A-Za-z0-9_-]+:)*"
    r"(?:connected(?:Debug|Release)?(?:AndroidTest|Check)"
    r"|connectedAndroidTest"
    r"|device(?:AndroidTest|Check))$"
)


def emit_allow() -> None:
    sys.stdout.write('{"permission":"allow"}')
    sys.exit(0)


def emit_deny(reason: str) -> None:
    sys.stdout.write(json.dumps({
        "permission": "deny",
        "agent_message": (
            "Blocked by project hook (.cursor/hooks/block-android-destructive.py): "
            "this command would wipe or risk wiping the OptimalX Room database on the device. "
            f"Reason: {reason}. "
            "Do NOT retry with a workaround. If the user truly wants to reinstall or run "
            "on-device tests, ask them first. Data-preserving alternatives: "
            "'./gradlew :app:testDebugUnitTest', "
            "'./gradlew :app:compileDebugAndroidTestKotlin', "
            "'adb install -r -d <apk>', "
            "'adb shell am force-stop com.example.optimalx', "
            "or run from Android Studio when the user chooses."
        ),
        "user_message": f"Cursor blocked a destructive Android command ({reason}).",
    }))
    sys.exit(0)


try:
    payload = json.load(sys.stdin)
except Exception:
    emit_allow()

cmd = (payload.get("command") or "").strip()
if not cmd:
    emit_allow()


# Split the full command line into subcommands by shell operators that start
# a new command: ; && || | & and newlines. Also handle $( ... ) and ` ... `
# substitutions by treating their contents as separate subcommands.
SPLIT_RE = re.compile(r"(?:;|&&|\|\||(?<!\|)\|(?!\|)|&|\n)")


def expand_substitutions(s: str) -> list[str]:
    parts = [s]
    parts.extend(m.group(1) for m in re.finditer(r"\$\(([^()]*)\)", s))
    parts.extend(m.group(1) for m in re.finditer(r"`([^`]*)`", s))
    return parts


def subcommands(line: str) -> list[list[str]]:
    out: list[list[str]] = []
    for chunk in expand_substitutions(line):
        for piece in SPLIT_RE.split(chunk):
            piece = piece.strip()
            if not piece:
                continue
            try:
                tokens = shlex.split(piece, posix=True)
            except ValueError:
                tokens = piece.split()
            # Strip leading env-var assignments: FOO=bar BAZ=qux real-cmd ...
            i = 0
            while i < len(tokens) and re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", tokens[i]):
                i += 1
            tokens = tokens[i:]
            if tokens:
                out.append(tokens)
    return out


def basename(prog: str) -> str:
    return prog.rsplit("/", 1)[-1]


def shell_targets_app_storage(shell_cmd: str) -> bool:
    lowered = shell_cmd.lower()
    if "optimalx.db" in lowered:
        return True
    if OPTIMALX_PACKAGE in shell_cmd:
        return True
    if "/data/data/" in lowered and "optimalx" in lowered:
        return True
    if re.search(rf"/data/user/\d+/{re.escape(OPTIMALX_PACKAGE)}\b", shell_cmd):
        return True
    return False


def check_adb_shell(shell_cmd: str) -> None:
    if re.search(r"(^|[\s;&|])(pm|cmd\s+package)\s+(uninstall|clear)\b", shell_cmd):
        emit_deny("adb shell pm/cmd package uninstall/clear")
    if re.search(r"\brm\b", shell_cmd) and shell_targets_app_storage(shell_cmd):
        emit_deny("adb shell rm on app data or database")
    if re.search(r"\bdeleteDatabase\b", shell_cmd) and shell_targets_app_storage(shell_cmd):
        emit_deny("adb shell deleteDatabase on app storage")


for tokens in subcommands(cmd):
    prog = basename(tokens[0])
    args = tokens[1:]

    # ---- gradlew / gradle tasks ----
    if prog in ("gradlew", "gradle"):
        if "--uninstall-first" in args:
            emit_deny("--uninstall-first flag")
        for a in args:
            if a.startswith("-"):
                continue
            if UNINSTALL_TASK_RE.match(a):
                emit_deny(f"gradlew uninstall task ({a})")
            if INSTALL_VARIANT_RE.match(a):
                emit_deny(
                    f"gradlew install variant task ({a}) - "
                    "can wipe data on signature/version mismatch"
                )
            if ON_DEVICE_INSTRUMENTED_TEST_RE.match(a):
                emit_deny(
                    f"gradlew on-device instrumented test task ({a}) - "
                    "installs APK(s) on device and can wipe unbacked-up data"
                )

    # ---- adb ----
    if prog == "adb":
        # Strip adb global options: -d, -e, -s <serial>, -H <host>, -P <port>, -t <id>, -L <socket>
        i = 0
        while i < len(args):
            a = args[i]
            if a in ("-d", "-e"):
                i += 1
            elif a in ("-s", "-H", "-P", "-t", "-L"):
                i += 2
            elif a.startswith("-"):
                i += 1
            else:
                break
        sub = args[i:]
        if sub:
            verb = sub[0]
            verb_args = sub[1:]
            if verb == "uninstall":
                emit_deny("adb uninstall")
            if verb in ("install", "install-multiple", "install-multi-package"):
                if not any(a == "-r" for a in verb_args):
                    emit_deny(f"adb {verb} without -r (would require uninstall on conflict)")
            if verb == "shell" and verb_args:
                check_adb_shell(" ".join(verb_args))

    # ---- bare pm (when shell is already on device) ----
    if prog == "pm" and args and args[0] in ("uninstall", "clear"):
        emit_deny(f"pm {args[0]}")

    # ---- bundletool (install-apks pushes a fresh install path) ----
    if prog == "bundletool" and any(a in ("install-apks", "install-apk") for a in args):
        emit_deny("bundletool install-apks/install-apk (installs on device)")


emit_allow()
