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
BLOG_DOMAIN="${BLOG_DOMAIN:-xiaoruru.beer}"
set +a

required=(
    XUI_DOMAIN
    NGINX_UI_DOMAIN
    CPAMP_DOMAIN
    CPA_API_DOMAIN
    GOST_DOMAIN
    CERT_NAME
    GATEWAY_NETWORK
    DOCKER_SUBNET
    XUI_ADMIN_USERNAME
    XUI_ADMIN_PASSWORD
    GOST_USERNAME
    GOST_PASSWORD
)
for name in "${required[@]}"; do
    if [[ -z "${!name:-}" ]]; then
        echo "Missing required variable in .env: $name" >&2
        exit 1
    fi
done

command -v docker >/dev/null 2>&1 || {
    echo "Required command not found: docker" >&2
    exit 1
}
docker compose version >/dev/null
./scripts/validate.sh
command -v python3 >/dev/null
command -v openssl >/dev/null
docker image inspect "${BLOG_IMAGE:-xiaoruru/rurublog:local}" >/dev/null 2>&1 || {
    echo "Blog image missing. Build/load it first; see 完整部署说明.md section 5." >&2
    exit 1
}
python3 scripts/init-blog.py

chmod 755 scripts/*.sh
chmod 644 scripts/init-panel.py config/gost/gost.yml.template
find config/nginx-ui -type f -exec chmod 644 {} +

mkdir -p \
    data/x-ui \
    data/nginx \
    data/nginx-ui \
    data/letsencrypt \
    data/certbot-webroot \
    data/bootstrap-output \
    data/logs/nginx \
    data/logs/x-ui
chmod 755 data/certbot-webroot
find data/certbot-webroot -type d -exec chmod 755 {} +
chmod 700 data/bootstrap-output data/nginx-ui

if [[ ! "$GATEWAY_NETWORK" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; then
    echo "GATEWAY_NETWORK contains unsupported characters" >&2
    exit 1
fi
if [[ ! "$DOCKER_SUBNET" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}/[0-9]{1,2}$ ]]; then
    echo "DOCKER_SUBNET must be an IPv4 CIDR" >&2
    exit 1
fi

if docker network inspect "$GATEWAY_NETWORK" >/dev/null 2>&1; then
    network_subnets="$(docker network inspect \
        --format '{{range .IPAM.Config}}{{println .Subnet}}{{end}}' \
        "$GATEWAY_NETWORK")"
    if ! grep -Fxq "$DOCKER_SUBNET" <<<"$network_subnets"; then
        echo "Existing Docker network $GATEWAY_NETWORK does not use $DOCKER_SUBNET" >&2
        exit 1
    fi
else
    docker network create \
        --driver bridge \
        --subnet "$DOCKER_SUBNET" \
        "$GATEWAY_NETWORK" >/dev/null
fi

echo "Starting 3x-ui and Nginx UI..."
docker compose up -d xui nginx-ui

echo "Waiting for Nginx UI..."
for _ in {1..60}; do
    status="$(docker inspect --format '{{.State.Health.Status}}' xiaoruru-nginx-ui 2>/dev/null || true)"
    [[ "$status" == "healthy" ]] && break
    sleep 2
done
status="$(docker inspect --format '{{.State.Health.Status}}' xiaoruru-nginx-ui 2>/dev/null || true)"
if [[ "$status" != "healthy" ]]; then
    echo "Nginx UI did not become healthy. Check: docker compose logs nginx-ui" >&2
    exit 1
fi

echo "Waiting for 3x-ui..."
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

echo "Applying the 3x-ui administrator credentials..."
docker compose stop xui
docker compose run --rm --no-deps --entrypoint /app/x-ui xui \
    setting \
    -username "$XUI_ADMIN_USERNAME" \
    -password "$XUI_ADMIN_PASSWORD" \
    -webBasePath "${XUI_WEB_BASE_PATH:-/}" \
    -listenIP "0.0.0.0"
docker compose start xui

./scripts/ensure-certificate.sh

./scripts/render-nginx-sites.sh final

echo "Configuring the panel, subscriptions, inbounds, and primary client..."
docker compose --profile ops run --rm panel-init

echo "Restarting 3x-ui with the generated Xray configuration..."
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
docker compose up -d xui nginx-ui gost
docker compose up -d --no-build --wait --wait-timeout 240 rurublog
docker compose ps

echo
echo "Nginx UI: https://$NGINX_UI_DOMAIN/"
echo "3x-ui:     https://$XUI_DOMAIN/"
echo "CPAMP:     https://$CPAMP_DOMAIN/"
echo "CPA API:   https://$CPA_API_DOMAIN/"
echo "Blog:      https://$BLOG_DOMAIN/"
echo "Blog admin credentials: $project_dir/config/rurublog/application.yml"
echo "Client access data: $project_dir/data/bootstrap-output/access.json"
