#!/usr/bin/env bash
# Vero's setup command (task 0041): platform-core's setup for product SDV, with Vero's product roles.
#
#   ./setup.sh plan  --licence licence.json --signin signin.json [other setup options]
#   ./setup.sh apply --licence licence.json --signin signin.json [other setup options]
#
# This wrapper fills in what Vero ships: --product SDV, --product-roles (Vero's product-roles.json). The licence
# keys it trusts are inside the setup jar (task 0067); no option changes them. Every other option is passed on. Database settings come from the environment
# only (PLATFORM_SETUP_DB_URL, PLATFORM_SETUP_DB_USER, PLATFORM_SETUP_DB_PASSWORD); this script never prints them.
# Install guide: docs/install/SETUP.md.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ $# -ge 1 && ( "$1" == "plan" || "$1" == "apply" ) ]] || { echo "The first word must be plan or apply." >&2; exit 2; }
action="$1"; shift
jar=""; args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar) [[ $# -ge 2 ]] || { echo "Missing value after --jar." >&2; exit 2; }; jar="$2"; shift 2 ;;
    --product|--product-roles) echo "$1 is set by this wrapper (Vero is product SDV)." >&2; exit 2 ;;
    *) args+=("$1"); shift ;;
  esac
done

if [[ -z "$jar" ]]; then
  for dir in "$here" "$here/backend/sdv-setup/target"; do
    mapfile -t found < <(find "$dir" -maxdepth 1 -type f -name 'sdv-setup-*.jar' ! -name '*.original' 2>/dev/null)
    if [[ ${#found[@]} -gt 1 ]]; then echo "More than one Vero setup jar in $dir. Say which one with --jar." >&2; exit 2; fi
    if [[ ${#found[@]} -eq 1 ]]; then jar="${found[0]}"; break; fi
  done
fi
[[ -n "$jar" && -f "$jar" ]] || { echo "The Vero setup jar (sdv-setup-*.jar) was not found. Say where it is with --jar." >&2; exit 2; }

roles="$here/product-roles.json"
[[ -f "$roles" ]] || roles="$here/backend/sdv-security/src/main/resources/setup/product-roles.json"
[[ -f "$roles" ]] || { echo "Vero's product-roles.json was not found next to this script." >&2; exit 2; }

command -v java >/dev/null || { echo "Java 21 is needed to run setup, and java was not found." >&2; exit 2; }

exec java -jar "$jar" "$action" --product SDV --product-roles "$roles" "${args[@]}"
