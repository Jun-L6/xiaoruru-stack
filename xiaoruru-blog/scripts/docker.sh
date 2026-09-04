#!/bin/sh
# rurublog lifecycle helper. Never evaluates .env as shell code or deletes data.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P) || exit 1
test -f "$PROJECT_DIR/compose.yaml" || { printf '%s\n' '找不到 compose.yaml' >&2; exit 1; }
cd "$PROJECT_DIR"
ACTION=${1:-help}
if [ "$#" -gt 0 ]; then shift; fi

usage() {
    printf '%s\n' \
        '小茹茹博客 / rurublog' \
        '用法：./scripts/docker.sh init|build [--prebuilt]|secret|up|down|restart|logs|status|config' \
        'init          创建 .env 和本地 data（不覆盖已有配置）' \
        'build         从源码构建镜像并运行测试；不需要本机 JDK/Maven' \
        'build --prebuilt  使用 target/rurublog.jar 构建轻量运行镜像' \
        'secret        在临时容器中交互生成 BCrypt 管理密钥哈希' \
        'up            启动并等待健康检查；需要先 build' \
        'down          停止并移除本项目容器/网络，保留全部 data' \
        'restart       重启本项目服务，不重新读取配置（配置改动请运行 up）' \
        'logs          跟踪最近 100 行日志（Ctrl-C 仅停止查看）' \
        'status        查看状态' \
        'config        静默验证配置，避免输出密钥'
}

compose() {
    test -f "$PROJECT_DIR/.env" || { printf '%s\n' '请先执行 ./scripts/docker.sh init 并配置 .env' >&2; exit 1; }
    test -d "$PROJECT_DIR/config" || { printf '%s\n' '找不到 config 目录，请保留部署配置目录。' >&2; exit 1; }
    docker compose --project-directory "$PROJECT_DIR" \
        --env-file "$PROJECT_DIR/.env" -f "$PROJECT_DIR/compose.yaml" "$@"
}

case "$ACTION" in
    init)
        test "$#" -eq 0 || { usage; exit 1; }
        if [ -e .env ] || [ -L .env ]; then
            printf '%s\n' '.env 已存在，未覆盖。请检查其中的数据目录与 UID/GID。'
            exit 0
        fi
        test ! -L data || { printf '%s\n' 'data 是符号链接，请手动核实配置；未修改。' >&2; exit 1; }
        if [ -e data ] && [ ! -d data ]; then
            printf '%s\n' 'data 已存在且不是目录，已停止。' >&2; exit 1
        fi
        RUN_UID=$(id -u) || exit 1
        RUN_GID=$(id -g) || exit 1
        if [ "$RUN_UID" -eq 0 ]; then RUN_UID=10001; RUN_GID=10001; fi
        if [ ! -d data ]; then
            mkdir -m 750 data
            if [ "$(id -u)" -eq 0 ]; then chown "$RUN_UID:$RUN_GID" data; fi
        fi
        # noclobber prevents replacing .env even if another process creates it.
        (umask 077; set -C; {
            cat .env.example
            printf '\nRURUBLOG_UID=%s\nRURUBLOG_GID=%s\n' "$RUN_UID" "$RUN_GID"
        } > .env)
        printf '%s\n' '已创建 .env（权限 600）和 data。管理密钥可填写 config/application.yml，或使用 .env 中的密钥哈希；另请配置数据库密码及公开 URL。'
        exit 0
        ;;
    help|-h|--help) usage; exit 0 ;;
    build|secret|up|down|restart|logs|status|config) ;;
    *) usage; exit 1 ;;
esac

command -v docker >/dev/null 2>&1 || { printf '%s\n' '请先安装 Docker Engine / Docker Desktop。' >&2; exit 1; }
docker info >/dev/null 2>&1 || { printf '%s\n' 'Docker 服务不可用，请启动服务后重试。' >&2; exit 1; }
case "$ACTION" in
    build)
        BUILD_TARGET=runtime
        if [ "${1:-}" = "--prebuilt" ]; then
            BUILD_TARGET=prebuilt
            shift
            test -f target/rurublog.jar || { printf '%s\n' '请先运行 mvn clean verify 生成 target/rurublog.jar。' >&2; exit 1; }
        fi
        test "$#" -eq 0 || { usage; exit 1; }
        docker build --target "$BUILD_TARGET" --tag rurublog:local "$PROJECT_DIR"
        ;;
    secret)
        test "$#" -eq 0 || { usage; exit 1; }
        docker run --rm -it --network none --read-only --cap-drop ALL \
            --security-opt no-new-privileges:true rurublog:local --generate-admin-hash
        ;;
    *)
        test "$#" -eq 0 || { usage; exit 1; }
        docker compose version >/dev/null 2>&1 || { printf '%s\n' '请安装 Docker Compose 插件。' >&2; exit 1; }
        case "$ACTION" in
            up) compose up -d --no-build --wait --wait-timeout 180 ;;
            down) compose down --timeout 30 ;;
            restart) compose restart ;;
            logs) compose logs --tail 100 --follow ;;
            status) compose ps ;;
            config) compose config --quiet ;;
        esac
        ;;
esac
