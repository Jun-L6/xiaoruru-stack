"""Docker lifecycle, gateway sites, certificate jobs and official CPA adapter."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request

from .storage import CERT_NAME, MODULES, StackError, Store, directory, safe_path, write

CPA_INSTALLER = "https://raw.githubusercontent.com/seakee/CPA-Manager-Plus/main/bin/install-cpamp.sh"
SERVICES = {"gateway": ["nginx-ui"], "vpn": ["xui", "gost"],
            "blog": ["rurublog"], "cpa": ["cli-proxy-api", "cpa-manager-plus"]}
SITES = {"gateway": ["10-nginx-ui"], "vpn": ["20-3x-ui"],
         "cpa": ["30-cpa-manager-plus", "40-cli-proxy-api"], "blog": ["50-rurublog"]}


def clean_env():
    # Neither a user's Compose variables nor another CPA install may redirect this stack.
    return {key: value for key, value in os.environ.items()
            if not key.startswith(("COMPOSE_", "CPAMP_"))}


def run(args, cwd=None, capture=False, check=True, timeout=900, env=None):
    try:
        result = subprocess.run(args, cwd=cwd, env=env or clean_env(),
                                text=True, capture_output=capture, timeout=timeout)
    except FileNotFoundError:
        raise StackError(f"未找到命令：{args[0]}")
    except subprocess.TimeoutExpired:
        raise StackError(f"{args[0]} 操作超时；已结束本次等待，请检查状态后重试。")
    if check and result.returncode:
        # Captured output may contain upstream configuration or private values.
        raise StackError(f"{args[0]} 执行失败（退出码 {result.returncode}）。请使用 logs / doctor 检查。")
    return result


class Runtime:
    def __init__(self, store=None):
        self.store = store or Store()
        self.root = self.store.root
        self.config = self.store.load()
        self.cpa_dir = self.store.data / "cpa"

    def compose(self, *args, cpa=False, **kwargs):
        if cpa:
            base = ["docker", "compose", "-p", "xiaoruru-cpa"]
            cwd = self.cpa_dir
        else:
            base = ["docker", "compose", "--env-file", str(self.store.system / "compose.env"),
                    "-f", str(self.root / "compose.yaml"), "-p", "xiaoruru",
                    "--profile", "vpn", "--profile", "proxy", "--profile", "blog", "--profile", "tools"]
            cwd = self.root
        if kwargs.get("capture") and kwargs.get("check", True):
            result = run(base + list(args), cwd=cwd, **{**kwargs, "check": False})
            if result.returncode:
                error_file = self.store.system / "last-error.log"
                write(error_file, result.stdout + "\n" + result.stderr)
                raise StackError(f"Docker Compose 操作失败。私密诊断输出：{error_file}")
            return result
        return run(base + list(args), cwd=cwd, **kwargs)

    def preflight(self, prepare=True):
        for executable in ("docker", "openssl", "bash", "curl"):
            if not shutil.which(executable):
                raise StackError(f"请安装 {executable}。")
        version = run(["docker", "compose", "version", "--short"], capture=True).stdout
        digits = re.search(r"(\d+)\.(\d+)\.(\d+)", version)
        if not digits or tuple(map(int, digits.groups())) < (2, 24, 4):
            raise StackError("需要 Docker Compose 2.24.4 或更高版本，以支持 CPA 的 !reset 覆盖。")
        run(["docker", "info", "--format", "{{.ServerVersion}}"], capture=True)
        if prepare:
            self.store.prepare(self.config)
        self.compose("config", "--quiet", capture=True)

    def network(self):
        name = self.config["network"]["name"]
        subnet = self.config["network"]["subnet"]
        result = run(["docker", "network", "inspect", name], capture=True, check=False)
        if result.returncode:
            run(["docker", "network", "create", "--driver", "bridge", "--subnet", subnet, name],
                capture=True)
        else:
            network = json.loads(result.stdout)[0]
            subnets = {item.get("Subnet") for item in network["IPAM"]["Config"]}
            if network["Driver"] != "bridge" or subnet not in subnets:
                raise StackError(f"已有网络 {name} 与配置不一致，请先解决子网冲突。")

    def containers(self):
        result = run(["docker", "ps", "-a", "--format", "{{json .}}"],
                     capture=True, check=False, timeout=30)
        if result.returncode:
            raise StackError("无法连接 Docker，请确认 Docker Engine 已启动且当前用户有权限。")
        rows = []
        for line in result.stdout.splitlines():
            row = json.loads(line)
            labels = dict(item.split("=", 1) for item in row.get("Labels", "").split(",") if "=" in item)
            project = labels.get("com.docker.compose.project")
            if project not in ("xiaoruru", "xiaoruru-cpa") or labels.get("com.docker.compose.oneoff") == "True":
                continue
            row["service"] = labels.get("com.docker.compose.service")
            row["project"] = project
            rows.append(row)
        return rows

    def running(self, module):
        project = "xiaoruru-cpa" if module == "cpa" else "xiaoruru"
        return any(row["project"] == project and row["service"] in SERVICES[module]
                   and row.get("State") == "running" for row in self.containers())

    def status(self):
        rows = self.containers()
        print("\n功能状态（运行状态不代表 CPA 已配置模型）")
        for module in ("gateway", *MODULES):
            expected = SERVICES[module][:]
            if module == "vpn" and not self.config["gost"]:
                expected.remove("gost")
            project = "xiaoruru-cpa" if module == "cpa" else "xiaoruru"
            for service in expected:
                match = next((r for r in rows if r["service"] == service and r["project"] == project), None)
                state = match["Status"] if match else "未创建"
                print(f"  {module:8} {service:20} {state}")
        print("  tools    按需运行，任务结束即移除临时容器")
        print(f"  GOST     {'启用' if self.config['gost'] else '关闭'}")
        self.links()

    def links(self, module=None):
        mapping = {"gateway": ["gateway"], "vpn": ["vpn"], "cpa": ["cpamp", "cpa_api"], "blog": ["blog"]}
        for selected in ([module] if module else ["gateway", *self.config["enabled"]]):
            for key in mapping[selected]:
                print(f"  {key}: https://{self.config['domains'][key]}/")

    def up(self, services, cpa=False, build=False):
        args = ["up", "-d", "--wait", "--wait-timeout", "240"]
        if not cpa:
            args += ["--build" if build else "--no-build"]
        self.compose(*args, *services, cpa=cpa)

    def seed_gateway(self):
        nginx = self.store.data / "gateway/nginx"
        state = self.store.state()
        if state.get("gateway_seeded"):
            if not (nginx / "nginx.conf").is_file():
                raise StackError("网关已初始化，但 nginx.conf 缺失。")
            return
        if any(nginx.iterdir()) and not state.get("gateway_seeding"):
            raise StackError("Nginx 目录非空却没有 nginx.conf，请检查完整性。")
        self.store.mark("gateway_seeding")
        # Copy the image's mime.types and supporting files before supplying our own vhosts.
        self.compose("run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "nginx-ui",
                     "-c", "cp -a /usr/local/etc/nginx/. /etc/nginx/")
        write(nginx / "nginx.conf", (self.root / "config/nginx-ui/nginx.conf").read_text(), 0o644)
        write(nginx / "conf.d/nginx-ui.conf", (self.root / "config/nginx-ui/common.conf").read_text(), 0o644)
        for folder in ("sites-available", "sites-enabled", "streams-available", "streams-enabled"):
            directory(nginx / folder, 0o755)
        default = (self.root / "config/nginx-ui/sites/00-default.conf.template").read_text()
        write(nginx / "sites-available/00-default.conf", default, 0o644)
        self.site_link("00-default", True)
        self.store.mark("gateway_seeded")

    def site_link(self, name, enabled):
        nginx = self.store.data / "gateway/nginx"
        link = nginx / "sites-enabled" / (name + ".conf")
        relative = "../sites-available/" + name + ".conf"
        if link.is_symlink():
            allowed = (relative, "/etc/nginx/sites-available/" + name + ".conf")
            if os.readlink(link) not in allowed:
                raise StackError(f"站点链接不是项目创建的目标：{link}")
            if not enabled:
                link.unlink()
        elif link.exists():
            raise StackError(f"站点启用位置存在普通文件，不自动覆盖：{link}")
        elif enabled:
            link.symlink_to(relative)

    def render_site(self, name):
        domains = self.config["domains"]
        values = {"NGINX_UI_DOMAIN": domains["gateway"], "XUI_DOMAIN": domains["vpn"],
                  "CPAMP_DOMAIN": domains["cpamp"], "CPA_API_DOMAIN": domains["cpa_api"],
                  "BLOG_DOMAIN": domains["blog"], "CERT_NAME": CERT_NAME,
                  "XUI_PANEL_PORT": "2053", "XUI_SUB_PORT": "2096"}
        content = (self.root / f"config/nginx-ui/sites/{name}.conf.template").read_text()
        for key, value in values.items():
            content = content.replace(f"__{key}__", value)
        if re.search(r"__[A-Z_]+__", content):
            raise StackError("Nginx 模板包含未填充的变量。")
        return content

    def sites(self, module, enabled=True, replace=False):
        nginx = self.store.data / "gateway/nginx"
        for name in SITES[module]:
            path = nginx / "sites-available" / (name + ".conf")
            if enabled:
                write(path, self.render_site(name), 0o644, overwrite=replace)
            self.site_link(name, enabled)
        self.reload()

    def reload(self):
        self.compose("exec", "-T", "nginx-ui", "nginx", "-t", capture=True)
        self.compose("exec", "-T", "nginx-ui", "nginx", "-s", "reload", capture=True)

    def certificate_domains(self):
        certificate = self.store.data / f"gateway/certificates/live/{CERT_NAME}/fullchain.pem"
        if not certificate.exists():
            return set(), False
        result = run(["openssl", "x509", "-in", str(certificate), "-noout", "-ext", "subjectAltName"],
                     capture=True)
        names = set(re.findall(r"DNS:([a-zA-Z0-9.*-]+)", result.stdout))
        valid = run(["openssl", "x509", "-in", str(certificate), "-noout", "-checkend", "2592000"],
                    capture=True, check=False).returncode == 0
        return names, valid

    def certificate(self, renew=False):
        existing, valid = self.certificate_domains()
        wanted = set(self.store.domains(self.config)) | existing
        if not renew and valid and wanted <= existing:
            return
        print("准备证书：" + ", ".join(sorted(wanted)))
        print("这些域名的 DNS 必须指向本机，公网 TCP 80 必须可访问。")
        certificate = self.store.data / f"gateway/certificates/live/{CERT_NAME}/fullchain.pem"
        before = certificate.read_bytes() if certificate.exists() else b""
        args = ["run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "certbot",
                "-c", 'umask 022; exec certbot "$@"', "certbot", "certonly", "--webroot",
                "-w", "/var/www/certbot", "--cert-name", CERT_NAME,
                "--email", self.config["email"], "--agree-tos", "--non-interactive",
                "--keep-until-expiring", "--expand"]
        for domain in sorted(wanted):
            args += ["-d", domain]
        self.compose(*args)
        if certificate.read_bytes() == before:
            print("证书尚未到续期时间；没有重启 VPN 或代理。")
            return
        self.reload()
        for service in ("xui", "gost"):
            if any(row["service"] == service and row.get("State") == "running" for row in self.containers()):
                self.compose("restart", service)

    def gateway(self):
        self.network()
        self.seed_gateway()
        self.up(["nginx-ui"])
        self.reload()  # Backend health alone does not prove Nginx config is valid.
        self.certificate()
        self.sites("gateway")

    def cpa_installed(self):
        return (self.cpa_dir / "compose.yaml").is_file() and (self.cpa_dir / ".env").is_file()

    def cpa_override(self):
        content = (self.root / "config/cpa.override.yaml").read_text().replace(
            "__GATEWAY_NETWORK__", self.config["network"]["name"])
        write(self.cpa_dir / "compose.override.yaml", content, 0o600)
        if self.cpa_installed():
            self.validate_cpa()

    def validate_cpa(self):
        result = self.compose("config", "--format", "json", cpa=True, capture=True)
        merged = json.loads(result.stdout)
        services = merged.get("services", {})
        if set(services) != set(SERVICES["cpa"]):
            raise StackError("官方 Compose 服务结构发生变化，请检查适配；没有自动启动其他服务。")
        for name, service in services.items():
            if service.get("ports") or service.get("network_mode") or "gateway" not in service.get("networks", {}):
                raise StackError(f"CPA 网络隔离检查失败：{name}")
            for volume in service.get("volumes", []):
                if volume.get("type") != "bind":
                    raise StackError(f"CPA 仍存在非目录卷：{name}")
                source = Path(volume["source"])
                safe_path(source)
                if not source.resolve().is_relative_to(self.cpa_dir.resolve()):
                    raise StackError(f"CPA 挂载位于统一数据目录之外：{name}")
        manager_data = [v for v in services["cpa-manager-plus"].get("volumes", []) if v["target"] == "/data"]
        if len(manager_data) != 1 or Path(manager_data[0]["source"]).resolve() != (self.cpa_dir / "manager").resolve():
            raise StackError("CPA Manager 的 /data 绑定目录不正确。")
        network = merged.get("networks", {}).get("gateway", {})
        if not network.get("external") or network.get("name") != self.config["network"]["name"]:
            raise StackError("CPA 共享网络设置不正确。")

    def official_installer(self):
        if not sys.stdin.isatty():
            raise StackError("CPA 官方安装器需要交互终端。请直接运行 ./bootstrap.sh start cpa。")
        self.cpa_override()
        print("进入 CPA 官方安装器：保留当前安装目录，选择完整 Docker 安装。")
        print("升级、修复或退出由你在官方菜单中决定；不配置上游账号或模型。")
        installer = self.store.system / "tools/install-cpamp.sh"
        try:
            with urllib.request.urlopen(CPA_INSTALLER, timeout=45) as response:
                content = response.read(2_000_001)
        except Exception:
            raise StackError("下载 CPA 官方安装器失败，请检查到 GitHub 的网络后重试。")
        if not content.startswith(b"#!/") or len(content) > 2_000_000:
            raise StackError("官方安装器下载内容异常，未执行。")
        write(installer, content.decode("utf-8"), 0o700)
        run(["bash", "-n", str(installer)], capture=True)
        print("安装器 SHA-256：" + hashlib.sha256(content).hexdigest())
        env = clean_env()
        env.update(CPAMP_LANG="zh-CN", CPAMP_INSTALL_DIR=str(self.cpa_dir),
                   CPAMP_INSTALL_MODE="stack", CPAMP_DEPLOY_METHOD="docker",
                   CPAMP_PROJECT_NAME="xiaoruru-cpa")
        result = run(["bash", str(installer)], cwd=self.cpa_dir, env=env, check=False, timeout=None)
        if self.cpa_installed():
            self.validate_cpa()
        if result.returncode:
            print(f"官方安装器已退出（{result.returncode}）。未代替你继续操作，请查看实际容器状态。")
            return False
        if not self.cpa_installed():
            print("官方安装器已退出，CPA 尚未安装。")
            return False
        # The official installer owns up/recreate. Exiting its menu must not start stopped services.
        running = {row["service"] for row in self.containers()
                   if row["project"] == "xiaoruru-cpa" and row.get("State") == "running"}
        if not set(SERVICES["cpa"]) <= running:
            print("CPA 尚未全部运行，未自动补启动。可再次 start cpa 选择操作。")
            return False
        return True

    def start(self, module, from_all=False, choose=None):
        if module == "all":
            for selected in self.config["enabled"]:
                self.start(selected, from_all=True, choose=choose)
            if not self.config["enabled"]:
                self.start("gateway")
            return
        if module != "gateway" and module not in self.config["enabled"]:
            raise StackError(f"{module} 未启用，请先执行 init {module}。")
        self.gateway()
        if module == "gateway":
            self.links(module)
            return
        if module == "vpn":
            self.up(["xui"])
            state = self.store.state()
            if not state.get("vpn_admin_ready"):
                self.compose("stop", "xui")
                # Secrets are passed in the environment, not displayed or placed in host argv.
                self.compose("run", "--rm", "--no-deps", "--entrypoint", "/bin/sh",
                             "-e", "ADMIN_USER", "-e", "ADMIN_PASSWORD", "xui", "-c",
                             'exec /app/x-ui setting -username "$ADMIN_USER" -password "$ADMIN_PASSWORD" -webBasePath / -listenIP 0.0.0.0',
                             capture=True, env={**clean_env(), **self.admin_env()})
                self.store.mark("vpn_admin_ready")
                self.up(["xui"])
            if not state.get("vpn_ready"):
                self.compose("run", "--rm", "--no-deps", "panel-init", capture=True)
                self.compose("restart", "xui")
                self.up(["xui"])
                self.store.mark("vpn_ready")
            if self.config["gost"]:
                self.up(["gost"])
            else:
                self.compose("stop", "gost")
            print("VPN 客户端与订阅信息：data/vpn/output/access.json（私密文件）")
        elif module == "blog":
            image = self.config["images"]["blog"]
            if run(["docker", "image", "inspect", image], capture=True, check=False).returncode:
                raise StackError("博客镜像未准备好。请先执行 ./bootstrap.sh build blog，或 docker load 导入镜像。")
            self.up(["rurublog"])
        elif module == "cpa":
            self.cpa_override()
            if not self.cpa_installed():
                if not self.official_installer():
                    return
            elif from_all:
                self.up(SERVICES["cpa"], cpa=True)
            elif self.running("cpa"):
                print("CPA 已运行。接下来打开官方安装器，由你选择操作。")
                if not self.official_installer():
                    return
            else:
                action = choose("CPA 已安装但未运行", ["启动现有容器（不升级）", "进入官方安装器"]) if choose else 1
                if action == 0:
                    return
                if action == 2:
                    if not self.official_installer():
                        return
                else:
                    self.up(SERVICES["cpa"], cpa=True)
        self.sites(module)
        self.links(module)

    def admin_env(self):
        credentials = json.loads((self.store.system / "secrets.json").read_text())
        return {"ADMIN_USER": credentials["vpn_username"], "ADMIN_PASSWORD": credentials["vpn_password"]}

    def stop(self, module):
        if module == "all":
            if self.cpa_installed():
                self.compose("stop", *SERVICES["cpa"], cpa=True)
            self.compose("stop", "rurublog", "gost", "xui", "nginx-ui")
            print("所有功能已停止；配置与数据保留。")
            return
        if module == "gateway" and any(self.running(item) for item in MODULES):
            raise StackError("业务仍在运行，不能单独停止网关。请先停止业务，或使用 stop all。")
        if module != "gateway" and self.running("gateway"):
            self.sites(module, enabled=False)
        if module == "cpa" and not self.cpa_installed():
            print("CPA 尚未安装，无需停止。")
            return
        self.compose("stop", *SERVICES[module], cpa=module == "cpa")
        print(f"{module} 已停止；数据保留。")

    def restart(self, module):
        if module == "all":
            for item in self.config["enabled"]:
                self.restart(item)
            return
        self.gateway()
        if module == "gateway":
            self.compose("restart", "nginx-ui")
            self.up(["nginx-ui"])
            return
        if module not in self.config["enabled"]:
            raise StackError(f"{module} 未启用。")
        if module == "cpa":
            if not self.cpa_installed():
                raise StackError("CPA 尚未安装，请使用 start cpa。")
            self.cpa_override()
        services = SERVICES[module][:]
        if module == "vpn" and not self.config["gost"]:
            services.remove("gost")
        self.compose("restart", *services, cpa=module == "cpa")
        self.up(services, cpa=module == "cpa")
        self.sites(module)

    def build_blog(self):
        self.compose("build", "rurublog", timeout=3600)

    def logs(self, module):
        if module == "cpa" and not self.cpa_installed():
            raise StackError("CPA 尚未安装。")
        self.compose("logs", "--tail", "120", *SERVICES[module], cpa=module == "cpa")

    def doctor(self):
        self.preflight(prepare=False)
        if self.running("gateway"):
            self.compose("exec", "-T", "nginx-ui", "nginx", "-t", capture=True)
            self.compose("exec", "-T", "nginx-ui", "wget", "-q", "-O", "/dev/null",
                         "http://127.0.0.1:9000/healthz")
            print("网关配置与健康检查通过。")
        if self.cpa_installed():
            self.validate_cpa()
            print("CPA 内网与数据目录检查通过。")
        self.status()

    def renew(self):
        if not self.running("gateway"):
            print("网关已停止，本次跳过证书任务，不启动业务。")
            return
        self.certificate(renew=True)

    def install_timer(self):
        if sys.platform != "linux" or os.geteuid() != 0:
            raise StackError("定时续期需在使用 systemd 的 Linux 服务器上以 root 执行。")
        root = str(self.root)
        if any(ch in root for ch in ('"', "\n", "%", "\\")):
            raise StackError("项目路径不能含引号、反斜线、百分号或换行。")
        unit = f'[Unit]\nDescription=Xiaoruru certificate renewal\nAfter=docker.service\n\n[Service]\nType=oneshot\nWorkingDirectory="{root}"\nExecStart="{root}/bootstrap.sh" tools renew\n'
        timer = "[Unit]\nDescription=Daily Xiaoruru certificate check\n\n[Timer]\nOnCalendar=daily\nRandomizedDelaySec=2h\nPersistent=true\n\n[Install]\nWantedBy=timers.target\n"
        write(Path("/etc/systemd/system/xiaoruru-cert-renew.service"), unit, 0o644)
        write(Path("/etc/systemd/system/xiaoruru-cert-renew.timer"), timer, 0o644)
        run(["systemctl", "daemon-reload"])
        run(["systemctl", "enable", "--now", "xiaoruru-cert-renew.timer"])
