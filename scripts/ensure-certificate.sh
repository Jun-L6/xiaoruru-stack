#!/usr/bin/env bash
set -Eeuo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
set -a
source .env
BLOG_DOMAIN="${BLOG_DOMAIN:-xiaoruru.beer}"
set +a
./scripts/validate.sh
command -v openssl >/dev/null

certificate="data/letsencrypt/live/$CERT_NAME/fullchain.pem"
domains=("$XUI_DOMAIN" "$NGINX_UI_DOMAIN" "$CPAMP_DOMAIN" "$CPA_API_DOMAIN" "$GOST_DOMAIN" "$BLOG_DOMAIN")
had_certificate=0
needs_issue=0
if [[ -s "$certificate" ]]; then
    had_certificate=1
    # Parse public DNS SANs, preserving additional names already on this lineage.
    certificate_text="$(openssl x509 -in "$certificate" -noout -text)"
    old_domains="$(printf '%s\n' "$certificate_text" | grep -oE 'DNS:[A-Za-z0-9*.-]+' | cut -c5- | sort -u)"
    [[ -n "$old_domains" ]] || { echo "Cannot read DNS SANs from $certificate" >&2; exit 1; }
    for domain in "${domains[@]}"; do
        if ! grep -Fxiq "$domain" <<<"$old_domains"; then
            needs_issue=1
        fi
    done
    while IFS= read -r domain; do
        # Wildcards cannot be expanded via HTTP-01; do not change such a lineage.
        [[ "$domain" != *'*'* ]] || { echo "Wildcard certificate requires DNS-01; stopped" >&2; exit 1; }
        domains+=("$domain")
    done <<<"$old_domains"
    if ! openssl x509 -in "$certificate" -noout -checkend 0 >/dev/null; then
        needs_issue=1
    fi
    if [[ "$needs_issue" == "0" ]]; then
        echo "Shared certificate already covers all six configured domains."
        exit 0
    fi
else
    ./scripts/render-nginx-sites.sh bootstrap
fi

# Existing HTTP sites and the default server must retain the ACME webroot location.
# Do not switch a live installation back to bootstrap mode when expanding a SAN.
docker compose exec -T nginx-ui nginx -t
certbot_args=(certonly --webroot --webroot-path /var/www/certbot
    --cert-name "$CERT_NAME" --agree-tos --non-interactive --expand --keep-until-expiring)
unique_domains="$(printf '%s\n' "${domains[@]}" | tr '[:upper:]' '[:lower:]' | sort -u)"
while IFS= read -r domain; do
    certbot_args+=(--domain "$domain")
done <<<"$unique_domains"
if [[ -n "${LE_EMAIL:-}" ]]; then
    certbot_args+=(--email "$LE_EMAIL" --no-eff-email)
else
    certbot_args+=(--register-unsafely-without-email)
fi
if [[ "${LE_STAGING:-0}" == "1" ]]; then
    certbot_args+=(--staging)
fi
docker compose --profile ops run --rm certbot "${certbot_args[@]}"

# Verify the returned certificate before enabling a new HTTPS virtual host.
certificate_text="$(openssl x509 -in "$certificate" -noout -text)"
new_domains="$(printf '%s\n' "$certificate_text" | grep -oE 'DNS:[A-Za-z0-9*.-]+' | cut -c5-)"
while IFS= read -r domain; do
    grep -Fxiq "$domain" <<<"$new_domains" || { echo "Issued certificate is missing $domain" >&2; exit 1; }
done <<<"$unique_domains"
if [[ "$had_certificate" == "1" ]]; then
    docker compose exec -T nginx-ui nginx -t
    docker compose exec -T nginx-ui nginx -s reload
    running_services="$(docker compose ps --status running --services)"
    for service in gost xui; do
        if grep -Fxq "$service" <<<"$running_services"; then
            docker compose restart "$service"
        fi
    done
fi
echo "Shared certificate ready; its existing name and mount paths are unchanged."
