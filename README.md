# xiaoruru Docker 部署包

面向全新 Ubuntu 24.04 服务器的一键部署项目，包含：

- Nginx UI v2.5.10，统一管理 80/443、站点、日志和证书；
- 3x-ui v3.7.0 与自动创建的 VLESS Reality、Hysteria2 入站；
- GOST HTTPS 正向代理；
- CPA Manager Plus 与 CLI Proxy API 的独立子域名反向代理；
- Certbot 共享证书和自动续期；
- 一个供多个 Compose 项目共同使用的外部 Docker 网络。

长期运行的本项目容器为 `nginx-ui`、`xui`、`gost`。`certbot` 和 `panel-init` 只在执行任务时临时运行。

## 部署

先将 `.env.example` 复制为 `.env`，完成域名、邮箱和密码配置，然后执行：

```bash
cd /srv/xiaoruru-stack
chmod 600 .env
chmod +x scripts/*.sh
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
```

Nginx UI 与 3x-ui 初始使用 `.env` 中同一组管理员账号密码。客户端订阅和节点链接生成在 `data/bootstrap-output/access.json`。

完整操作见 [完整部署说明](./完整部署说明.md)。
