# 小茹茹博客 · rurublog

本项目已接入父目录的统一 Docker 部署：主域名为 `xiaoruru.beer`，服务器操作以 [统一博客部署说明](../博客部署说明.md) 为准。下文的独立 Compose 仍供单独开发/部署使用；不要在同一服务器上重复启动两套博客。

面向单人技术写作的轻量博客：一个 Spring Boot JAR、一个本地数据目录，不依赖 MySQL、Redis、Node.js 或对象存储。公开站点无需登录，管理后台使用单一密钥，正文保存在 H2，上传文件保存在本地目录。

中文名为 **小茹茹博客**，英文名为 **rurublog**。Maven 坐标为 `beer.xiaoruru:rurublog`，Java 根包为 `beer.xiaoruru`，构建产物固定为 `target/rurublog.jar`。本地工程文件夹仍保留为 `xiaoruru-blog`，不影响应用命名。

需求基线见 [BLOG_REQUIREMENTS_DESIGN.md](BLOG_REQUIREMENTS_DESIGN.md)。大模型分享链接导入按基线暂缓到 V2。

## 已实现

- 现代技术手记风格，冷白/石墨双主题、青绿色强调、约 780px 正文，包含首页/详情/分类/标签/归档/搜索/关于；
- Markdown、HTML、TXT 安全渲染；Markdown 支持表格、任务列表、脚注、代码高亮、KaTeX 和 Mermaid；
- 单密钥后台，文章状态、自动保存、预览、筛选与批量操作、资源上传、分类、标签、站点设置；
- 两级分类、单叶子分类、多标签，标签重命名与合并；
- Spring AI + OpenAI-compatible 接口，持久化异步队列、硬超时、置信度规则、人工锁、执行日志和失败降级；
- H2 文件数据库、Flyway 迁移、本地资源和一键/定时完整备份；
- RSS、Sitemap、canonical、Open Graph、健康检查和自定义 404；
- CSRF、Session、登录限流、HTML 白名单、文件魔数和路径边界检查。

## 本地启动

需要 JDK 21 和 Maven 3.9+。

最简单的方式：在项目根目录的 `config/application.yml` 中直接设置管理密钥，然后启动：

```yaml
blog:
  admin:
    secret: '替换为你自己的至少12字符密钥'
```

```bash
mvn clean verify
./scripts/start.sh
```

本机已生成私密配置文件，之后直接修改该文件即可；其他机器可参考 `config/application.example.yml`。真实文件已加入忽略规则且不进入 JAR/Docker 镜像，建议保持权限 600。密钥至少 12 个字符，UTF-8 不超过 72 字节；修改后重启生效。应用在内存中转为 BCrypt 用于验证，文件本身仍为明文，请单独妥善保管，不要上传到公开仓库。

如果偏好原来的哈希配置方式，仍可使用：

```bash
mvn clean package
java -jar target/rurublog.jar --generate-admin-hash
```

第二条命令会在终端中隐式读取两次管理密钥并输出 BCrypt 哈希。随后设置环境变量并启动：

```bash
export BLOG_DATA_DIR="$PWD/data"
export BLOG_PUBLIC_URL="http://localhost:8080"
export BLOG_ADMIN_SECRET_HASH='{bcrypt}$2a$10$请替换为上一步输出的完整内容'
export BLOG_AI_ENABLED=false
java -Xms128m -Xmx768m -XX:MaxMetaspaceSize=256m \
  -jar target/rurublog.jar
```

访问：

- 博客：`http://localhost:8080/`
- 后台：`http://localhost:8080/admin/login`
- 健康检查：`http://localhost:8080/actuator/health`

`secret-hash` 与 `secret` 同时设置时，优先使用哈希。两者都为空时管理员账户禁用。若 YAML 密钥修改后仍无法登录，请检查是否还有旧的 `BLOG_ADMIN_SECRET_HASH` 环境变量。

## 配置 OpenAI-compatible 模型

应用已经直接引入 Spring AI。常见配置如下：

```bash
export BLOG_AI_ENABLED=true
export BLOG_AI_BASE_URL="https://api.openai.com"
export BLOG_AI_COMPLETIONS_PATH="/v1/chat/completions"
export BLOG_AI_API_KEY="你的 API Key"
export BLOG_AI_MODEL="gpt-4o-mini"
```

兼容服务的 URL 结构不同；如果服务商给出的地址已经包含 `/v1`，应相应调整 `BLOG_AI_BASE_URL` 和 `BLOG_AI_COMPLETIONS_PATH`，避免路径重复。

