# ArabiFlow: no-subscription local mode

This setup uses **your own computer**, the GPL-licensed open-source LibreTranslate server, and the existing ArabiFlow APK backend. No third-party API key, rental VPS or paid cloud subscription is required. Electricity, device, storage and initial model downloads still have costs. The backend currently cannot guarantee support for all APKs.

## Requirements

- Windows/macOS/Linux PC with Docker Compose, enough disk space for language models, and ideally 8+ GB memory.
- Android phone, USB data cable, Android developer options + USB debugging enabled, and Google's Android Platform Tools (adb).
- You must have permission to modify the APKs you import. New APKs use a different signature.

## Start (macOS/Linux; Windows: run in WSL or reproduce these Docker Compose steps)

From the repository root:

```sh
sh local/start.sh
adb devices
adb reverse tcp:8000 tcp:8000
```

Wait for LibreTranslate's first-run Arabic model download (this can take several minutes).
To verify the backend without disclosing the token publicly:

```sh
curl http://127.0.0.1:8000/health
# For an authenticated readiness probe (will verify a fixed live translation):
set -a; . local/.env; set +a
curl -H "Authorization: Bearer $ARABIFLOW_API_TOKEN" http://127.0.0.1:8000/ready
```

When `ready` becomes `true`, open ArabiFlow's server settings on the phone. Use `http://127.0.0.1:8000` and copy the private `ARABIFLOW_API_TOKEN` from `local/.env`. Note that `127.0.0.1` on Android reaches the PC **only** while the USB debugging session and adb reverse connection are active. The backend listens only on the computer's loopback address; LibreTranslate is reachable only on Docker's internal network. Do **not** forward 8000 from your router or publish it to the internet.

For Windows PowerShell with Docker Desktop, create `local/.env` yourself containing `ARABIFLOW_API_TOKEN=<64 random hexadecimal characters>`, start with `docker compose --env-file local/.env -f local/docker-compose.yml up -d --build`, then run the same adb reverse command.

To stop, run `docker compose --env-file local/.env -f local/docker-compose.yml down`. Do not use `down -v` unless you intend to delete cached translation models and conversion outputs. The initial model download requires internet, but translation inference runs on your own machine after models are cached.

## Supported scope

The test fixture validates XML resources and straightforward layout-based RTL changes, not all protected/Compose/WebView/native APKs. The default engine may produce weaker translations than paid proprietary models. All downloaded models must support your desired language pairs; the readiness probe tests only an English-to-Arabic phrase. If it fails, inspect `docker compose -f local/docker-compose.yml logs libretranslate` and ensure English and Arabic models are available.

## No-charge safety

Do not use the previously provisioned Railway backend if your requirement is no hosting bill. **Deleting or stopping that Railway service requires a separate explicit decision; the files here do not stop it automatically.** Remove previously entered Railway addresses and tokens from the Android app's trusted server list so it chooses only the local host.
