#!/usr/bin/env bash
# Type-checks bluke-mirror for macOS (aarch64-apple-darwin) from Linux. ring's build script
# compiles C, so zig stands in as the cross C compiler (zigcc-darwin, zigar beside this script).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here/.."
export CC_aarch64_apple_darwin="$here/zigcc-darwin"
export AR_aarch64_apple_darwin="$here/zigar"
cargo=(cargo)
if command -v mise >/dev/null 2>&1; then
  cargo=(mise exec rust@1.99.0 -- cargo)
fi
"${cargo[@]}" check --target aarch64-apple-darwin -p bluke-mirror --bins --lib "$@"
"${cargo[@]}" check --target aarch64-apple-darwin -p bluke-mirror --features rig --all-targets "$@"
