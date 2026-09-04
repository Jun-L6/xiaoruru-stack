# 小茹茹博客（rurublog）——Docker 部署

部署到现有 xiaoruru-stack 时，以 [统一博客部署说明](../../博客部署说明.md) 和父目录 Compose 为准：共用网关、证书、统一数据目录，集成内存上限为 768MiB。本文描述的是可选的独立部署，不要重复启动。

一个容器运行 Spring Boot 单体，H2 数据库、上传资源、日志和备份全部保存在宿主机目录。Java 根包为 `beer.xiaoruru`；镜像名为 `rurublog:local`；JAR 固定为 `rurublog.jar`。

## 1. 前置条件与文件

需要 Docker Engine 或 Docker Desktop，以及支持 `docker compose up --wait` 的现代 Compose 插件。源码构建不需要宿主机安装 Java/Maven；首次构建需要访问 Docker Hub、Ubuntu 软件仓库和 Maven Central。

| 文件 | 用途 |
| --- | --- |
| `Dockerfile` | 多阶段源码构建，也支持已有 JAR 的 `prebuilt` 阶段 |
| `.dockerignore` | 构建上下文白名单，排除密钥、数据和 IDE 配置 |
| `compose.yaml` | 单容器、持久化目录、端口、资源限制及健康检查 |
| `.env.example` | Compose 配置模板，不包含真实密钥 |
| `config/application.example.yml` | 直接填写管理员密钥的 YAML 模板；实际配置使用同目录 application.yml |
| `scripts/docker.sh` | 初始化、构建、启动、停止、查看日志等操作 |
| `deploy/docker/entrypoint.sh` | 数据目录权限检查，以 exec 启动 JVM |
| `deploy/docker/healthcheck.sh` | 本机 `/actuator/health` 检查 |
| `scripts/start.sh` | 可选的非 Docker、前台 JAR 启动方式 |

## 2. 首次部署

在项目根目录执行：

```bash
./scripts/docker.sh init
./scripts/docker.sh build
./scripts/docker.sh secret
```

`init` 只在不存在时创建 `.env`，权限为 600；不会覆盖配置，也不会递归修改已有数据目录。`build` 会运行 Maven 测试再生成运行镜像。`secret` 在不联网、不挂载数据的临时容器中读取两次密钥，输出 BCrypt 哈希，密钥至少 12 个字符。

编辑 `.env`：

```dotenv
BLOG_ADMIN_SECRET_HASH='{bcrypt}$2a$10$这里粘贴命令输出的完整哈希'
BLOG_DB_PASSWORD='新部署时设置一个随机数据库密码'
BLOG_PUBLIC_URL=https://blog.example.com
BLOG_COOKIE_SECURE=true
```

也可以不生成哈希，直接在 `config/application.yml` 中配置 `blog.admin.secret`（至少 12 个字符、UTF-8 不超过 72 字节），并将 `.env` 的 `BLOG_ADMIN_SECRET_HASH` 留空：

```yaml
blog:
  admin:
    secret: '这里填写你自己的管理密钥'
```

本次开发环境已经生成该私密 YAML；新服务器可复制 `config/application.example.yml` 为 `config/application.yml` 后填写。配置文件不进入镜像，由 Compose 从 `./config` 只读挂载至 `/app/config`。部署时保留 config 目录，使用与运行 UID 一致的文件属主及 600 权限。两种配置同时存在时哈希优先；都未设置时管理员登录禁用。修改 YAML 密钥后执行 `./scripts/docker.sh restart` 生效，不能仅运行 up（仅文件内容变化不会自动重建容器）。

哈希必须保留 `{bcrypt}` 前缀，包含 `$` 的值使用单引号，不要把 `$` 改为 `$$`。脚本不会 `source .env`，以免执行配置中的内容。数据库密码在第一次创建 H2 时确定；使用已有数据或恢复备份时必须使用原密码（历史空密码也保持为空），不能仅修改环境变量来更改已有数据库密码。

验证并启动：

```bash
./scripts/docker.sh config
./scripts/docker.sh up
./scripts/docker.sh status
```

启动脚本会等待健康检查，失败时返回非零退出码。访问 `http://localhost:8080/`，后台为 `/admin/login`。如果仅在本机通过 HTTP 验收，先用 `BLOG_PUBLIC_URL=http://localhost:8080`、`BLOG_COOKIE_SECURE=false`；否则 Secure Cookie 无法用于普通 HTTP 登录。对外上线时必须改回真实 HTTPS 地址和安全 Cookie。

默认端口仅绑定 `127.0.0.1:8080`。通过宿主机 [Nginx 模板](../deploy/nginx-rurublog.conf) 配置域名、证书和 HTTPS。确需直接对外暴露时可以修改 `RURUBLOG_BIND_ADDRESS`，但不要将后台密钥通过不可信的明文网络传输。

## 3. 持久化目录和权限

```text
项目目录/
├── .env               私密配置，不放入镜像或版本控制
├── config/            只读挂载到 /app/config，私密 YAML 不进入镜像
└── data/              宿主机绑定到容器 /data
    ├── database/      H2 文件
    ├── uploads/       图片和附件
    ├── backups/       完整 ZIP 备份
    ├── logs/          应用滚动日志
    └── temp/          临时文件
```

`RURUBLOG_DATA_DIR` 是宿主机路径，支持项目相对路径或绝对路径；容器内始终使用 `/data`。指定目录不存在时 Compose 会报错，不会悄悄创建一个 root 属主的数据目录。

