#!/bin/sh
set -eu
response=$(curl --fail --silent --show-error --max-time 5 \
    "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health") || exit 1
printf '%s' "$response" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'
