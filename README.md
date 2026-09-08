# 小茹茹服务栈

一个入口管理网关、VPN、CPA 和博客。所有持久化数据统一放在项目的 `data/` 下。

| 功能 | 常驻容器 | 依赖 |
| --- | --- | --- |
| gateway | nginx-ui（同时运行 Nginx） | 无 |
| vpn | xui，按需启用 gost | gateway |
| cpa | cli-proxy-api、cpa-manager-plus | gateway |
| blog | rurublog | gateway |
| tools | certbot、panel-init 等一次性任务 | 按任务需要 |

博客使用配置中的 `blog` 域名作为唯一规范地址，并自动让对应的 `www` 地址通过 HTTP 301 跳转到规范地址；共享证书会同时包含两者。

全部业务启用时有 5 个常驻容器；启用 GOST 后为 6 个。博客不依赖 CPA。

## 开始使用

在官方 Ubuntu 服务器上以 root 运行时，脚本会自动安装缺失的 Python ≥ 3.9、Docker Engine、Docker Compose、curl、OpenSSL，并启动 Docker。其他系统需要提前准备这些依赖。

```bash
./bootstrap.sh
```

不带参数打开中文菜单。也可以直接执行：

```bash
./bootstrap.sh deploy
./bootstrap.sh init
./bootstrap.sh build blog
./bootstrap.sh reset blog
./bootstrap.sh start vpn
./bootstrap.sh start cpa
./bootstrap.sh start blog
./bootstrap.sh status
./bootstrap.sh credentials
```

`deploy` 是首次部署与后续补齐服务的统一入口：初始化（如需要）、按资源检查 Swap、根据源码指纹构建缺失或过期的博客镜像、启动所有已启用功能、安装证书续期任务并执行诊断。命令可重复执行，不会重置已有数据。首次安装 CPA 时仍会进入官方安装器交互界面。

`reset blog` 用于重新初始化博客。交互运行时必须确认两次；它会永久清空文章、上传、备份、日志、AI 设置与博客配置，但不影响网关、VPN 或 CPA。

`credentials` 在交互终端只读显示各管理页面的入口、部署初始账号和密钥，不重置凭据，也不会在 `status` 中泄露。

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
PYTHONPATH=. python3 tests/smoke_lifecycle.py
```

单元和 Compose 合并测试不启动容器。网关、VPN 和 GOST 冒烟测试会在本机启动隔离的临时容器，只发布回环地址端口（VPN 不发布端口），使用自签名测试证书，完成后移除测试容器及专用网络。VPN 测试会检测公网 REALITY 握手目标；CPA 契约测试需要访问官方仓库，只生成临时配置、禁止执行部署。博客测试需要 JDK 21 和 Maven。

`smoke_lifecycle.py` 使用真实管理器验证首次部署、重复启停、GOST 配置变更、ACME 文件权限、博客独立启动和 Nginx 错误恢复；仅以自签名证书替代公网 CA 申请。该测试读取生成的订阅，并经两条 VPN 隧道向 example.com 发起 HTTPS 请求，需要正常出站网络。测试需要博客镜像，使用独立项目名和网络，不操作正式部署。
