#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q test
echo ALL_TESTS_PASSED
