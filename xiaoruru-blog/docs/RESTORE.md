# 小茹茹博客（rurublog）——离线恢复

恢复只写入一个新的空数据目录，不在运行中的后台覆盖数据库。保留原数据目录、原镜像/JAR 和原配置，直到新实例验证完毕。只处理本博客生成、来源可信的备份；校验和用于检查损坏，不能证明未知 ZIP 的安全性。

## 1. 停止旧实例并选择工作目录

Docker 部署：在项目根目录执行以下命令，然后继续在该目录操作，使用与配置的 RURUBLOG_UID/GID 相同的非 root 用户。

~~~bash
./scripts/docker.sh down
~~~

Systemd 部署：先由有权限的管理员停止服务，然后切换到运行用户，在可写的数据根目录操作后面的解包步骤。

~~~bash
sudo systemctl stop rurublog
sudo -u rurublog sh
cd /var/lib/rurublog
~~~

不要让两个进程使用同一个 H2 目录。以下步骤必须在同一个终端中顺序执行，以保留临时目录变量。新目标为当前目录下的 data-restored；它必须尚不存在。

## 2. 解包并校验

先把备份放在当前用户可读的位置，替换下面的示例路径：

~~~bash
set -eu
RESTORE_ARCHIVE=/安全路径/blog-backup-YYYYMMDD-HHMMSS-SSS-manual.zip
test -f "$RESTORE_ARCHIVE"
restore_stage=$(mktemp -d /tmp/rurublog-restore.XXXXXX)
case "$restore_stage" in /tmp/rurublog-restore.*) ;; *) exit 1 ;; esac
test -d "$restore_stage"

unzip -l "$RESTORE_ARCHIVE"
unzip "$RESTORE_ARCHIVE" -d "$restore_stage"
(
  cd "$restore_stage"
  sha256sum -c checksums.sha256
  unzip -t database/h2-backup.zip
)
~~~

所有校验必须成功，否则立即停止，不要继续恢复。macOS 可把 sha256sum 替换为 shasum -a 256。

检查 manifest.json：formatVersion 应为 1，application 应为 rurublog。改名前生成的历史备份标识为 paper-blog，属于兼容的旧格式，仍可恢复。应用版本应与计划启动的 JAR/镜像兼容。

## 3. 恢复到新目录

~~~bash
test ! -e ./data-restored
test ! -L ./data-restored
mkdir -m 750 ./data-restored
mkdir -m 750 ./data-restored/database ./data-restored/uploads \
  ./data-restored/backups ./data-restored/logs ./data-restored/temp

unzip "$restore_stage/database/h2-backup.zip" -d ./data-restored/database
if [ -d "$restore_stage/uploads" ]; then
  cp -a "$restore_stage/uploads/." ./data-restored/uploads/
fi
test -f ./data-restored/database/blog.mv.db
~~~

内层 h2-backup.zip 包含真正的 H2 文件；不要把 ZIP 本身当成数据库。没有图片或附件的备份可能不含 uploads 目录，这是正常情况。

## 4. 切换配置并验证

Docker：修改项目 .env：

~~~dotenv
RURUBLOG_DATA_DIR=./data-restored
~~~

保留备份对应的 BLOG_DB_PASSWORD，并确认 RURUBLOG_UID/GID 对新目录有读写权限。回到项目根目录执行：

~~~bash
./scripts/docker.sh config
./scripts/docker.sh up
./scripts/docker.sh status
curl --fail http://127.0.0.1:8080/actuator/health
~~~

Systemd：退出步骤 1 中的运行用户 shell，由管理员把 /etc/rurublog/rurublog.env 中的 BLOG_DATA_DIR 改为 /var/lib/rurublog/data-restored，再启动：

~~~bash
sudo systemctl start rurublog
sudo systemctl status rurublog
curl --fail http://127.0.0.1:8080/actuator/health
~~~

该恢复目录仍位于服务的 ReadWritePaths=/var/lib/rurublog 范围内。如果选择其他位置，必须同时调整 Systemd 写入路径白名单。

检查首页、文章正文、图片、分类、标签、后台和设置。验证成功前不要删除原目录或临时解包目录；成功后可以将它们另行归档。无需复制旧备份包到新 backups 目录，也不需要修改数据库用户名或文件名。

## 5. 常见问题

- **数据库密码错误**：恢复时必须使用创建备份时的原密码；历史空密码仍为空。不能仅改环境变量来重置 H2 密码。
- **数据库被占用**：确认原 JAR、Systemd 或旧容器均已停止。
- **图片 404**：确认 uploads 已恢复，且运行用户可读取。
- **登录失败**：管理密钥及哈希不在数据库备份里；从独立保存的 config/application.yml 或环境变量恢复。两者同时存在时哈希优先。HTTP 验收时注意 Secure Cookie 配置。
- **AI 不运行**：模型 API Key 同样不在备份里，需单独恢复配置。
- **出现中断的备份任务**：备份快照里可能记录了当时运行中的任务，启动后会标记为中断，不影响文章或资源。
- **旧版无法启动**：不要让旧版代码打开已经升级的数据；回退要使用升级前备份配合旧镜像/JAR。
