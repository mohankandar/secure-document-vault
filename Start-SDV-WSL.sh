#!/usr/bin/env bash
# Start the existing SDV DEV stack from Ubuntu/WSL2.
# Does not build code, initialize Vault, recreate containers, or touch DEMO.
set -Eeuo pipefail

# Paths default to the current checkout, but can be supplied at runtime.
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VAULT_INIT="${SDV_DEV_VAULT_INIT_FILE:-$HOME/backups/sdv-dev-vault/initialization.json}"
VAULT_TOKEN_FILE="${SDV_DEV_VAULT_TOKEN_FILE:-$HOME/backups/sdv-dev-vault/sdv-app.token}"
VAULT_URL="${SDV_DEV_VAULT_URL:-http://127.0.0.1:8200}"
DB_CONTAINER="${SDV_DEV_DB_CONTAINER:-sdv-postgres}"
VAULT_CONTAINER="${SDV_DEV_VAULT_CONTAINER:-sdv-vault}"
LOG_DIR=""

usage() {
  cat <<'HELP'
Start-SDV-WSL.sh: existing SDV local/DEV environment ONLY, not production.
Usage: bash Start-SDV-WSL.sh [options]
  --app-dir PATH           SDV checkout (default: directory containing script)
  --token-file PATH        Restricted Vault application token file
  --init-file PATH         Existing Vault initialization JSON (optional if unsealed)
  --logs-dir PATH          Override sibling log directory
  --vault-url URL          Loopback Vault endpoint (default: http://127.0.0.1:8200)
  --db-container NAME      Existing PostgreSQL container
  --vault-container NAME   Existing Vault container
  --help                   Show help
HELP
}
while (( $# )); do
  case "$1" in
    --app-dir|--token-file|--init-file|--logs-dir|--vault-url|--db-container|--vault-container)
      (( $# >= 2 )) || { echo "Missing value for $1" >&2; exit 2; }
      case "$1" in
        --app-dir) ROOT="$2";;
        --token-file) VAULT_TOKEN_FILE="$2";;
        --init-file) VAULT_INIT="$2";;
        --logs-dir) LOG_DIR="$2";;
        --vault-url) VAULT_URL="$2";;
        --db-container) DB_CONTAINER="$2";;
        --vault-container) VAULT_CONTAINER="$2";;
      esac
      shift 2;;
    --help|-h) usage; exit 0;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2;;
  esac
done
[[ -d "$ROOT" ]] || { echo "Application directory not found: $ROOT" >&2; exit 1; }
ROOT="$(cd "$ROOT" && pwd -P)"
[[ -n "$LOG_DIR" ]] || LOG_DIR="$(dirname "$ROOT")/logs"
JAR="$ROOT/backend/sdv-web/target/sdv-web-0.1.0-SNAPSHOT.jar"
FRONTEND="$ROOT/frontend/sdv-ui"
RUNTIME="$LOG_DIR"

say() { printf '\n[SDV DEV] %s\n' "$*"; }
fail() { printf '\n[SDV DEV] ERROR: %s\n' "$*" >&2; exit 1; }
ready_http() { curl --silent --show-error --fail --max-time 2 "$1" 2>/dev/null; }

