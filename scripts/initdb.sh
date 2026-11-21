#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
DB="${1:-aegis.db}"
rm -f "$DB"
sqlite3 "$DB" < aegis-persistence/src/main/resources/db/001_base_schema.sql
sqlite3 "$DB" < aegis-persistence/src/main/resources/db/002_showcase_expansion.sql
echo "TABLES=$(sqlite3 "$DB" "SELECT count(*) FROM sqlite_master WHERE type='table';")"
sqlite3 "$DB" 'PRAGMA foreign_key_check;'
