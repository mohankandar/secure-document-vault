#!/usr/bin/env bash
# Start the existing SDV DEV stack from Ubuntu/WSL2.
# Does not build code, initialize Vault, recreate containers, or touch DEMO.
set -Eeuo pipefail

ROOT="$HOME/projects/sdv/secure-document-vault"
JAR="$ROOT/backend/sdv-web/target/sdv-web-0.1.0-SNAPSHOT.jar"
FRONTEND="$ROOT/frontend/sdv-ui"
VAULT_INIT="$HOME/backups/sdv-dev-vault/initialization.json"
VAULT_TOKEN_FILE="$HOME/backups/sdv-dev-vault/sdv-app.token"
RUNTIME="$HOME/.local/state/sdv-dev"

say() { printf '\n[SDV DEV] %s\n' "$*"; }
fail() { printf '\n[SDV DEV] ERROR: %s\n' "$*" >&2; exit 1; }
ready_http() { curl --silent --show-error --fail --max-time 2 "$1" 2>/dev/null; }

[[ -d "$ROOT" ]] || fail "Repository not found: $ROOT"
[[ -s "$JAR" ]] || fail "Backend JAR missing; build SDV first: $JAR"
[[ -d "$FRONTEND/node_modules" ]] || fail "Frontend dependencies missing; run npm ci in $FRONTEND"
[[ -s "$VAULT_TOKEN_FILE" ]] || fail "SDV application Vault token missing: $VAULT_TOKEN_FILE"
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
for name in sdv-postgres sdv-vault; do
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
  if docker exec sdv-postgres pg_isready -U sdv -d sdv >/dev/null 2>&1; then
    db_ready=true
    break
  fi
  sleep 1
done
[[ "$db_ready" == "true" ]] || fail "PostgreSQL did not become ready. Check: docker logs sdv-postgres --tail 60"

say "Checking Vault seal status"
python3 - "$VAULT_INIT" <<'PY'
import json
import sys
from pathlib import Path
from urllib.request import Request, urlopen

base = "http://127.0.0.1:8200/v1"

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
  [[ ! -e /proc/net/tcp ]] || true
  if (ss -ltn 2>/dev/null | grep -qE '[:.]8280[[:space:]]'); then
    fail "Port 8280 is in use, but SDV health is unavailable. See existing backend logs/process."
  fi
  say "Starting SDV backend (log: $RUNTIME/backend.log)"
  (
    cd "$ROOT"
    nohup env -u SDV_VAULT_TOKEN \
      SDV_KEY_PROVIDER=vault \
      SDV_VAULT_BASE_URL=http://127.0.0.1:8200 \
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
