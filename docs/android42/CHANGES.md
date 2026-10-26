# Android 4.2 wired adaptation and provenance

The repository's modern project remains on upstream commit
`858a7ee` (DiPlay 0.2.15). The standalone legacy target has a different history:

- Legacy receiver starting point: [AIxBits/DiPlay android-4.3](https://github.com/AIxBits/DiPlay/tree/cedb0a049f2206e0329a6f51193a8fc7d2bc6889), commit `cedb0a049f2206e0329a6f51193a8fc7d2bc6889`.
- Pinned protocol/media source: DiPlay 0.2.8, commit `f2d06951b4e8114dbb62f551c12a32a845a3042f`; selected original files live under the nested `shared/` tree.
- Selective later screen-crypto and H.264 recovery fixes were adapted from the 0.2.94 review; this is not a complete 0.2.94 protocol upgrade.
- Native crypto: Monocypher 4.0.2. Vendor provenance and file hashes accompany its source; BSD-2-Clause / CC0 notices are retained.

## Retained changes

1. API17 four-argument USB transfers, bounded 16KiB chunks, short-write continuation and legacy interface descriptors; freestanding ARMv7 configuration/interface ioctl support.
2. Explicit session/resource ownership and asynchronous stop completion. New USB sessions wait for actual reader/transport teardown; stalled shutdown remains visible in local evidence.
3. Native authenticated video decryption, bounded video queue and SPS/PPS recovery. Surface/Texture and hardware/software decoder comparisons support black-screen diagnosis.
4. Android-reported display metrics; selectable projection sizes and 15/30/60fps settings, persisted for subsequent connections. Current sessions retain their active geometry until the user reconnects.
5. Wired-only entry points; local media/vehicle tests and a four-trial black-screen comparison. Diagnostic reports are redacted, retained locally (up to 20) and included in manual export.

## Public edition boundary

All legacy log-upload transports, queues, background services, receivers and UI hooks were removed.
All legacy version-check, APK-download, installation-update code and publish/server deployment scripts were excluded.
The source-only APK verifier rejects those classes and configuration assets if reintroduced.
The public branch contains neither private device logs nor server addresses, credentials, runtime identities or release-signing keys.

The original project license and dependency notices are preserved in `legacy/android42/source/LICENSE`,
`docs/licenses/` and receiver assets. Root modern sources are inherited from the original repository;
this standalone adaptation does not extend the original maintainer's compatibility claims.
