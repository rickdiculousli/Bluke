#!/usr/bin/env bash
# One command for the end-to-end HID check: boot the emulator and install the
# debug APK (emulator.sh), then run rig.py.
#
#   tools/hid-rig/run.sh            # from the repo root
#   KEEP_EMULATOR=1 tools/hid-rig/run.sh
#
# Needs uv, plus what emulator.sh needs.
set -euo pipefail

source "$(dirname "$0")/emulator.sh"

cd "$repo/tools/hid-rig"
if ! GRPC_VERBOSITY=ERROR uv run --quiet python rig.py "$@" 2> >(grep -v fork_posix >&2); then
  logcat="${TMPDIR:-/tmp}/bluke-rig-logcat.txt"
  "$adb" logcat -d >"$logcat" 2>&1 || true
  echo "rig failed; the emulator's log is in $logcat" >&2
  exit 1
fi
