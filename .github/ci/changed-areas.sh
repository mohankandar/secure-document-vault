#!/usr/bin/env bash
# Task 0086: which CI jobs a pull request needs. Reads changed paths (one per line) on standard input and prints
# backend=true|false and frontend=true|false. Anything not listed here, or no paths at all, means: run everything.
# The product-name guard always runs, whatever changed.
set -euo pipefail
backend=false
frontend=false
seen=0
while IFS= read -r path || [ -n "$path" ]; do
  [ -n "$path" ] || continue
  seen=1
  case "$path" in
    # Task and report prose: no test reads it.
    docs/work/*.md) ;;
    # Frontend changes run the backend too.
    frontend/*) frontend=true; backend=true ;;
    # The frontend build and its tests read nothing outside frontend/.
    backend/*) backend=true ;;
    *) backend=true; frontend=true ;;
  esac
done
if [ "$seen" = 0 ]; then
  backend=true
  frontend=true
fi
echo "backend=$backend"
echo "frontend=$frontend"
