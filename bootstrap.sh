#!/usr/bin/env bash
set -Eeuo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
command -v python3 >/dev/null 2>&1 || { echo '请先安装 Python 3.9 或更高版本。' >&2; exit 1; }
cd -- "$task_root"
export PYTHONDONTWRITEBYTECODE=1
exec python3 -m manager "$@"
