# xiaoruru Docker 部署包

面向全新 Ubuntu 24.04 服务器的一键部署项目，包含：

- Nginx UI v2.5.10，统一管理 80/443、站点、日志和证书；
- 3x-ui v3.7.0 与自动创建的 VLESS Reality、Hysteria2 入站；
- GOST HTTPS 正向代理；
- 小茹茹博客（Java 21 + H2），主域名 `xiaoruru.beer`；
- CPA Manager Plus 与 CLI Proxy API 的独立子域名反向代理；
- Certbot 共享证书和自动续期；
- 一个供多个 Compose 项目共同使用的外部 Docker 网络。

长期运行的本项目容器为 `nginx-ui`、`xui`、`gost`、`rurublog`。加上独立 CPAMP 项目的两个容器，共六个常驻容器。`certbot` 和 `panel-init` 只在执行任务时临时运行。

六个域名共用 Certbot 管理的 `xiaoruru-services` SAN 证书，博客不会另行申请一张。Nginx UI 管理站点配置，不重复接管这张证书的签发/续期。

已有服务器新增博客，直接看 [博客部署说明](./博客部署说明.md)，不必重跑 VPN 初始化。

## 部署

先按 [完整部署说明](./完整部署说明.md) 在本机生成博客 JAR、上传项目；在服务器将 `.env.example` 复制为 `.env`，完成域名、邮箱和密码配置，然后执行：

```bash
cd /srv/xiaoruru-stack
chmod 600 .env
chmod +x scripts/*.sh
docker build --target prebuilt -t xiaoruru/rurublog:local ./xiaoruru-blog
./scripts/validate.sh
./scripts/bootstrap.sh
```

默认入口：

```text
Nginx UI: https://nginx-ui.xiaoruru.beer/
3x-ui:     https://3x-ui.xiaoruru.beer/
CPAMP:     https://cpa-mp.xiaoruru.beer/
CPA API:   https://cli-proxy-api.xiaoruru.beer/
GOST:      https://www.xiaoruru.beer:9443
博客:      https://xiaoruru.beer/
博客后台:  https://xiaoruru.beer/admin/login
```

Nginx UI 与 3x-ui 初始使用 `.env` 中同一组管理员账号密码。客户端订阅和节点链接生成在 `data/bootstrap-output/access.json`。

博客管理员密钥和 H2 密码由初始化脚本随机生成，位于私密文件 `config/rurublog/application.yml`。博客全部运行数据位于 `data/rurublog/`，内部 `8080` 不发布到公网。默认容器上限 768MiB、JVM 堆上限 320MiB、CPU 上限 1 核，AI 默认关闭。

完整操作见 [完整部署说明](./完整部署说明.md)。
