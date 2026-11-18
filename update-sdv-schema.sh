#!/usr/bin/env bash
# The Vero schema step: run by the customer's database team, with the database owner's credentials.
#
#   ./update-sdv-schema.sh status                 lists the changesets that would run (changes nothing)
#   ./update-sdv-schema.sh sql --output FILE      writes the SQL that "update" would run, for review
#   ./update-sdv-schema.sh update                 applies the changesets
#
# Options: --url jdbc:postgresql://host:5432/db, --username USER, --jar PATH (default: the sdv-web jar next to this
# script, else backend/sdv-web/target). Or set SDV_SCHEMA_DB_URL and SDV_SCHEMA_DB_USERNAME.
# The password: SDV_SCHEMA_DB_PASSWORD, or typed when asked. Vero never stores it, and it is never an option.
#
# It runs the Liquibase bundled in the Vero application jar, always with the "production" context, so local test
# data can never be added. Starting Vero never changes the database; it refuses to serve until this step has run.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar=""
args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar) [[ $# -ge 2 ]] || { echo "Missing value after --jar." >&2; exit 2; }; jar="$2"; shift 2 ;;
    *) args+=("$1"); shift ;;
  esac
done

if [[ -z "$jar" ]]; then
  for dir in "$here" "$here/backend/sdv-web/target"; do
    mapfile -t found < <(find "$dir" -maxdepth 1 -type f -name 'sdv-web-*.jar' ! -name '*.original' 2>/dev/null)
    if [[ ${#found[@]} -gt 1 ]]; then echo "More than one Vero application jar in $dir. Say which one with --jar." >&2; exit 2; fi
    if [[ ${#found[@]} -eq 1 ]]; then jar="${found[0]}"; break; fi
  done
fi
[[ -n "$jar" && -f "$jar" ]] || { echo "The Vero application jar was not found. Say where it is with --jar." >&2; exit 2; }
command -v java >/dev/null || { echo "Java 21 is needed to run the schema step, and java was not found." >&2; exit 2; }

# Java asks for the password itself when SDV_SCHEMA_DB_PASSWORD is not set (it needs a terminal for that).
exec java -cp "$jar" -Dloader.main=com.sdv.platform.web.schema.SchemaStep \
  org.springframework.boot.loader.launch.PropertiesLauncher "${args[@]}"
