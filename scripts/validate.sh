#!/usr/bin/env bash
set -Eeuo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if [[ ! -f .env ]]; then
    echo "Missing .env; copy .env.example and replace all placeholders." >&2
    exit 1
fi

if grep -q 'change_me' .env; then
    echo ".env still contains change_me placeholders." >&2
    exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

required=(
    XUI_DOMAIN NGINX_UI_DOMAIN CPAMP_DOMAIN CPA_API_DOMAIN GOST_DOMAIN
    CERT_NAME GATEWAY_NETWORK DOCKER_SUBNET
    XUI_IMAGE NGINX_UI_IMAGE GOST_IMAGE CERTBOT_IMAGE PANEL_INIT_IMAGE
    HTTP_PORT HTTPS_PORT XRAY_PORT GOST_PORT XUI_PANEL_PORT XUI_SUB_PORT
    XUI_ADMIN_USERNAME XUI_ADMIN_PASSWORD GOST_USERNAME GOST_PASSWORD
)
for name in "${required[@]}"; do
    if [[ -z "${!name:-}" ]]; then
        echo "Missing required variable in .env: $name" >&2
        exit 1
    fi
done

domains=("$XUI_DOMAIN" "$NGINX_UI_DOMAIN" "$CPAMP_DOMAIN" "$CPA_API_DOMAIN" "$GOST_DOMAIN")
for domain in "${domains[@]}"; do
    if [[ ! "$domain" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]; then
        echo "Invalid DNS name in .env: $domain" >&2
        exit 1
    fi
done
duplicate_domain="$(printf '%s\n' "${domains[@]}" | sort | uniq -d | head -n 1)"
if [[ -n "$duplicate_domain" ]]; then
    echo "Every public service needs a unique domain; duplicate: $duplicate_domain" >&2
    exit 1
fi

if [[ -n "${LE_EMAIL:-}" && ! "$LE_EMAIL" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
    echo "LE_EMAIL is not a valid email address" >&2
    exit 1
fi
if [[ ! "$CERT_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    echo "CERT_NAME contains unsupported characters" >&2
    exit 1
fi
if [[ ! "$GATEWAY_NETWORK" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; then
    echo "GATEWAY_NETWORK contains unsupported characters" >&2
    exit 1
fi
if [[ ! "$DOCKER_SUBNET" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}/[0-9]{1,2}$ ]]; then
    echo "DOCKER_SUBNET must be an IPv4 CIDR" >&2
    exit 1
fi

for name in HTTP_PORT HTTPS_PORT XRAY_PORT GOST_PORT XUI_PANEL_PORT XUI_SUB_PORT; do
    value="${!name}"
    if [[ ! "$value" =~ ^[0-9]+$ ]] || ((value < 1 || value > 65535)); then
        echo "$name must be a port from 1 to 65535" >&2
        exit 1
    fi
done
if [[ "$HTTP_PORT" == "$HTTPS_PORT" || "$HTTP_PORT" == "$XRAY_PORT" || "$HTTP_PORT" == "$GOST_PORT" ||
      "$HTTPS_PORT" == "$XRAY_PORT" || "$HTTPS_PORT" == "$GOST_PORT" || "$XRAY_PORT" == "$GOST_PORT" ]]; then
    echo "HTTP_PORT, HTTPS_PORT, XRAY_PORT, and GOST_PORT must be distinct" >&2
    exit 1
fi

for script in scripts/*.sh; do
    bash -n "$script"
done
if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import ast; ast.parse(open("scripts/init-panel.py", encoding="utf-8").read())'
fi

docker compose config --quiet

echo "Static validation passed. No containers were started."
