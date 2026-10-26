# Build the Android 4.2 wired receiver

Run from the repository root. The legacy target is independent of the modern Gradle modules.
The validated host is macOS ARM64: JDK 17, Android API17 platform, Build Tools 34.0.0,
Kotlin compiler 1.9.24, Zig 0.13.0, OpenSSL 3, Python 3, clang and zip.
Host-native tests currently use Darwin JNI headers and dylib flags; Linux/Windows hosts are not validated.

```sh
export JAVA_HOME=/absolute/path/to/jdk-17
export DIPLAY_PLATFORM_JAR=/absolute/path/to/android-sdk/platforms/android-17/android.jar
export DIPLAY_BUILD_TOOLS_DIR=/absolute/path/to/android-sdk/build-tools/34.0.0
export DIPLAY_KOTLIN_DIR=/absolute/path/to/kotlinc
export DIPLAY_ZIG=/absolute/path/to/zig-0.13.0/zig
export DIPLAY_DEPS_DIR="$PWD/.local/android42/dependencies"
# Ensure OpenSSL 3 is on PATH for synthetic test certificates.
./scripts/android42/build.sh
./scripts/android42/check.sh
./scripts/android42/verify.sh
```

`fetch_dependencies.py` downloads pinned JARs from Maven Central and verifies every SHA256
against `legacy/android42/source/adaptation/receiver/dependency-sha256.json`.
The scripts do not download Android SDKs, compilers or runtime identity assets.

Output: `artifacts/android42/current/DiPlay-Android42-USB-Experimental.apk`.
This is a local source-only compatibility build, not a distributed working CarPlay release.
The build does not discover private configuration automatically. There is no log-upload or update configuration.

## Authentication and signing

Without externally provisioned compatible receiver identity, iPhone authentication cannot complete.
To intentionally supply your own authorized runtime inputs, set `DIPLAY_AUTH_ASSETS_DIR`
to an external directory containing `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`.
The build verifier checks that the packaged files exactly match those explicitly selected inputs.
Tests use synthetic, short-lived identities generated under the ignored build directory;
these identities cannot substitute for real accessory authentication.

The default build generates a test keystore in the receiver's ignored `build/` directory.
Preserve it if you need later APKs with the same signing identity. Clean builds on another
machine generate a different key and cannot replace an installation signed by the old key.
To supply a keystore deliberately, export `DIPLAY_SIGNING_KEYSTORE`, optionally
`DIPLAY_SIGNING_ALIAS` (default `receiver`), `DIPLAY_SIGNING_STORE_PASSWORD` and
`DIPLAY_SIGNING_KEY_PASSWORD`. For generated test keys, the script uses a public test password.
Do not commit keystores, private keys, pairing records, raw device reports or APKs.

## Source locations

- `legacy/android42/source/adaptation/receiver/`: native widgets, services, compatibility adapters, JNI and tests.
- `legacy/android42/source/shared/`: pinned protocol/media sources selected by `prepare_sources.py`.
- `legacy/android42/source/common/src/main/res/`: attributed upstream string inputs for the native UI.
- `scripts/android42/`: source-only build, test and verification wrappers.

`prepare_sources.py` writes ignored generated sources and SHA256 manifests, plus reproducible
license/translation assets. The original source files remain unmodified so patches remain traceable.
Root Gradle commands build the modern project, not this API17 receiver.
