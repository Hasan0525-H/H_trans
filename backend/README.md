# Processing service deployment

The Android app does not execute Apktool on the phone. Host this opt-in service under a **trusted HTTPS URL**, configure that URL and its bearer token inside the app, and only process APKs that you may lawfully modify. Production deployments must isolate the container and enforce TLS, upload caps, authentication, quotas, filesystem cleanup and request-rate limiting.

## إعداد الخادم لتشغيل التطبيق

لا يتضمن APK خادمًا مجانيًا جاهزًا. ستحتاج خادم Linux/Docker تملكه وعنوان HTTPS، بالإضافة إلى مزوّد ترجمة. هذا الإعداد يُجرى مرة واحدة:

1. انشر هذا المجلد على خادمك مع Docker وثبّت شهادة HTTPS على نطاقك.
2. أنشئ رمزًا سريًا عشوائيًا لـ `ARABIFLOW_API_TOKEN` وأضف عنوان مزوّد الترجمة ومفتاحه بحسب النوع.
3. افتح تطبيق ArabiFlow AI ← الإعدادات، وأدخل رابط الخادم بصيغة `https://...` والرمز نفسه.
4. اضغط «حفظ الإعدادات». سيرسل التطبيق طلبًا محميًا إلى `/ready` قبل السماح بأي رفع ملف.

فحص `/ready` يختبر الإعدادات المحلية ووجود Apktool وأدوات Android SDK، **ولا يضمن** وصول خدمة الترجمة الخارجية أو نجاح تعريب أي APK. لا تضع الرمز في مستودع GitHub أو رسالة عامة. لا تستخدم نطاقًا افتراضيًا باعتباره خادمًا حقيقيًا.

## Prerequisites

- Docker or Linux with Python 3.12, Java 21, Apktool **3.0.3**, Android SDK build tools 35 (zipalign + apksigner), keytool.
- A trusted HTTPS translation endpoint implementing LibreTranslate's POST /translate API, and optionally its API key. Translation is an external processing service; evaluate its privacy policy before uploading proprietary content.
- Set strong random ARABIFLOW_API_TOKEN, LIBRETRANSLATE_URL and optional LIBRETRANSLATE_API_KEY in server environment. Never commit credentials.

## AI translation (optional)

To use an OpenAI-compatible model endpoint instead of LibreTranslate, set:

    TRANSLATION_PROVIDER=openai_compatible
    AI_BASE_URL=https://your-trusted-provider.example/v1
    AI_MODEL=your-model-id
    AI_API_KEY=your-secret

The server uses a translation-only system instruction, passes text as data, preserves placeholder tokens, and refuses corrupted responses. An endpoint is not included: you must supply your own trusted account and credentials. The provider may process third-party intellectual property; review its terms and obtain consent. Large projects may incur substantial translation costs.

## Development

    cd backend
    python3 -m venv .venv
    . .venv/bin/activate
    pip install -r requirements.txt
    PYTHONPATH=. pytest -q tests
    export ARABIFLOW_API_TOKEN='<generated-unique-random-secret>'
    export LIBRETRANSLATE_URL='https://your-trusted-translator.example'
    uvicorn arabiflow.api:app --host 127.0.0.1 --port 8000

## Container behind HTTPS reverse proxy

    docker build -t arabiflow-backend backend/
    docker run --rm --read-only --cpus=2 --memory=6g --pids-limit=256 \
      --security-opt=no-new-privileges --tmpfs /srv/work:rw,noexec,nosuid,size=4g \
      -v arabiflow-results:/srv/out -p 127.0.0.1:8000:8000 \
      -e ARABIFLOW_API_TOKEN -e LIBRETRANSLATE_URL \
      -e LIBRETRANSLATE_API_KEY arabiflow-backend

Configure Caddy/nginx with a valid certificate for the public domain, proxy to local port 8000, and limit request body size to 512 MiB. Never expose the HTTP port directly to the internet.

## API

- GET /health: liveness only, no sensitive metadata
- GET /ready: bearer-authenticated prerequisite check; does not test a real translation
- POST /jobs: authenticated multipart upload under form name apk; returns {id,status}
- GET /jobs/{id}: authenticated progress 0–100, diagnostic status/report
- GET /jobs/{id}/download: authenticated signed APK once completed
- DELETE /jobs/{id}: authenticated cleanup for completed or failed jobs

Do not interpret /health as proof that tools or translation are configured. The Android client checks /ready before a conversion.

All authenticated calls require Authorization: Bearer <token>. Jobs are currently stored in process memory: use one backend worker; an instance restart interrupts active jobs and invalidates their IDs. For high availability, replace the in-memory scheduler with a durable queue and object storage.

**Known limitations:** One conventional resource-bearing base APK at a time; not AAB, XAPK, APKM or split APK sets. Resource XML and literal layout text are handled. Compiled Kotlin/Java strings, native binaries, Compose code, image text, custom drawings, WebViews, runtime downloads and apps with anti-tamper are not automatically localized. The temporary signing key is unique to the conversion, so the result **cannot update** the original developer's installed package. Do not remove original installed apps automatically. Do not describe the output as a verified fully-Arabic build until tested manually.

**Privacy:** The service currently retains successful output files until deleted using the authenticated delete endpoint or server administrator cleanup. Rotate the bearer token and clean retained output files regularly. No uploaded APK is deliberately executed, but decoding untrusted APKs still has risk: run the container with strict OS sandboxing and keep Apktool patched.
