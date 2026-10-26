#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/env.sh"
cd "$ANDROID42_SOURCE"
if [[ ! -f adaptation/receiver/build/receiver.jar ]]; then
    echo 'Run ./scripts/android42/build.sh from the project root first.' >&2
    exit 1
fi
sh adaptation/receiver/test.sh
sh adaptation/receiver/test-packaging.sh
build_dir="$ANDROID42_SOURCE/adaptation/receiver/build"
classpath="$build_dir/receiver.jar:$DIPLAY_KOTLIN_DIR/lib/kotlin-stdlib.jar:$DIPLAY_DEPS_DIR/bcprov-jdk15to18-1.79.jar"
mkdir -p "$ANDROID42_LOCAL/checks"
"$JAVA_HOME/bin/javac" -cp "$classpath:$DIPLAY_PLATFORM_JAR" -d "$ANDROID42_LOCAL/checks" "$ANDROID42_SOURCE/adaptation/receiver/build-tools/CheckDisplayPersistence.java"
"$JAVA_HOME/bin/java" -cp "$ANDROID42_LOCAL/checks:$classpath:$DIPLAY_PLATFORM_JAR" CheckDisplayPersistence
"$ANDROID42_PROJECT_ROOT/scripts/android42/verify.sh" "$ANDROID42_SOURCE/adaptation/output/DiPlay-Android42-USB-Experimental.apk"
