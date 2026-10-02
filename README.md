# ArabiFlow AI

Android APK Arabic localization client and opt-in, self-hosted processing service. See `docs/ARCHITECTURE.md` for threat model and supported APK formats.

**Important:** Rebuilt APKs have a different signature. This cannot update apps signed by other developers, and no engine can guarantee translation of all runtime or hardcoded UI. Only process packages you own or have permission to modify. APK uploads go only to the service URL that you configure.

## Build

- Android: Java 17, Android SDK 35, Gradle 8.10.2, `gradle :app:assembleDebug`.
- Backend: Python 3.12; `cd backend && pip install -r requirements.txt && pytest`.
- To run the conversion backend, install Apktool 3.0.2+, Android SDK build-tools (`zipalign`, `apksigner`), and set a translation provider (`LIBRETRANSLATE_URL` or a compatible endpoint). For deployment see `backend/README.md`.

The public CI produces a **debug APK**, not a signed Play Store production release. Configure your own release keystore for distribution. No real user APKs or translation-service credentials are committed to this repository.
