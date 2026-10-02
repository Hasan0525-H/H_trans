# ArabiFlow AI

Android APK Arabic localization client with **real offline APK archive inspection**, and an opt-in self-hosted cloud localization service. See `docs/ARCHITECTURE.md` for threat model and supported APK formats.

**Important:** Rebuilt APKs have a different signature. This cannot update apps signed by other developers, and no engine can guarantee translation of all runtime or hardcoded UI. Only process packages you own or have permission to modify. APK uploads go only to the service URL that you configure.

## How the application works

1. Tap **فحص APK محليًا** to import an APK and inspect archive entries, DEX count, resource path candidates, package name, version, and signing-certificate fingerprint locally. No upload is performed during inspection.
2. From the local results screen, tap **بدء التعريب وإعادة بناء APK** to request server-backed conversion. Without a configured HTTPS server and token, the app opens setup instead of starting a doomed job.
3. Conversion requires an operator-managed backend and translator; the app cannot autonomously create a free host. Use [backend setup](backend/README.md).

**Limit:** the offline pass checks ZIP entry paths, not all compiled resources or decompiled strings. A successful integration test validates one small project-owned APK, not arbitrary third-party packages or installation on every Android version.

**Debug installation:** GitHub-hosted CI debug APKs may have different debug signing keys across runs. If Android refuses to replace the previous debug build, do not uninstall without first considering that you will lose app-private history. For stable upgrades, configure a privately held release signing key and use the signed release workflow.

## Build

- Android: Java 17, Android SDK 35, Gradle 8.10.2, `gradle :app:assembleDebug`.
- Backend: Python 3.12; `cd backend && pip install -r requirements.txt && pytest`.
- To run the conversion backend, install Apktool 3.0.3 (SHA-256 pinned in CI), Android SDK build-tools (`zipalign`, `apksigner`), and set a translation provider (`LIBRETRANSLATE_URL` or a compatible endpoint). For deployment see `backend/README.md`.

The public CI produces a **debug APK**, not a signed Play Store production release. Configure your own release keystore for distribution. No real user APKs or translation-service credentials are committed to this repository.
