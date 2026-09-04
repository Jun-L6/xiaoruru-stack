#!/bin/sh
set -eu
umask 077

if [ "${1:-}" != "--generate-admin-hash" ]; then
    if [ ! -d "${BLOG_DATA_DIR:-/data}" ] || [ ! -w "${BLOG_DATA_DIR:-/data}" ]; then
        printf '%s\n' 'rurublog: 数据目录不存在或当前 UID 不可写，请检查挂载目录与 RURUBLOG_UID/GID。' >&2
        exit 1
    fi
fi

exec java -jar /app/rurublog.jar "$@"
