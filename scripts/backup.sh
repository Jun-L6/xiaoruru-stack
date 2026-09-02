#!/usr/bin/env bash
set -Eeuo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
umask 077

mkdir -p backups
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
archive="backups/xiaoruru-stack-$timestamp.tar.gz"
xui_was_running=0

if [[ "$(docker inspect --format '{{.State.Running}}' xiaoruru-xui 2>/dev/null || true)" == "true" ]]; then
    xui_was_running=1
    docker compose stop xui
fi

restore_service() {
    if [[ "$xui_was_running" == "1" ]]; then
        docker compose start xui >/dev/null
    fi
}
trap restore_service EXIT

tar \
    --exclude='./backups' \
    --exclude='./data/logs' \
    --exclude='./data/nginx-runtime' \
    -czf "$archive" \
    compose.yaml \
    compose.bootstrap.yaml \
    .env \
    .env.example \
    .gitignore \
    README.md \
    完整部署说明.md \
    config \
    scripts \
    data/x-ui \
    data/letsencrypt \
    data/certbot-webroot

chmod 600 "$archive"
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$archive" > "$archive.sha256"
else
    shasum -a 256 "$archive" > "$archive.sha256"
fi
chmod 600 "$archive.sha256"

echo "Backup created: $project_dir/$archive"
echo "The archive contains private keys and passwords; encrypt it before off-site storage."
