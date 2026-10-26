#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/env.sh"
cd "$ANDROID42_SOURCE"
python3 adaptation/receiver/fetch_dependencies.py
sh adaptation/receiver/build.sh
python3 adaptation/receiver/verify_apk.py
release_dir="$ANDROID42_PROJECT_ROOT/artifacts/android42/current"
mkdir -p "$release_dir"
cp adaptation/output/DiPlay-Android42-USB-Experimental.apk "$release_dir/DiPlay-Android42-USB-Experimental.apk"
(cd "$release_dir" && shasum -a 256 DiPlay-Android42-USB-Experimental.apk > SHA256SUMS)
echo "Built: $release_dir/DiPlay-Android42-USB-Experimental.apk"
echo "Next: ./scripts/android42/check.sh"
