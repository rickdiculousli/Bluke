#!/usr/bin/env bash
# End-to-end Mirror check: the companion's synthetic source and recording
# injector (cargo feature `rig`) against the debug app on the emulator, reached
# through `adb forward`. Takes the HID rig's lock and reuses its emulator boot.
#
#   tools/mirror-rig/run.sh            # from the repo root
#   KEEP_EMULATOR=1 tools/mirror-rig/run.sh
#   RUNS=300 tools/mirror-rig/run.sh   # timing runs (default 100)
#
# Needs Rust through mise (companion/rust-toolchain.toml pins it) and python3,
# plus what tools/hid-rig/emulator.sh needs.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
(cd "$here/../../companion" && mise exec rust@"$(sed -n 's/^channel *= *"\(.*\)"/\1/p' rust-toolchain.toml)" -- \
  cargo build --quiet --release -p bluke-mirror --features rig --example rig)
export MIRROR_RIG_COMPANION="$here/../../companion/target/release/examples/rig"

source "$here/../hid-rig/emulator.sh"

if ! python3 "$here/rig.py" "$@"; then
  logcat="${TMPDIR:-/tmp}/bluke-mirror-rig-logcat.txt"
  "$adb" logcat -d >"$logcat" 2>&1 || true
  echo "mirror rig failed; the emulator's log is in $logcat" >&2
  exit 1
fi
