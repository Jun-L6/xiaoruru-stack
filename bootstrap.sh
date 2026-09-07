#!/usr/bin/env bash
set -Eeuo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_python_ready="false"
if command -v python3 >/dev/null 2>&1 \
      && python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1; then
  task_python_ready="true"
fi
if [[ "$task_python_ready" != "true" ]]; then
  task_os_id=""
  task_uid="$(id -u)"
  if [[ -r /etc/os-release ]]; then
    task_os_id="$(. /etc/os-release; printf '%s' "${ID:-}")"
  fi
  if [[ "$(uname -s)" == "Linux" && "$task_uid" =~ ^[0-9]+$ && "$task_uid" == "0" \
        && "$task_os_id" == "ubuntu" && -x /usr/bin/apt-get ]]; then
    echo '未找到 Python 3，正在通过 Ubuntu APT 安装...'
    DEBIAN_FRONTEND=noninteractive /usr/bin/apt-get -o DPkg::Lock::Timeout=120 update
    DEBIAN_FRONTEND=noninteractive /usr/bin/apt-get -o DPkg::Lock::Timeout=120 install -y python3
  else
    echo '请在受支持的 Ubuntu 服务器上以 root 运行，或先安装 Python 3.9 及以上版本。' >&2
    exit 1
  fi
fi
python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1 \
  || { echo 'Python 3.9 及以上版本自动安装后仍不可用。' >&2; exit 1; }
cd -- "$task_root"
export PYTHONDONTWRITEBYTECODE=1
exec python3 -m manager "$@"
