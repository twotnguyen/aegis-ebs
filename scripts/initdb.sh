#!/usr/bin/env bash
# Refuses to overwrite an existing database unless --force is supplied.
set -euo pipefail
cd "$(dirname "$0")/.."
FORCE=0
if [ "${1:-}" = "--force" ]; then FORCE=1; shift; fi
DB="${1:-aegis.db}"

command -v sqlite3 >/dev/null 2>&1 || { echo "initdb: sqlite3 CLI is required" >&2; exit 3; }
for schema in 001_base_schema.sql 002_showcase_expansion.sql; do
  [ -f "aegis-persistence/src/main/resources/db/$schema" ] || {
    echo "initdb: missing schema $schema" >&2; exit 3; }
done
if [ -e "$DB" ] && [ "$FORCE" != "1" ]; then
  echo "initdb: $DB already exists; pass --force to overwrite (destructive)" >&2
  exit 2
fi
rm -f "$DB" "$DB-wal" "$DB-shm"
sqlite3 "$DB" < aegis-persistence/src/main/resources/db/001_base_schema.sql
sqlite3 "$DB" < aegis-persistence/src/main/resources/db/002_showcase_expansion.sql
echo "TABLES=$(sqlite3 "$DB" "SELECT count(*) FROM sqlite_master WHERE type='table';")"
sqlite3 "$DB" 'PRAGMA foreign_key_check;'
