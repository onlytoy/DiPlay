#!/usr/bin/env bash
# Source-only defaults: never discover or bundle private authentication/signing inputs.
set -euo pipefail
ANDROID42_PROJECT_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
ANDROID42_SOURCE="$ANDROID42_PROJECT_ROOT/legacy/android42/source"
ANDROID42_LOCAL="$ANDROID42_PROJECT_ROOT/.local/android42"
if [[ -n "${DIPLAY_JDK_HOME:-}" ]]; then
    export JAVA_HOME="$DIPLAY_JDK_HOME"
elif [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -x /usr/libexec/java_home ]]; then
        export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    else
        echo 'Set JAVA_HOME or DIPLAY_JDK_HOME to JDK 17. Read docs/android42/BUILD.md.' >&2
        return 1
    fi
fi
export DIPLAY_PROBE_JDK_DIR="$JAVA_HOME"
export DIPLAY_PLATFORM_JAR="${DIPLAY_PLATFORM_JAR:-$ANDROID42_LOCAL/toolchain/android-4.2.2/android.jar}"
export DIPLAY_BUILD_TOOLS_DIR="${DIPLAY_BUILD_TOOLS_DIR:-$ANDROID42_LOCAL/toolchain/android-14}"
export DIPLAY_KOTLIN_DIR="${DIPLAY_KOTLIN_DIR:-$ANDROID42_LOCAL/toolchain/kotlinc}"
export DIPLAY_ZIG="${DIPLAY_ZIG:-$ANDROID42_LOCAL/toolchain/zig-macos-aarch64-0.13.0/zig}"
export DIPLAY_DEPS_DIR="${DIPLAY_DEPS_DIR:-$ANDROID42_LOCAL/dependencies}"
export DIPLAY_MIN_SDK=17
export DIPLAY_TARGET_SDK=17
for required in "$JAVA_HOME/bin/java" "$DIPLAY_PLATFORM_JAR" "$DIPLAY_BUILD_TOOLS_DIR/aapt" \
    "$DIPLAY_BUILD_TOOLS_DIR/lib/d8.jar" "$DIPLAY_KOTLIN_DIR/lib/kotlin-compiler.jar" "$DIPLAY_ZIG"; do
    if [[ ! -f "$required" ]]; then
        echo "Missing Android 4.2 build input: $required. Read docs/android42/BUILD.md." >&2
        return 1
    fi
done
