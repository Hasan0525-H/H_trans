# ArabiFlow AI — current architecture and boundaries

Android (Kotlin, Compose, MVVM) -> HTTPS job API -> isolated conversion pipeline -> authenticated download -> Android FileProvider/SAF. History is local Room; token is Android Keystore AES-GCM encrypted. WorkManager owns durable phone-side background upload, polling and download. UI reads Room to avoid fabricated progress.

Server steps: ZIP safety inspection -> Apktool 3 decode -> XML resource inspection -> provider-backed translation with placeholder validation -> values-ar and layout RTL rewrite -> Apktool rebuild (one fallback) -> zipalign -> new ephemeral keystore -> apksigner sign and verify -> ZIP integrity check -> report. No shell invocation on untrusted filenames; subprocess is argument-array only. Service operator must add rate limiting, isolation and durable job storage before hosting for external users.

## Threat model

Treat all APKs as untrusted archives; enforce upload and expansion caps, reject traversal paths/encrypted entries/duplicate paths, run under a non-root isolated account, cap execution time, and disable outbound networking for unpack/rebuild tools through separate sandbox policy if offered by the deployment platform. Remote translation may transfer proprietary text to a third party; obtain user consent and evaluate its data handling. This service is not an app-signature bypass and does not restore privileged permissions or proprietary publisher keys.

## Product gates before broad commercial release

1. Device/instrumentation testing across Android 8–15 including scoped storage, file shares and user-driven installs.
2. Integration tests against owned fixture APKs using a real translation server, Apktool and Android SDK tools; install, launch and compare screenshots in Arabic and English.
3. Improve resource coverage: formatted spans, all locale-specific variants, dex-derived literals where authorized, RTL assets, language detection and translator glossary/context.
4. Production infrastructure: durable queued jobs, rate limiting and quotas, cryptographic artifact hash verification, 24h TTL data retention, privacy controls, metrics/observability, and malware scanning of uploads and outputs.
5. CI success + signed release keystore securely stored in CI + Play policy review for REQUEST_INSTALL_PACKAGES. Current CI artifact is debug only.
