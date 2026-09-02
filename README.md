# xiaoruru Docker 部署包

在全新 Ubuntu 24.04 服务器上自动部署：

- 3x-ui v3.7.0 与 Xray；
- VLESS Reality `8443/TCP`；
- Hysteria2 `8443/UDP`；
- Nginx 面板与订阅 HTTPS；
- Let's Encrypt 证书及自动续期；
- GOST HTTPS 正向代理 `9443/TCP`；
- 自动创建主客户端及基础、JSON、Clash/Mihomo 订阅。

所有持久化数据都位于项目的 `data/` 目录，不使用 Docker named volume。

## 部署

```bash
cd /srv/xiaoruru-stack
chmod 600 .env
chmod +x scripts/*.sh
./scripts/validate.sh
./scripts/bootstrap.sh
```

面板地址：

```text
https://www.xiaoruru.beer/
```

部署成功后，私密订阅地址和单节点链接位于：

```text
/srv/xiaoruru-stack/data/bootstrap-output/access.json
```

完整说明见 [完整部署说明](./完整部署说明.md)。