[[ -d "$ROOT" ]] || fail "Repository not found: $ROOT"
[[ -s "$JAR" ]] || fail "Backend JAR missing; build SDV first: $JAR"
[[ -d "$FRONTEND/node_modules" ]] || fail "Frontend dependencies missing; run npm ci in $FRONTEND"
[[ -s "$VAULT_TOKEN_FILE" ]] || fail "SDV application Vault token missing: $VAULT_TOKEN_FILE"
[[ "$VAULT_URL" == http://127.0.0.1:* || "$VAULT_URL" == http://localhost:* ]] ||
  fail "Local DEV launcher requires loopback Vault. Production needs an independent deployment."
say "Application: $ROOT"
say "Logs: $LOG_DIR"
for tool in docker curl python3 java npm; do
  command -v "$tool" >/dev/null 2>&1 || fail "Missing command: $tool"
done

if ! docker info >/dev/null 2>&1; then
  fail "Docker engine is unavailable. Start Docker Desktop and enable Ubuntu WSL integration, then retry."
fi

mkdir -p "$RUNTIME"
chmod 700 "$RUNTIME"
umask 077

# Reuse existing named containers and their durable volumes. Never use compose down -v.
for name in "$DB_CONTAINER" "$VAULT_CONTAINER"; do
  docker container inspect "$name" >/dev/null 2>&1 ||
    fail "Existing container $name not found. Stop; do not initialize a replacement automatically."
  running="$(docker inspect --format '{{.State.Running}}' "$name")"
  if [[ "$running" != "true" ]]; then
    say "Starting existing container $name"
    docker start "$name" >/dev/null
  else
    say "Container $name already running"
  fi
done

say "Checking PostgreSQL readiness"
db_ready=false
for _ in $(seq 1 40); do
  if docker exec "$DB_CONTAINER" pg_isready -U sdv -d sdv >/dev/null 2>&1; then
    db_ready=true
    break
  fi
  sleep 1
done
[[ "$db_ready" == "true" ]] || fail "PostgreSQL did not become ready. Check: docker logs "$DB_CONTAINER" --tail 60"

say "Checking Vault seal status"
python3 - "$VAULT_INIT" "$VAULT_URL" <<'PY'
import json
import sys
from pathlib import Path
from urllib.request import Request, urlopen

base = sys.argv[2].rstrip("/") + "/v1"

def call(path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = Request(
        base + path,
        data=data,
        method="PUT" if payload is not None else "GET",
        headers={"Content-Type": "application/json"},
    )
    with urlopen(req, timeout=10) as response:
        return json.load(response)

status = call("/sys/seal-status")
if not status.get("initialized"):
    raise SystemExit("ERROR: Vault is not initialized. Refusing to initialize or replace it.")
if status["sealed"]:
    keyfile = Path(sys.argv[1])
    if not keyfile.is_file():
        raise SystemExit("ERROR: Vault is sealed and the existing initialization backup is missing. Unseal manually using KeePassXC.")
    unseal_key = json.loads(keyfile.read_text())["unseal_keys_b64"][0]
    status = call("/sys/unseal", {"key": unseal_key})
    del unseal_key
if status["sealed"]:
    raise SystemExit("ERROR: Vault remains sealed.")
print("Vault initialized: true; sealed: false")
PY

# Refuse to confuse an unrelated service on the same port with SDV.
if ready_http "http://127.0.0.1:8280/actuator/health" | grep -q '"status":"UP"'; then
  ready_http "http://127.0.0.1:8280/api/v1/system/info" | grep -q '"productCode":"SDV"' ||
    fail "Port 8280 responds, but is not recognized as SDV. Stop and inspect manually."
  say "Backend already healthy on port 8280"
else
  if (ss -ltn 2>/dev/null | grep -qE '[:.]8280[[:space:]]'); then
    fail "Port 8280 is in use, but SDV health is unavailable. See existing backend logs/process."
  fi
  say "Starting SDV backend (log: $RUNTIME/backend.log)"
  (
    cd "$ROOT"
    nohup env -u SDV_VAULT_TOKEN \
      SDV_KEY_PROVIDER=vault \
      SDV_VAULT_BASE_URL="$VAULT_URL" \
      SDV_VAULT_TOKEN_FILE="$VAULT_TOKEN_FILE" \
      java -jar "$JAR" --spring.profiles.active=local \
      >"$RUNTIME/backend.log" 2>&1 </dev/null &
    echo $! > "$RUNTIME/backend.pid"
  )
  healthy=false
  for _ in $(seq 1 90); do
    if ready_http "http://127.0.0.1:8280/actuator/health" | grep -q '"status":"UP"'; then
      healthy=true
      break
    fi
    sleep 1
  done
  if [[ "$healthy" != "true" ]]; then
    tail -n 45 "$RUNTIME/backend.log" >&2 || true
    fail "Backend failed to become healthy; see $RUNTIME/backend.log"
  fi
fi

if ready_http "http://127.0.0.1:5183/" >/dev/null; then
  say "Frontend already responding on port 5183"
else
  if (ss -ltn 2>/dev/null | grep -qE '[:.]5183[[:space:]]'); then
    fail "Port 5183 is in use, but the frontend is not responding."
  fi
  say "Starting SDV frontend (log: $RUNTIME/frontend.log)"
  (
    cd "$FRONTEND"
    nohup npm run dev -- --host 127.0.0.1 --port 5183 --strictPort \
      >"$RUNTIME/frontend.log" 2>&1 </dev/null &
    echo $! > "$RUNTIME/frontend.pid"
  )
  frontend_ready=false
  for _ in $(seq 1 45); do
    if ready_http "http://127.0.0.1:5183/" >/dev/null; then
      frontend_ready=true
      break
    fi
    sleep 1
  done
  if [[ "$frontend_ready" != "true" ]]; then
    tail -n 45 "$RUNTIME/frontend.log" >&2 || true
    fail "Frontend failed to become ready; see $RUNTIME/frontend.log"
  fi
fi

say "All SDV DEV services are ready"
printf 'Backend:  http://localhost:8280/actuator/health\n'
printf 'Frontend: http://localhost:5183/\n'
printf 'Logs:     %s\n' "$RUNTIME"
printf 'This script starts SDV DEV only; SDV DEMO is not touched.\n'
