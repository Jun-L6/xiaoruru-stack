#!/bin/sh
set -eu

require_value() {
    name="$1"
    eval "value=\${$name:-}"
    if [ -z "$value" ]; then
        echo "Missing required environment variable: $name" >&2
        exit 1
    fi
}

require_value DOMAIN
require_value GOST_USERNAME
require_value GOST_PASSWORD
require_value GOST_PORT

case "$DOMAIN" in
    *[!A-Za-z0-9.-]*) echo "DOMAIN contains unsupported characters" >&2; exit 1 ;;
esac
case "$GOST_USERNAME" in
    *[!A-Za-z0-9_-]*) echo "GOST_USERNAME contains unsupported characters" >&2; exit 1 ;;
esac
case "$GOST_PASSWORD" in
    *[!A-Za-z0-9_-]*) echo "GOST_PASSWORD must use only A-Z, a-z, 0-9, _ or -" >&2; exit 1 ;;
esac
case "$GOST_PORT" in
    ''|*[!0-9]*) echo "GOST_PORT must be numeric" >&2; exit 1 ;;
esac

cert="/etc/letsencrypt/live/$DOMAIN/fullchain.pem"
key="/etc/letsencrypt/live/$DOMAIN/privkey.pem"

attempt=0
while [ ! -s "$cert" ] || [ ! -s "$key" ]; do
    attempt=$((attempt + 1))
    if [ "$attempt" -gt 120 ]; then
        echo "TLS certificate was not found for $DOMAIN" >&2
        exit 1
    fi
    echo "Waiting for the TLS certificate for $DOMAIN..."
    sleep 5
done

mkdir -p /run/gost
sed \
    -e "s|__DOMAIN__|$DOMAIN|g" \
    -e "s|__GOST_USERNAME__|$GOST_USERNAME|g" \
    -e "s|__GOST_PASSWORD__|$GOST_PASSWORD|g" \
    -e "s|__GOST_PORT__|$GOST_PORT|g" \
    /etc/gost/gost.yml.template > /run/gost/gost.yml
chmod 600 /run/gost/gost.yml

exec /bin/gost -C /run/gost/gost.yml
