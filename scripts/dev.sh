#!/usr/bin/env bash
# Keep this launcher executable in the repository.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -DskipTests package
echo BUILD_OK
