#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "$0")" && pwd -P)"
RUN="$ROOT/../runtime/run"
tick() { awk '{print $22}' "/proc/$1/stat"; }
stop_owned() {
  local name="$1" file="$RUN/$1.pid" pid started pgid i
  [[ -f "$file" ]] || { echo "$name: no owned PID; leaving other processes alone"; return 0; }
  read -r pid started <"$file" || return 1
  if [[ ! "$pid" =~ ^[0-9]+$ || ! "$started" =~ ^[0-9]+$ || ! -r "/proc/$pid/stat" ]]; then
    rm -f -- "$file"
    echo "$name: stale PID record removed"
    return 0
  fi
  [[ "$(tick "$pid")" == "$started" ]] || { echo "$name: PID reused, refusing to stop" >&2; return 1; }
  pgid="$(ps -o pgid= -p "$pid" | tr -d ' ')"
  [[ "$pgid" == "$pid" ]] || { echo "$name: unexpected process group; refusing stop" >&2; return 1; }
  echo "Stopping SDV $name (group $pgid)"
  kill -TERM -- "-$pgid" 2>/dev/null || true
  for ((i=0;i<15;i++)); do
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "$name remains running; inspect manually" >&2
    return 1
  fi
  rm -f -- "$file"
}
stop_owned frontend
stop_owned backend
echo "Vault, PostgreSQL, Docker, LexaVault and SDV DEMO untouched."
