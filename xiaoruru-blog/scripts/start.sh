#!/bin/sh
# Foreground JAR launcher; use systemd or Docker for daemon management.
set -eu
umask 077
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P) || exit 1
cd "$PROJECT_DIR"
command -v java >/dev/null 2>&1 || { printf '%s\n' '请安装 JDK 21。' >&2; exit 1; }
test -f target/rurublog.jar || { printf '%s\n' '请先运行 mvn clean verify。' >&2; exit 1; }
if [ "${1:-}" = "--generate-admin-hash" ]; then
    exec java -jar target/rurublog.jar "$@"
fi
if [ -z "${BLOG_ADMIN_SECRET_HASH:-}" ] && [ -z "${BLOG_ADMIN_SECRET:-}" ] && [ ! -f config/application.yml ] && [ ! -f config/application.yaml ]; then
    printf '%s\n' '请在 config/application.yml 配置 blog.admin.secret，或设置 BLOG_ADMIN_SECRET_HASH。' >&2
    exit 1
fi
export BLOG_DATA_DIR="${BLOG_DATA_DIR:-$PROJECT_DIR/data}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Xms128m -Xmx768m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC}"
exec java -jar target/rurublog.jar "$@"
