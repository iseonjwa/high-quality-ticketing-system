#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -m unittest discover -s script/tests -v
docker compose -f compose.test.yml up -d --wait --wait-timeout 120
./gradlew clean build --no-daemon
python3 script/check-config-failure.py
script/records-check.sh
git diff --check
