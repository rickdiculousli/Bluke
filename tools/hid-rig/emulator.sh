# Sourced by the rigs (tools/hid-rig/run.sh, tools/mirror-rig/run.sh): takes the
# one-rig-per-box lock, boots a headless emulator (unless one is already running),
# builds and installs the debug APK, and shuts the emulator down on exit if it
# started it (KEEP_EMULATOR=1 leaves it up). Sets repo, adb and ANDROID_SERIAL.
#
# Needs ANDROID_HOME (or the default SDK location) and hardware acceleration for
# the emulator (Hypervisor.framework on macOS, /dev/kvm access on Linux).

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

# One rig per box: concurrent runs (say, two worktrees verifying) would share the emulator.
if command -v flock >/dev/null; then
  exec 9>"${TMPDIR:-/tmp}/bluke-hid-rig.lock"
  flock 9
fi
case "$(uname -s)" in
  Darwin) sdk_default="$HOME/Library/Android/sdk" ;;
  *) sdk_default="$HOME/Android/Sdk" ;;
esac
export ANDROID_HOME=${ANDROID_HOME:-$sdk_default}
if [[ -z "${JAVA_HOME:-}" ]] && ! java -version >/dev/null 2>&1 && command -v mise >/dev/null; then
  export JAVA_HOME=$(mise where java@temurin-21)  # k16 keeps its JDK in mise with no global default
fi
case "$(uname -m)" in
  arm64|aarch64) abi=arm64-v8a ;;
  *) abi=x86_64 ;;
esac
image="system-images;android-36;google_apis;$abi"
avd=bluke-rig
adb="$ANDROID_HOME/platform-tools/adb"
emulator="$ANDROID_HOME/emulator/emulator"
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
avdmanager="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

if [[ ! -x "$emulator" || ! -d "$ANDROID_HOME/system-images/android-36/google_apis/$abi" ]]; then
  echo "Installing the emulator and $image" >&2
  yes | "$sdkmanager" --install emulator platform-tools "$image" >/dev/null
fi
if ! "$emulator" -list-avds | grep -qx "$avd"; then
  echo no | "$avdmanager" create avd -n "$avd" -k "$image" -d pixel_6 >/dev/null
fi

started=""
if ! "$adb" devices | grep -q '^emulator-.*device$'; then
  echo "Booting emulator $avd" >&2
  # -wipe-data: Bumble keeps no keys between runs, so the emulator must not keep its bond either.
  "$emulator" -avd "$avd" -wipe-data -no-window -no-audio -no-snapshot -no-boot-anim \
    -gpu swiftshader_indirect >"${TMPDIR:-/tmp}/bluke-rig-emulator.log" 2>&1 &
  started=$!
fi
cleanup() {
  if [[ -n "$started" && -z "${KEEP_EMULATOR:-}" ]]; then
    "$adb" emu kill >/dev/null 2>&1 || kill "$started" 2>/dev/null || true
    wait "$started" 2>/dev/null || true  # emu kill returns early; the next run must not find it half-dead
  fi
}
trap cleanup EXIT

"$adb" -e wait-for-device
for _ in $(seq 1 150); do
  [[ "$("$adb" -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && break
  sleep 2
done
serial=$("$adb" devices | awk '/^emulator-.*device$/ {print $1; exit}')
export ANDROID_SERIAL=$serial

(cd "$repo" && ./gradlew -q assembleDebug)
"$adb" install -r -g "$repo/app/build/outputs/apk/debug/app-debug.apk" >/dev/null

