# 小茹茹博客

Java 21 / Spring Boot 应用，提供 Markdown 与 HTML 文章、分类、标签、媒体、全文检索、后台管理、备份和可选的 AI 分类。

## 部署

由仓库根目录的 `bootstrap.sh` 统一管理，见 [部署说明](../../docs/部署说明.md)。博客只依赖网关，数据库使用 H2，不需要额外数据库或缓存容器。

后台地址：`https://xiaoruru.beer/admin`。登录用户名为 `admin`，登录密钥在部署初始化时生成。

## AI 设置

登录后台，进入“AI 设置”：

- 关闭 AI：默认模式，不发送模型请求。
- 使用 CPA：固定内网根地址 `http://cli-proxy-api:8317`；填写普通 API Key 和模型。
- 外部模型：填写 OpenAI-compatible 根地址、调用路径、API Key、模型、超时以及可选 Temperature。

保存不联网验证，对后续调用生效。两个接口的配置分别保存；切换外部地址必须重新填写密钥。密钥不回显，使用 AES-GCM 加密存入数据库，加密密钥位于数据目录的 `secrets/ai.key`。

AI 分类在后台执行。请求失败会记录错误，可手动重试，不会阻塞文章保存。模型分类输出格式错误时最多发起一次 JSON 修复请求；不做服务可用性监控或接口自动切换。

## 开发

```bash
mvn -B -ntp clean verify
BLOG_ADMIN_SECRET='仅用于本地测试的随机密钥' mvn spring-boot:run
```

开发模式默认监听 8080，数据位于当前目录的 `data/`。不要将开发数据或凭据提交到仓库。

Dockerfile 提供 `runtime`（容器内编译）和 `prebuilt`（复制预编译 JAR）两个构建目标。
