# Public API17 source validation — 2026-10-26

Validated on macOS ARM64 with the tools in [BUILD.md](BUILD.md).
No runtime accessory identity, private signing keystore, upload configuration or update endpoint was supplied.

| Check | Result |
| --- | --- |
| Standalone source-only build | Pass; API17, ARMv7, `0.2.36-api17.7-public` / 44 |
| Primary JVM tests | 203 passed |
| Separate IPv6 JVM tests | 2 passed |
| USB ioctl/JNI argument, errno and linkage checks | Passed with synthetic host ioctl |
| Native and Bouncy Castle audio crypto paths | Passed in independent JVMs |
| Native and fallback screen crypto | RFC8439 vectors, 0–1MiB payloads, authenticated rejection/recovery passed |
| Post-shrink mDNS reflection | Passed |
| Post-shrink USB TLS1.2 mutual authentication / roundtrip | Passed with synthetic test certificates |
| Display persistence | Actual preference writer/reader roundtrip, atomic save, 15fps, unrelated values and diagnostic isolation passed |
| APK compatibility | SDK17 class/member audit, single DEX035, ZIP CRC, ARM EABI5 JNI and signature/alignment passed |
| Excluded features | APK verifier found no legacy uploader/updater classes or their configuration assets |
| Public source scan | No credential containers, private-key blocks or private delivery endpoint/token/path references |

The required modern-project CI command was also attempted:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

It stopped during configuration with **CXX1101**: the local NDK `28.2.13676358` installation
has no `source.properties`. Those modern tests/lint/build tasks did not run; they are not reported as passed.
The independent legacy target does not use that NDK installation.

## Vehicle limits

Prior feedback confirmed a visible CarPlay interface using trial A (800×480, 30fps, Surface,
hardware decoding) on the Android 4.2 head unit. This public source-only APK was not installed
on the vehicle and omits runtime identity, so that feedback does not certify this artifact.
Repeated reconnect reliability, larger resolutions/60fps, microphone, audio routing and
vehicle-specific functions still require parked vehicle testing.
Display detection reports Android's metrics; it cannot establish the LCD's physical resolution.
Local diagnostic exports are troubleshooting evidence, not proof that iPhone rendered or accepted every action.
