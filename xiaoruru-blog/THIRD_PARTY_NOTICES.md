# 小茹茹博客（rurublog）——第三方组件说明

小茹茹博客（rurublog）在运行时使用以下主要开源组件。版本以 `pom.xml` 和最终构建产物中的依赖清单为准。

| 组件 | 当前版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| Spring Boot | 4.1.1 | Apache License 2.0 | Web、MVC、JPA、安全、监控与应用生命周期 |
| Spring AI | 2.0.0 | Apache License 2.0 | OpenAI-compatible 模型接入 |
| H2 Database | 2.x（由 Spring Boot 管理） | MPL 2.0 / EPL 1.0 | 嵌入式文件数据库 |
| Flyway Community | 由 Spring Boot 管理 | Apache License 2.0 | 数据库版本迁移 |
| flexmark-java | 0.64.8 | BSD 2-Clause | Markdown 解析与扩展语法 |
| OWASP Java HTML Sanitizer | 20240325.1 | BSD 3-Clause | HTML 白名单清洗 |
| Mermaid | 11.17.1 | MIT | 流程图与结构图渲染 |
| highlight.js | 11.11.1 | BSD 3-Clause | 代码语法高亮 |
| KaTeX | 0.16.44 | MIT | 数学公式渲染 |

完整的传递依赖可通过下列命令查看：

```bash
mvn dependency:tree
```

本文件仅用于简要归档。重新分发时应同时遵守各组件随包附带或其官方网站公布的许可证正文及通知要求。