保存正文后默认延迟 10 秒提交分类；发布时若当前正文没有有效分类结果，会立即提交任务。AI 只能选择现有、已启用的叶子分类，可以创建标签但不能创建分类。AI 未配置、超时或返回错误不会阻止文章保存与发布。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `BLOG_DATA_DIR` | `./data` | 数据根目录 |
| `BLOG_PUBLIC_URL` | `http://localhost:8080` | 生成 canonical/RSS/Sitemap 的公开地址 |
| `BLOG_ADMIN_SECRET_HASH` | 空 | 带 `{bcrypt}` 前缀的管理密钥哈希 |
| `BLOG_ADMIN_SECRET` | 空 | 本机可选明文密钥；推荐直接填写外部 YAML，哈希配置优先 |
| `BLOG_DB_PASSWORD` | 空 | H2 用户密码，生产环境建议设置 |
| `BLOG_COOKIE_SECURE` | `false` | HTTPS 生产环境必须设为 `true` |
| `BLOG_AI_ENABLED` | `false` | 是否执行 AI 任务 |
| `BLOG_AI_BASE_URL` | OpenAI | OpenAI-compatible 根地址 |
| `BLOG_AI_COMPLETIONS_PATH` | `/v1/chat/completions` | Chat Completions 路径 |
| `BLOG_AI_API_KEY` | `disabled` | 模型 API Key |
| `BLOG_AI_MODEL` | `gpt-4o-mini` | 模型名称 |
| `BLOG_AI_TIMEOUT` | `45s` | 单次模型请求的硬超时 |
| `BLOG_BACKUP_CRON` | `0 0 3 * * *` | Spring cron，每天 03:00 |
| `BLOG_BACKUP_RETENTION` | `7` | 自动备份保留份数；手动备份不自动删 |
| `BLOG_UPLOAD_MAX_SIZE` | `20MB` | 单文件和请求上传上限 |

全部配置定义在 `src/main/resources/application.yml`。

## Docker 部署

提供多阶段 Dockerfile、Compose 配置和启动脚本，服务器只需 Docker 与 Compose。完整说明见 [docs/DOCKER.md](docs/DOCKER.md)。

```bash
./scripts/docker.sh init
./scripts/docker.sh build
# 编辑 config/application.yml：填写 blog.admin.secret
# 编辑 .env：填写数据库密码、BLOG_PUBLIC_URL 等；哈希可留空
./scripts/docker.sh config
./scripts/docker.sh up
```

默认只监听 `127.0.0.1:8080`，数据绑定到本机 `./data`，`./config` 只读挂载到 `/app/config`。Compose 使用非 root 用户、只读根文件系统、1400 MiB 容器内存上限和本地健康检查。生产环境请通过 Nginx 配置 HTTPS，并设 `BLOG_COOKIE_SECURE=true`。部署时必须携带 config 目录，并确保容器 UID 能读取私密 YAML。

2C2G 服务器可采用“本地构建 JAR，服务器只构建运行镜像”的方式，避免在服务器运行 Maven：

```bash
mvn clean verify
./scripts/docker.sh build --prebuilt
```

停止容器使用 `./scripts/docker.sh down`，不会删除数据目录。不使用 Docker 时，先导出环境变量再运行 `./scripts/start.sh`；该脚本以前台方式启动，后台托管用 Systemd。

## 数据目录

```text
data/
├── database/   H2 数据库
├── uploads/    图片和附件
├── backups/    已完成的完整备份包
├── logs/       滚动日志
└── temp/       备份临时文件
```

管理后台“备份”页面可立即生成并下载完整 ZIP。离线恢复流程见 [docs/RESTORE.md](docs/RESTORE.md)。备份仍位于同一块硬盘时不能抵御磁盘故障，建议定期下载到另一台设备。

## 测试与打包

```bash
mvn test
mvn clean package
```

生产部署模板见：

- [deploy/rurublog.service](deploy/rurublog.service)
- [deploy/rurublog.env.example](deploy/rurublog.env.example)
- [deploy/nginx-rurublog.conf](deploy/nginx-rurublog.conf)

模板按 2C2G 主机预留了系统和 Nginx 内存，JVM 上限为 768 MiB。部署前请修改域名、路径、用户、密钥和 AI 配置。

## 升级

1. 在后台生成并下载一次手动备份；
2. 停止旧进程；
3. 替换 JAR，不修改数据目录；
4. 启动应用，Flyway 会自动执行向前迁移；
5. 检查 `/actuator/health`、首页、后台和日志。

不要用旧版 JAR 打开已经由新版迁移过的数据库。需要回退时恢复升级前的完整备份。

V5 迁移仅将旧默认站名和页脚更新为“小茹茹博客 / rurublog”，已自定义的内容保持不变。V1–V4 迁移文件保留原文，避免已有数据库校验失败。`BLOG_*` 配置键、数据库文件名和备份格式不变；新备份的应用标识为 `rurublog`，历史备份仍可按恢复文档使用。

主要开源依赖与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
