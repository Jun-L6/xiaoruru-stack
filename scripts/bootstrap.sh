#!/usr/bin/env bash
set -Eeuo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
umask 077

if [[ ! -f .env ]]; then
    echo "Missing $project_dir/.env" >&2
    exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

required=(DOMAIN ROOT_DOMAIN XUI_ADMIN_USERNAME XUI_ADMIN_PASSWORD GOST_USERNAME GOST_PASSWORD)
for name in "${required[@]}"; do
    if [[ -z "${!name:-}" ]]; then
        echo "Missing required variable in .env: $name" >&2
        exit 1
    fi
done

for command_name in docker; do
    command -v "$command_name" >/dev/null 2>&1 || {
        echo "Required command not found: $command_name" >&2
        exit 1
    }
done

docker compose version >/dev/null
docker compose config --quiet

# Bind-mounted helper files must remain readable inside containers even when
# the project archive was extracted under a restrictive umask.
chmod 755 scripts/gost-entrypoint.sh
chmod 644 \
    scripts/init-panel.py \
    config/gost/gost.yml.template \
    config/nginx/nginx.conf \
    config/nginx/templates/default.conf.template \
    config/nginx/templates-bootstrap/default.conf.template

mkdir -p \
    data/x-ui \
    data/letsencrypt \
    data/certbot-webroot \
    data/nginx-runtime \
    data/bootstrap-output \
    data/logs/nginx \
    data/logs/x-ui \
    backups

# The Nginx worker runs as an unprivileged user and must be able to traverse
# the shared ACME webroot. Certbot writes challenge files here with public
# tokens, so directory traversal permission is required but secrets are not
# stored in this path.
chmod 755 data/certbot-webroot
find data/certbot-webroot -type d -exec chmod 755 {} +
chmod 700 data/bootstrap-output

new_database=0
if [[ ! -s data/x-ui/x-ui.db ]]; then
    new_database=1
fi

certificate="data/letsencrypt/live/$DOMAIN/fullchain.pem"
if [[ ! -s "$certificate" ]]; then
    echo "Starting the HTTP-only bootstrap endpoint..."
    docker compose -f compose.yaml -f compose.bootstrap.yaml up -d xui nginx
else
    echo "Existing certificate found; starting the normal endpoint..."
    docker compose up -d xui nginx
fi

echo "Waiting for 3x-ui to become healthy..."
for _ in {1..60}; do
    status="$(docker inspect --format '{{.State.Health.Status}}' xiaoruru-xui 2>/dev/null || true)"
    [[ "$status" == "healthy" ]] && break
    sleep 2
done
status="$(docker inspect --format '{{.State.Health.Status}}' xiaoruru-xui 2>/dev/null || true)"
if [[ "$status" != "healthy" ]]; then
    echo "3x-ui did not become healthy. Check: docker compose logs xui" >&2
    exit 1
fi

if [[ "$new_database" == "1" ]]; then
    echo "Applying the private 3x-ui administrator credentials from .env..."
    docker compose stop xui
    docker compose run --rm --no-deps --entrypoint /app/x-ui xui \
        setting \
        -username "$XUI_ADMIN_USERNAME" \
        -password "$XUI_ADMIN_PASSWORD" \
        -webBasePath "${XUI_WEB_BASE_PATH:-/}" \
        -listenIP "0.0.0.0"
    docker compose start xui
fi

if [[ ! -s "$certificate" ]]; then
    certbot_args=(
        certonly
        --webroot
        --webroot-path /var/www/certbot
        --cert-name "$DOMAIN"
        --domain "$DOMAIN"
        --domain "$ROOT_DOMAIN"
        --agree-tos
        --non-interactive
        --keep-until-expiring
    )

    if [[ -n "${LE_EMAIL:-}" ]]; then
        certbot_args+=(--email "$LE_EMAIL" --no-eff-email)
    else
        certbot_args+=(--register-unsafely-without-email)
    fi
    if [[ "${LE_STAGING:-0}" == "1" ]]; then
        certbot_args+=(--staging)
    fi

    echo "Requesting the TLS certificate..."
    docker compose --profile ops run --rm certbot "${certbot_args[@]}"
fi

echo "Configuring the panel, subscriptions, inbounds, and primary client..."
docker compose --profile ops run --rm panel-init

echo "Restarting 3x-ui so the subscription listener uses the saved settings..."
docker compose restart xui
for _ in {1..60}; do
    status="$(docker inspect --format '{{.State.Health.Status}}' xiaoruru-xui 2>/dev/null || true)"
    [[ "$status" == "healthy" ]] && break
    sleep 2
done
status="$(docker inspect --format '{{.State.Health.Status}}' xiaoruru-xui 2>/dev/null || true)"
if [[ "$status" != "healthy" ]]; then
    echo "3x-ui did not recover after panel initialization." >&2
    exit 1
fi

echo "Starting the complete stack..."
docker compose up -d --force-recreate xui nginx gost
docker compose ps

echo
echo "Deployment started. Credentials are stored only in $project_dir/.env"
