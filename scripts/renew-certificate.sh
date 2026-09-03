#!/usr/bin/env bash
set -Eeuo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if [[ ! -f .env ]]; then
    echo "Missing $project_dir/.env" >&2
    exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

certificate="data/letsencrypt/live/${CERT_NAME:?CERT_NAME is required}/fullchain.pem"
if [[ ! -s "$certificate" ]]; then
    echo "Certificate not found: $project_dir/$certificate" >&2
    exit 1
fi

certificate_digest() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$certificate" | awk '{print $1}'
    else
        shasum -a 256 "$certificate" | awk '{print $1}'
    fi
}

before_digest="$(certificate_digest)"

docker compose --profile ops run --rm certbot \
    renew \
    --cert-name "$CERT_NAME" \
    --webroot \
    --webroot-path /var/www/certbot \
    --quiet

after_digest="$(certificate_digest)"

if [[ "$before_digest" != "$after_digest" ]]; then
    docker compose exec -T nginx-ui nginx -t
    docker compose exec -T nginx-ui nginx -s reload
    docker compose restart gost xui
    echo "Certificate renewed; Nginx was reloaded and TLS proxy services were restarted."
else
    echo "Certificate is not due for renewal."
fi
