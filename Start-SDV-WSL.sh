#!/usr/bin/env bash
# LOCAL SDV DEV launcher. Never creates/reinitializes Vault or touches DEMO.
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "$0")" && pwd -P)"
TOKEN_FILE="$HOME/backups/sdv-dev-vault/sdv-app.token"
VAULT_URL="http://127.0.0.1:8200"
DB_CONTAINER="sdv-postgres"
VAULT_CONTAINER="sdv-vault"
LOG_DIR=""
RUNTIME_DIR=""
usage() {
  cat <<'HELP'
Usage: bash Start-SDV-WSL.sh [options]
  --app-dir PATH         Existing SDV repository
  --token-file PATH      Restricted application token file
  --logs-dir PATH        External rolling-log directory
  --runtime-dir PATH     External process metadata directory
  --vault-url URL        Existing loopback Vault
  --db-container NAME    Existing PostgreSQL container
  --vault-container NAME Existing persistent Vault container
  --help                 Show usage

Prerequisites must already be running. This script does NOT create containers,
start Docker, initialize or unseal Vault, disable encryption, or touch SDV DEMO.
HELP
}
while (($#)); do
  case "$1" in
    --app-dir|--token-file|--logs-dir|--runtime-dir|--vault-url|--db-container|--vault-container)
      [[ $# -ge 2 && -n "$2" ]] || { echo "Missing value for $1" >&2; exit 2; }
      case "$1" in
        --app-dir) ROOT="$2" ;;
        --token-file) TOKEN_FILE="$2" ;;
        --logs-dir) LOG_DIR="$2" ;;
        --runtime-dir) RUNTIME_DIR="$2" ;;
        --vault-url) VAULT_URL="$2" ;;
        --db-container) DB_CONTAINER="$2" ;;
        --vault-container) VAULT_CONTAINER="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -d "$ROOT" ]] || { echo "SDV checkout unavailable: $ROOT" >&2; exit 1; }
ROOT="$(cd -- "$ROOT" && pwd -P)"
PARENT="$(dirname "$ROOT")"
[[ -n "$LOG_DIR" ]] || LOG_DIR="$PARENT/logs"
[[ -n "$RUNTIME_DIR" ]] || RUNTIME_DIR="$PARENT/runtime"
LOG_DIR="$(realpath -m -- "$LOG_DIR")"
RUNTIME_DIR="$(realpath -m -- "$RUNTIME_DIR")"
case "$LOG_DIR/" in "$ROOT/"*) echo "Logs must be OUTSIDE source repository" >&2; exit 1 ;; esac
case "$RUNTIME_DIR/" in "$ROOT/"*) echo "Runtime files must be OUTSIDE source repository" >&2; exit 1 ;; esac
RUN="$RUNTIME_DIR/run"
BACKEND_LOG="$LOG_DIR/backend/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend/frontend.log"
JAR="$ROOT/backend/sdv-web/target/sdv-web-0.1.0-SNAPSHOT.jar"
FRONTEND="$ROOT/frontend/sdv-ui"