容器不以 root 运行。`init` 会把当前用户的 UID/GID 写入 `.env`，与本地 data 的属主一致；root 执行时使用 10001:10001，且只修改新建 data 目录本身的属主。复用旧数据时，应让 `RURUBLOG_UID/GID` 与现有目录的实际属主匹配，不要对未知目录执行递归 chown。

自定义目录需先手动创建，并确保配置的非 root UID/GID 有读写权限。不要同时让两个实例使用同一个 H2 数据目录。把已有单 JAR 部署迁移到 Docker 前，先停止原进程并做完整备份，再挂载原数据目录，不要修改 H2 文件名或数据库用户名。

## 4. 2C2G 服务器的低开销部署

运行时默认 JVM 最大堆 768 MiB、Metaspace 上限 256 MiB；容器内存上限 1400 MiB，CPU 上限 2 核。剩余空间留给系统、Docker 和 Nginx。首次源码构建所需下载、磁盘空间和临时内存不包含在这些运行时限制内。

推荐在开发机生成 JAR：

```bash
mvn clean verify
./scripts/docker.sh build --prebuilt
```

也可以将 `target/rurublog.jar` 和部署文件复制到服务器后，只构建 `prebuilt` 阶段；该阶段不会安装 Maven、编译 Java 或重新下载 Maven 依赖。

开发机和服务器 CPU 架构不同（如 Apple Silicon → x86_64 Linux）时，JAR 不需要改变，但镜像需要匹配服务器架构：

```bash
docker buildx build --platform linux/amd64 --target prebuilt \
  --tag rurublog:local --load .
docker save --output rurublog-image.tar rurublog:local
```

把镜像归档和 Compose/脚本等部署文件传到服务器，在服务器执行：

```bash
docker load --input rurublog-image.tar
./scripts/docker.sh init
# 编辑 .env，确认服务器实际的数据路径及 UID/GID
./scripts/docker.sh up
```

ARM64 服务器则用 `--platform linux/arm64`。默认构建使用当前 Docker 主机架构，不自动假定服务器为 x86_64。

## 5. AI 配置

在 `.env` 中设置：

```dotenv
BLOG_AI_ENABLED=true
BLOG_AI_BASE_URL=https://your-provider.example.com
BLOG_AI_COMPLETIONS_PATH=/v1/chat/completions
BLOG_AI_API_KEY='你的 API Key'
BLOG_AI_MODEL=你的模型名称
BLOG_AI_TIMEOUT=45s
```

如果 BASE_URL 已含 `/v1`，COMPLETIONS_PATH 使用 `/chat/completions`。修改后执行 `./scripts/docker.sh up` 重新创建受配置变更影响的容器。单纯 `restart` 不会重新注入环境变量。

默认关闭 AI；未设置 API Key 时 Compose 注入占位值使应用可以正常启动，不会主动执行分类。不要把真实 API Key 写入 Dockerfile、镜像构建参数或公共版本控制。`.env` 不在文章备份中，需另行安全保管。

## 6. 常用维护命令

```bash
./scripts/docker.sh logs
./scripts/docker.sh status
./scripts/docker.sh restart
./scripts/docker.sh down
```

`down` 只停止并移除本 Compose 项目的容器和网络，不删除本地 data。日志命令按 Ctrl-C 仅退出日志查看。容器设置了失败/重启恢复策略、健康检查、只读根文件系统和 `/tmp` 临时挂载；业务文件只能写入 `/data`。

`config` 只检查配置是否有效，不打印密钥。不要将完整的 `docker compose config` 或 `docker inspect` 输出发送到公开渠道，它们可能含环境变量中的密钥。

## 7. 升级和恢复

1. 在后台创建并下载完整备份，另外安全保存 `.env` 与 `config/application.yml`（后台备份不包含密钥配置）。
2. 保留旧镜像标签或导出归档；不要在回滚验证前清理它。
3. 构建新镜像，或导入新镜像归档。
4. 执行 `./scripts/docker.sh up`，新容器继续使用原 data。
5. 检查健康状态、首页、后台和日志。Flyway 会自动执行向前迁移。

恢复流程见 [RESTORE.md](RESTORE.md)。不要让旧版代码直接打开已被新版迁移过的数据库；回退时用升级前的完整备份恢复到新目录，再配合旧镜像启动。恢复 H2 时必须使用备份对应的原数据库密码。

## 8. 排查

- **权限错误**：核对 data 实际属主、UID/GID 和挂载路径。不要通过容器 root 运行绕过权限问题。
- **后台登录循环**：本地 HTTP 时不能使用 Secure Cookie；反向代理场景确认 `X-Forwarded-Proto` 和 HTTPS 设置。
- **H2 数据库被锁**：确认只有一个进程使用该目录，原 JAR/Systemd 进程已经停止。
- **健康检查失败**：查看 `logs`，检查 DB 密码、目录权限和资源上限；不要直接删除数据库。
- **构建下载失败**：检查 Docker Hub、Maven Central 和 Ubuntu 软件源连接；也可在能联网的开发机构建并导入镜像。
- **新机器重启后没有服务**：确认 Docker 服务设置为开机启动，且先前使用 `up` 创建的容器尚在；`down` 后需要再次 `up`。

## 参考

Compose 的配置文件、变量插值和优先级依据官方文档：[服务配置](https://docs.docker.com/reference/compose-file/services/)、[变量插值与单引号](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)、[环境变量优先级](https://docs.docker.com/compose/how-tos/environment-variables/envvars-precedence/)。
