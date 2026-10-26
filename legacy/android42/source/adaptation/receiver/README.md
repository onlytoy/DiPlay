# Android 4.2 wired receiver — public source

This standalone API17 / ARMv7 target retains USB projection, persistent display settings,
local black-screen diagnosis and manual report export. It contains no report uploader or app updater.

Read the repository's [Android 4.2 entry](../../../../../ANDROID42.md),
[build guide](../../../../../docs/android42/BUILD.md),
[validation](../../../../../docs/android42/VALIDATION.md) and
[source provenance](../../../../../docs/android42/CHANGES.md).

Runtime accessory identity and APK signing keys are external inputs. Default builds omit
runtime identity and cannot complete iPhone authentication. Host test success does not establish vehicle compatibility.
