#!/usr/bin/env bash
set -Eeuo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if [[ ! -f .env ]]; then
    echo "Missing .env; copy .env.example and replace all change_me values." >&2
    exit 1
fi

if grep -q 'change_me' .env; then
    echo ".env still contains change_me placeholders." >&2
    exit 1
fi

for script in scripts/*.sh; do
    bash -n "$script"
done

docker compose config --quiet
docker compose -f compose.yaml -f compose.bootstrap.yaml config --quiet

echo "Static validation passed. No containers were started."
