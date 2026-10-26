#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/env.sh"
apk_path="${1:-$ANDROID42_PROJECT_ROOT/artifacts/android42/current/DiPlay-Android42-USB-Experimental.apk}"
python3 "$ANDROID42_SOURCE/adaptation/receiver/verify_apk.py" "$apk_path"
"$JAVA_HOME/bin/java" -jar "$DIPLAY_BUILD_TOOLS_DIR/lib/apksigner.jar" verify --verbose --print-certs --min-sdk-version 17 "$apk_path"
"$DIPLAY_BUILD_TOOLS_DIR/zipalign" -c 4 "$apk_path"
"$DIPLAY_BUILD_TOOLS_DIR/aapt" dump badging "$apk_path"
