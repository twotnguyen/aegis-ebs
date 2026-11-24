#!/usr/bin/env bash
# Keep this launcher executable in the repository.
# POSIX chmod bit is part of the committed contract.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -DskipTests package
echo BUILD_OK
