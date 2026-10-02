#!/bin/sh
set -eu
cd "$(dirname "$0")"
if ! command -v docker >/dev/null 2>&1; then
  echo "Install Docker Desktop or Docker Engine first." >&2
  exit 1
fi
if [ ! -f .env ]; then
  if command -v openssl >/dev/null 2>&1; then
    SECRET="$(openssl rand -hex 32)"
  elif command -v python3 >/dev/null 2>&1; then
    SECRET="$(python3 -c 'import secrets;print(secrets.token_hex(32))')"
  else
    echo "Install openssl or python3 to generate a strong random token." >&2
    exit 1
  fi
  umask 077
  printf 'ARABIFLOW_API_TOKEN=%s\n' "$SECRET" > .env
  echo "Saved a private token to local/.env (gitignored)."
fi
docker compose --env-file .env -f docker-compose.yml up -d --build
echo ""
echo "Server starting. First launch downloads offline models; readiness may take several minutes."
echo "Connect your Android device by USB with USB debugging, then execute:"
echo "    adb reverse tcp:8000 tcp:8000"
echo "Set the app URL to http://127.0.0.1:8000."
echo "Read the private ARABIFLOW_API_TOKEN from local/.env and enter it ONLY on your device."
echo "Stop local services with: docker compose --env-file .env -f docker-compose.yml down"