fail() { echo "[SDV DEV] ERROR: $*" >&2; exit 1; }
require() { command -v "$1" >/dev/null 2>&1 || fail "Missing dependency: $1"; }
for cmd in docker java npm python3 curl ss setsid ps realpath; do require "$cmd"; done
[[ -s "$JAR" ]] || fail "Built SDV backend JAR missing: $JAR"
[[ -d "$FRONTEND/node_modules" ]] || fail "Frontend dependencies missing. Run npm ci first."
[[ -s "$TOKEN_FILE" ]] || fail "Application Vault token file missing: $TOKEN_FILE"
[[ "$VAULT_URL" == http://127.0.0.1:* || "$VAULT_URL" == http://localhost:* ]] ||
  fail "DEV Vault must be loopback."
docker info >/dev/null 2>&1 || fail "Docker unavailable. Start Docker Desktop manually."
for name in "$DB_CONTAINER" "$VAULT_CONTAINER"; do
  docker inspect "$name" >/dev/null 2>&1 || fail "Required container '$name' missing. Will NOT create replacement."
  [[ "$(docker inspect --format '{{.State.Running}}' "$name")" == true ]] ||
    fail "Required container '$name' is stopped. Start it manually."
done
docker exec "$DB_CONTAINER" pg_isready -U sdv -d sdv >/dev/null 2>&1 ||
  fail "SDV PostgreSQL not ready"
python3 "$ROOT/scripts/sdv-vault-preflight.py" "$VAULT_URL" "$TOKEN_FILE" ||
  fail "Existing Vault unavailable, sealed, or application-token Transit permissions invalid."

umask 077
mkdir -p -- "$RUN" "$LOG_DIR/backend" "$LOG_DIR/frontend"
chmod 700 "$RUN" "$LOG_DIR/backend" "$LOG_DIR/frontend"
tick() { awk '{print $22}' "/proc/$1/stat"; }
owned() {
  local name="$1" file="$RUN/$1.pid" pid t
  [[ -f "$file" ]] || return 1
  read -r pid t < "$file" || return 1
  [[ "$pid" =~ ^[0-9]+$ && "$t" =~ ^[0-9]+$ && -r "/proc/$pid/stat" ]] || return 1
  [[ "$(tick "$pid")" == "$t" ]]
}
healthy() { curl -fsS --max-time 2 "$1" 2>/dev/null; }
launch() {
  local name="$1" port="$2" wd="$3" logfile="$4"
  shift 4
  if owned "$name"; then
    echo "$name already owned by the SDV WSL launcher"
    return 0
  fi
  if ss -ltn "( sport = :$port )" 2>/dev/null | grep -q LISTEN; then
    fail "Port $port is occupied but no matching launcher PID was found. Refusing takeover."
  fi
  (
    cd "$wd"
    exec setsid python3 "$ROOT/scripts/sdv-rolling-runner.py" "$logfile" "$@"
  ) >/dev/null 2>&1 </dev/null &
  local pid=$!
  sleep 0.3
  [[ -r "/proc/$pid/stat" ]] || fail "$name exited immediately. Check $logfile"
  printf '%s %s\n' "$pid" "$(tick "$pid")" > "$RUN/$name.pid"
}
echo "=== SDV WSL DEV startup ==="
echo "App: $ROOT"
echo "Logs: $LOG_DIR"
echo "Run: $RUN"
echo "Required dependencies: PostgreSQL and unsealed persistent Vault confirmed."
export SDV_KEY_PROVIDER=vault
export SDV_VAULT_BASE_URL="$VAULT_URL"
export SDV_VAULT_TOKEN_FILE="$TOKEN_FILE"
unset SDV_VAULT_TOKEN || true
launch backend 8280 "$ROOT" "$BACKEND_LOG" java -jar "$JAR" --spring.profiles.active=local
backend_healthy=false
for ((i=0;i<90;i++)); do
  if healthy http://127.0.0.1:8280/actuator/health | grep -q '"status":"UP"'; then
    if healthy http://127.0.0.1:8280/api/v1/system/info | grep -q '"productCode":"SDV"'; then
      backend_healthy=true
      break
    fi
  fi
  sleep 1
done
[[ "$backend_healthy" == true ]] || fail "SDV backend failed health/product check; see $BACKEND_LOG"
launch frontend 5183 "$FRONTEND" "$FRONTEND_LOG" npm run dev -- --host 127.0.0.1 --port 5183 --strictPort
frontend_healthy=false
for ((i=0;i<45;i++)); do
  if healthy http://127.0.0.1:5183/ >/dev/null; then frontend_healthy=true; break; fi
  sleep 1
done
[[ "$frontend_healthy" == true ]] || fail "SDV frontend unavailable; see $FRONTEND_LOG"
echo "SDV DEV READY: http://localhost:5183"
echo "Backend: http://localhost:8280/actuator/health"
echo "Stop: bash Stop-SDV-WSL.sh"
echo "PostgreSQL, persistent Vault, LexaVault and DEMO remain independently managed."
