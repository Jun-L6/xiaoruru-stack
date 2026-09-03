#!/usr/bin/env bash
set -Eeuo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

mode="${1:-}"
if [[ "$mode" != "bootstrap" && "$mode" != "final" ]]; then
    echo "Usage: $0 bootstrap|final" >&2
    exit 1
fi

if [[ ! -f .env ]]; then
    echo "Missing $project_dir/.env" >&2
    exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

required=(XUI_DOMAIN NGINX_UI_DOMAIN CPAMP_DOMAIN CPA_API_DOMAIN GOST_DOMAIN CERT_NAME XUI_PANEL_PORT XUI_SUB_PORT)
for name in "${required[@]}"; do
    if [[ -z "${!name:-}" ]]; then
        echo "Missing required variable in .env: $name" >&2
        exit 1
    fi
done

for name in XUI_DOMAIN NGINX_UI_DOMAIN CPAMP_DOMAIN CPA_API_DOMAIN GOST_DOMAIN; do
    value="${!name}"
    if [[ ! "$value" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]; then
        echo "$name is not a valid DNS name" >&2
        exit 1
    fi
done
if [[ ! "$CERT_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    echo "CERT_NAME contains unsupported characters" >&2
    exit 1
fi
for name in XUI_PANEL_PORT XUI_SUB_PORT; do
    value="${!name}"
    if [[ ! "$value" =~ ^[0-9]+$ ]] || ((value < 1 || value > 65535)); then
        echo "$name must be a TCP port from 1 to 65535" >&2
        exit 1
    fi
done

nginx_dir="$project_dir/data/nginx"
available_dir="$nginx_dir/sites-available"
enabled_dir="$nginx_dir/sites-enabled"
conf_dir="$nginx_dir/conf.d"
streams_available_dir="$nginx_dir/streams-available"
streams_enabled_dir="$nginx_dir/streams-enabled"

if [[ ! -s "$nginx_dir/nginx.conf" ]]; then
    echo "Nginx UI has not initialized $nginx_dir yet" >&2
    exit 1
fi

mkdir -p \
    "$available_dir" \
    "$enabled_dir" \
    "$conf_dir" \
    "$streams_available_dir" \
    "$streams_enabled_dir"
install -m 0644 config/nginx-ui/common.conf "$conf_dir/nginx-ui.conf"

render_template() {
    local source_file="$1"
    local target_file="$2"
    local temporary_file="${target_file}.tmp.$$"

    sed \
        -e "s|__XUI_DOMAIN__|$XUI_DOMAIN|g" \
        -e "s|__NGINX_UI_DOMAIN__|$NGINX_UI_DOMAIN|g" \
        -e "s|__CPAMP_DOMAIN__|$CPAMP_DOMAIN|g" \
        -e "s|__CPA_API_DOMAIN__|$CPA_API_DOMAIN|g" \
        -e "s|__GOST_DOMAIN__|$GOST_DOMAIN|g" \
        -e "s|__CERT_NAME__|$CERT_NAME|g" \
        -e "s|__XUI_PANEL_PORT__|$XUI_PANEL_PORT|g" \
        -e "s|__XUI_SUB_PORT__|$XUI_SUB_PORT|g" \
        "$source_file" > "$temporary_file"
    chmod 0644 "$temporary_file"
    mv -f "$temporary_file" "$target_file"
}

managed_sites=(
    00-bootstrap.conf
    00-default.conf
    10-nginx-ui.conf
    20-3x-ui.conf
    30-cpa-manager-plus.conf
    40-cli-proxy-api.conf
)
for site in "${managed_sites[@]}"; do
    rm -f "$enabled_dir/$site"
done

if [[ "$mode" == "bootstrap" ]]; then
    render_template \
        config/nginx-ui/sites/00-bootstrap.conf.template \
        "$available_dir/00-bootstrap.conf"
    ln -s ../sites-available/00-bootstrap.conf "$enabled_dir/00-bootstrap.conf"
else
    rm -f "$available_dir/00-bootstrap.conf"
    for site in 00-default 10-nginx-ui 20-3x-ui 30-cpa-manager-plus 40-cli-proxy-api; do
        if [[ ! -e "$available_dir/$site.conf" ]]; then
            render_template \
                "config/nginx-ui/sites/$site.conf.template" \
                "$available_dir/$site.conf"
        fi
        ln -s "../sites-available/$site.conf" "$enabled_dir/$site.conf"
    done
fi

docker compose exec -T nginx-ui nginx -t
docker compose exec -T nginx-ui nginx -s reload

echo "Nginx $mode configuration installed and reloaded."
