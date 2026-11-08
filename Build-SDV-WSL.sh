#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "$0")" && pwd -P)"
SKIP=false
for opt in "$@"; do
  case "$opt" in
    --skip-tests) SKIP=true ;;
    -h|--help) echo "Usage: bash Build-SDV-WSL.sh [--skip-tests]"; exit 0 ;;
    *) echo "Unknown option: $opt" >&2; exit 2 ;;
  esac
done
require() { command -v "$1" >/dev/null || { echo "Missing prerequisite: $1" >&2; exit 1; }; }
for cmd in java mvn node npm; do require "$cmd"; done
[[ -f "$ROOT/backend/pom.xml" && -f "$ROOT/frontend/sdv-ui/package.json" ]] || { echo "SDV project files missing" >&2; exit 1; }
echo "SDV Ubuntu full build: Java 21, Maven reactor, frontend production"
java -version
mvn -version
node --version
npm --version
cd "$ROOT/backend"
if "$SKIP"; then mvn clean verify -DskipTests; else mvn clean verify; fi
cd "$ROOT/frontend/sdv-ui"
[[ -f package-lock.json ]] || { echo "Missing package-lock.json: refusing dependency-lock changes" >&2; exit 1; }
npm ci
npm run build
echo "SDV FULL BUILD PASSED"
