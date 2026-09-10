# 小茹茹博客

Java 21 / Spring Boot 单体应用，用来记录日常、技术、思考、阅读和任何值得留下的内容。提供 Markdown、HTML 与纯文本文章、分类、标签、媒体、搜索、后台管理、本地备份、AI 自动整理，以及 ChatGPT / DeepSeek 分享对话转文章。

文章使用三个互相独立的维度：一个叶子分类表示主要主题，一个内容形态表示阅读方式，0–5 个标签记录具体人物、地点、技术或关键词。完整规则见 [内容模型与 AI 分类](docs/内容模型与AI分类.md)。

## 部署

由仓库根目录的 `bootstrap.sh` 统一管理，见 [部署说明](../../docs/部署说明.md)。博客只依赖网关，数据库使用 H2，不需要额外数据库或缓存容器。

后台地址：`https://xiaoruru.beer/admin`。登录用户名为 `admin`，登录密钥在部署初始化时生成。

## AI 设置

登录后台，进入“AI 设置”：

- 关闭 AI：默认模式，不发送模型请求。
- 使用 CPA：固定内网根地址 `http://cli-proxy-api:8317`；填写普通 API Key 和模型。
- 外部模型：填写 OpenAI-compatible 根地址、调用路径、API Key、模型、超时以及可选 Temperature。

开发时可以直接编辑 `src/main/resources/application.yml` 中的 `blog.ai.openai`；部署时实际配置位于仓库根目录的 `data/blog/config/application.yml`，也可通过环境变量注入。设置 `enabled: true` 并配置 `base-url`、`api-key`、`model` 后即可使用；后台一旦保存过 AI 设置，将以数据库中的后台设置为准。

保存不联网验证，对后续调用生效。两个接口的配置分别保存；切换外部地址必须重新填写密钥。密钥不回显，使用 AES-GCM 加密存入数据库，加密密钥位于数据目录的 `secrets/ai.key`。

AI 调用通过进程内轻量 HTTP 客户端访问 OpenAI-compatible 接口，分类在后台执行。请求失败会记录错误，可手动重试，不会阻塞文章保存或发布。模型输出语义化分类 slug、内容形态和可选标签；格式错误时最多发起一次 JSON 修复请求。分类体系由站点维护，AI 只能建议新分类，不能自行创建。

## 对话转文章与相似检测

后台“对话转文章”支持 `chatgpt.com/s/...`、`chatgpt.com/share/...` 和 `chat.deepseek.com/share/...` 的公开链接。提取只使用受限域名的 HTTP 请求，不在容器中运行浏览器；提取预览确认后才提交 AI 生成。多轮问答统一重写为一篇 Markdown 草稿，超长内容按消息边界分段整理，成功生成草稿后清理保存的原始对话副本。

相似检测默认完全在本地执行：规范化正文哈希、中文字符 5-gram、整篇 Jaccard 和段落覆盖率共同识别完全重复与轻度改写。发布前自动检测，疑似重复时需要人工确认才能继续发布。

“AI 设置”中还可以选择性开启 OpenAI-compatible Embedding 接口。可以复用当前 AI 根地址与密钥，也可以填写独立外部接口；它只是博客进程内的 HTTP 客户端，不会增加容器、向量数据库或本地模型。未启用或接口暂时不可用时，文本检测照常工作。启用后可手动分批更新历史文章的语义索引。

## 开发

```bash
mvn -B -ntp clean verify
BLOG_ADMIN_SECRET='仅用于本地测试的随机密钥' mvn spring-boot:run
```

开发模式默认监听 8080，数据位于当前目录的 `data/`。不要将开发数据或凭据提交到仓库。

Dockerfile 提供 `runtime`（容器内编译）和 `prebuilt`（复制预编译 JAR）两个构建目标。
