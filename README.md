# 小茹茹服务栈

一个入口管理网关、VPN、CPA 和博客。所有持久化数据统一放在项目的 `data/` 下。

| 功能 | 常驻容器 | 依赖 |
| --- | --- | --- |
| gateway | nginx-ui（同时运行 Nginx） | 无 |
| vpn | xui，按需启用 gost | gateway |
| cpa | cli-proxy-api、cpa-manager-plus | gateway |
| blog | rurublog | gateway |
| tools | certbot、panel-init 等一次性任务 | 按任务需要 |

全部业务启用时有 5 个常驻容器；启用 GOST 后为 6 个。博客不依赖 CPA。

## 开始使用

服务器需要 Linux、Docker Engine、Docker Compose ≥ 2.24.4、Python ≥ 3.9、curl、OpenSSL。

```bash
./bootstrap.sh
```

不带参数打开中文菜单。也可以直接执行：

```bash
./bootstrap.sh init
./bootstrap.sh build blog
./bootstrap.sh start vpn
./bootstrap.sh start cpa
./bootstrap.sh start blog
./bootstrap.sh status
```

首次部署、DNS、证书、凭据、镜像构建与日常操作见 [部署说明](docs/部署说明.md)。

CPA 的安装、升级和修复由[官方安装器](https://seakee.github.io/CPA-Manager-Plus/docs/deployment/installer.html)交互完成；本项目负责 `compose.override.yaml`、网关与共享网络，不提供 `update cpa`。

## 项目结构

```text
bootstrap.sh           统一交互与命令入口
compose.yaml           网关、VPN、博客、临时工具
manager/               配置校验、生命周期、官方 CPA 安装器适配
config/                默认设置、Nginx 模板、CPA override 模板
apps/blog/             博客应用与 Dockerfile
tests/                 部署管理和本地冒烟测试
docs/部署说明.md        唯一部署手册
data/                  初始化后生成的私有配置和全部持久化数据
```

## 本地验证

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
cd apps/blog
mvn -B -ntp clean verify
cd ../..
python3 tests/smoke_blog.py
PYTHONPATH=. python3 tests/smoke_gateway.py
PYTHONPATH=. python3 tests/smoke_vpn.py
PYTHONPATH=. python3 tests/smoke_gost.py
PYTHONPATH=. python3 tests/check_cpa_installer.py
docker build --target prebuilt -t xiaoruru/rurublog:local ./apps/blog
PYTHONPATH=. python3 tests/smoke_blog_container.py
```

单元和 Compose 合并测试不启动容器。网关、VPN 和 GOST 冒烟测试会在本机启动隔离的临时容器，只发布回环地址端口（VPN 不发布端口），使用自签名测试证书，完成后移除测试容器及专用网络。VPN 测试会检测公网 REALITY 握手目标；CPA 契约测试需要访问官方仓库，只生成临时配置、禁止执行部署。博客测试需要 JDK 21 和 Maven。
