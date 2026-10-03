# ArabiFlow AI

Phone-only Android APK resource editor with Google ML Kit on-device neural translation, local RTL manifest editing, rebuilding and signature verification. No server, computer, paid API or account is required. Initial model downloads require Wi-Fi. See `docs/ARCHITECTURE.md` for threat model and supported APK formats.

**Important:** Rebuilt APKs have a different signature. This cannot update apps signed by other developers, and no engine can guarantee translation of all runtime or hardcoded UI. Only process packages you own or have permission to modify. APK uploads go only to the service URL that you configure.

## Zero-subscription local setup

To avoid paid cloud providers completely, use the [free local Docker Compose setup](local/README.md) on your own PC. It runs our existing APK backend together with the open-source LibreTranslate engine. Connect your Android phone over USB using `adb reverse`, and configure `http://127.0.0.1:8000` with the locally generated bearer token. This uses no paid API or paid server, although your computer, electricity, storage and any network downloads are your responsibility. It is **not** a fully phone-only converter.

The earlier Railway project is **not free without limitations**, is not used by the local mode and may incur account charges: stop it separately if you require strictly no hosting spend. No paid cloud integration is required by the Android app.

## Android phone-only workflow

Install the current v0.2+ Android build, import one conventional base APK and tap **بدء التعريب على الهاتف**. The app parses binary resources locally with ARSCLib, translates phrases included in a compact offline starter glossary, sets the manifest's RTL support flag, writes a modified APK and signs it with a personal key generated in Android Keystore.

**Current limits:** This is a real but deliberately limited proof-of-function, **not** an offline AI model. ML Kit can translate supported resource-language strings after its model download, but short strings and special formatting may not translate. Unknown phrases, native code, text inside DEX/Compose/WebView/images, all layout mirroring, protected packages, and split APK sets are outside its coverage. It currently refuses to produce an output if it cannot translate at least one known phrase. Device-side conversions are limited to 128 MiB input to reduce out-of-memory failure; passing CI build/tests does not establish runtime compatibility on all Android phones.

## Archived optional backend

The codebase still includes the optional older backend for controlled testing, but the Android UI now uses the phone-only worker by default and never uploads imported files. Avoid running the previously configured Railway backend if your goal is zero hosting cost.

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
