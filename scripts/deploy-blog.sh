#!/usr/bin/env bash
# Add/update the blog on an existing stack without running panel initialization.
set -Eeuo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--build" ) ]]; then
    echo "Usage: $0 [--build] (default: use a previously loaded blog image)" >&2
    exit 1
fi
set -a
source .env
BLOG_DOMAIN="${BLOG_DOMAIN:-xiaoruru.beer}"
set +a
./scripts/validate.sh
docker network inspect "$GATEWAY_NETWORK" >/dev/null
docker compose exec -T nginx-ui nginx -t
python3 scripts/init-blog.py
if [[ "${1:-}" == "--build" ]]; then
    docker compose build rurublog
fi
docker compose up -d --no-build --wait --wait-timeout 240 rurublog
./scripts/ensure-certificate.sh
./scripts/render-nginx-sites.sh blog
echo "Blog: https://$BLOG_DOMAIN/"
echo "Admin: https://$BLOG_DOMAIN/admin/login"
echo "Private credentials: $project_dir/config/rurublog/application.yml"
