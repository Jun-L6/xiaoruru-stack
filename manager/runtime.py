"""Docker lifecycle, gateway sites, certificate jobs and official CPA adapter."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request

from .storage import CERT_NAME, MODULES, StackError, Store, directory, safe_path, www_alias, write

CPA_INSTALLER = "https://raw.githubusercontent.com/seakee/CPA-Manager-Plus/main/bin/install-cpamp.sh"
SERVICES = {"gateway": ["nginx-ui"], "vpn": ["xui", "gost"],
            "blog": ["rurublog"], "cpa": ["cli-proxy-api", "cpa-manager-plus"]}
SITES = {"gateway": ["10-nginx-ui"], "vpn": ["20-3x-ui"],
         "cpa": ["30-cpa-manager-plus", "40-cli-proxy-api"], "blog": ["50-rurublog"]}
MINIMUM_COMPOSE = (2, 24, 4)
BLOG_SOURCE_LABEL = "beer.xiaoruru.blog.source-hash"
# Identifies the database layout accepted by the current blog image.
BLOG_SCHEMA_GENERATION = "schema-v1"


def blog_source_hash(root):
    """Hash every file that can change the blog image, without depending on Git metadata."""
    root = safe_path(Path(root) / "apps/blog")
    selected = []
    for relative in ("Dockerfile", ".dockerignore", "pom.xml"):
        selected.append(safe_path(root / relative))
    for folder in ("container", "src"):
        base = safe_path(root / folder)
        if not base.is_dir():
            raise StackError(f"博客源码目录缺失：{base}")
        selected.extend(sorted(path for path in base.rglob("*") if path.is_file()))
    digest = hashlib.sha256()
    for path in sorted(selected, key=lambda item: item.relative_to(root).as_posix()):
        path = safe_path(path)
        if not path.is_file():
            raise StackError(f"博客构建文件缺失：{path}")
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        with path.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
    return digest.hexdigest()


def clean_env():
    # Neither a user's Compose variables nor another CPA install may redirect this stack.
    return {key: value for key, value in os.environ.items()
            if not key.startswith(("COMPOSE_", "CPAMP_", "CPA_", "GATEWAY_", "VPN_", "BLOG_",
                                   "NGINX_UI_", "XUI_", "GOST_", "CERTBOT_", "PANEL_INIT_"))
            and key not in ("DOCKER_SUBNET", "LE_EMAIL")}


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
    project = "xiaoruru"
    cpa_project = "xiaoruru-cpa"

    def __init__(self, store=None):
        self.store = store or Store()
        self.root = self.store.root
        self.config = self.store.load()
        self.cpa_dir = self.store.data / "cpa"

    def compose(self, *args, cpa=False, **kwargs):
        if cpa:
            base = ["docker", "compose", "--env-file", str(self.cpa_dir / ".env"),
                    "-f", str(self.cpa_dir / "compose.yaml"),
                    "-f", str(self.cpa_dir / "compose.override.yaml"), "-p", self.cpa_project]
            cwd = self.cpa_dir
        else:
            base = ["docker", "compose", "--env-file", str(self.store.system / "compose.env"),
                    "-f", str(self.root / "compose.yaml"), "-p", self.project,
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
        if prepare:
            self.ensure_host_environment()
        for executable in ("docker", "openssl", "bash", "curl"):
            if not shutil.which(executable):
                raise StackError(f"请安装 {executable}。")
        version = run(["docker", "compose", "version", "--short"], capture=True).stdout
        digits = re.search(r"(\d+)\.(\d+)\.(\d+)", version)
        if not digits or tuple(map(int, digits.groups())) < MINIMUM_COMPOSE:
            raise StackError("需要 Docker Compose 2.24.4 或更高版本，以支持 CPA 的 !reset 覆盖。")
        run(["docker", "info", "--format", "{{.ServerVersion}}"], capture=True)
        if prepare:
            self.ensure_swap()
            self.store.prepare(self.config)
        self.compose("config", "--quiet", capture=True)

    @staticmethod
    def compose_supported():
        if not shutil.which("docker"):
            return False
        result = run(["docker", "compose", "version", "--short"], capture=True,
                     check=False, timeout=30)
        digits = re.search(r"(\d+)\.(\d+)\.(\d+)", result.stdout if result.returncode == 0 else "")
        return bool(digits and tuple(map(int, digits.groups())) >= MINIMUM_COMPOSE)

    @staticmethod
    def operating_system(path):
        try:
            values = {}
            for line in Path(path).read_text().splitlines():
                if "=" in line and not line.lstrip().startswith("#"):
                    key, value = line.split("=", 1)
                    values[key] = value.strip().strip('"\'')
            return values
        except OSError:
            return {}

    def ensure_host_environment(self, os_release=Path("/etc/os-release"),
                                key=Path("/etc/apt/keyrings/xiaoruru-docker.asc"),
                                source=Path("/etc/apt/sources.list.d/xiaoruru-docker.sources")):
        """Prepare production prerequisites on a clean, supported Ubuntu host."""
        base_missing = [name for name in ("bash", "curl", "openssl") if not shutil.which(name)]
        docker_packages_needed = not shutil.which("docker") or not self.compose_supported()
        daemon_ready = (not docker_packages_needed and run(
            ["docker", "info", "--format", "{{.ServerVersion}}"], capture=True,
            check=False, timeout=30).returncode == 0)
        if not base_missing and not docker_packages_needed and daemon_ready:
            print("环境检查：Docker Engine、Compose、curl 与 OpenSSL 已就绪。")
            return
        if sys.platform != "linux":
            return
        release = self.operating_system(os_release)
        codename = release.get("UBUNTU_CODENAME") or release.get("VERSION_CODENAME", "")
        if release.get("ID") != "ubuntu" or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", codename):
            raise StackError("自动准备宿主机目前只支持官方 Ubuntu；此系统请按 Docker 官方文档安装环境。")
        if os.geteuid() != 0:
            raise StackError("自动安装 Docker 和系统依赖需要以 root 运行。")
        if not shutil.which("apt-get") or not shutil.which("dpkg") or not shutil.which("dpkg-query"):
            raise StackError("Ubuntu APT/dpkg 工具不完整，无法自动准备宿主机。")
        if not shutil.which("systemctl") or not Path("/run/systemd/system").is_dir():
            raise StackError("完整部署需要使用 systemd 的 Ubuntu 服务器。")
        apt_env = {**clean_env(), "DEBIAN_FRONTEND": "noninteractive", "NEEDRESTART_MODE": "a"}
        apt = ["apt-get", "-o", "DPkg::Lock::Timeout=120"]
        if docker_packages_needed:
            conflicts = []
            for package in ("docker.io", "docker-compose", "docker-compose-v2", "docker-doc",
                            "docker-buildx", "podman-docker", "containerd", "runc"):
                status = run(["dpkg-query", "-W", "-f=${Status}", package],
                             capture=True, check=False, timeout=30)
                if status.returncode == 0 and "install ok installed" in status.stdout:
                    conflicts.append(package)
            if conflicts:
                raise StackError("检测到与 Docker 官方软件包冲突的现有组件：" + ", ".join(conflicts)
                                 + "。为避免影响已有业务，脚本不会自动卸载。")
        if base_missing or docker_packages_needed:
            print("环境准备：更新 Ubuntu 软件索引并安装基础依赖...")
            run(apt + ["update"], env=apt_env, timeout=1800)
            run(apt + ["install", "-y", "ca-certificates", "curl", "openssl"],
                env=apt_env, timeout=1800)
        if docker_packages_needed:
            architecture = run(["dpkg", "--print-architecture"], capture=True).stdout.strip()
            if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", architecture):
                raise StackError("无法识别用于 Docker 仓库的服务器架构。")
            key = safe_path(key)
            source = safe_path(source)
            directory(source.parent, 0o755)
            existing_repository = False
            for pattern in ("*.list", "*.sources"):
                for candidate in source.parent.glob(pattern):
                    try:
                        if "https://download.docker.com/linux/ubuntu" in candidate.read_text():
                            existing_repository = True
                    except OSError:
                        continue
            if not existing_repository:
                try:
                    with urllib.request.urlopen("https://download.docker.com/linux/ubuntu/gpg", timeout=45) as response:
                        docker_key = response.read(200_001)
                except Exception:
                    raise StackError("下载 Docker 官方 APT 签名密钥失败，请检查服务器网络。")
                if (len(docker_key) > 200_000
                        or not docker_key.startswith(b"-----BEGIN PGP PUBLIC KEY BLOCK-----")):
                    raise StackError("Docker 官方 APT 签名密钥内容异常，未继续安装。")
                if source.exists() and "Managed by Xiaoruru" not in source.read_text():
                    raise StackError(f"{source} 不是本项目创建的仓库配置，未覆盖。")
                directory(key.parent, 0o755)
                os.chmod(key.parent, 0o755)
                write(key, docker_key.decode("ascii"), 0o644)
                repository = ("# Managed by Xiaoruru host preparation\n"
                              "Types: deb\n"
                              "URIs: https://download.docker.com/linux/ubuntu\n"
                              f"Suites: {codename}\n"
                              "Components: stable\n"
                              f"Architectures: {architecture}\n"
                              f"Signed-By: {key}\n")
                write(source, repository, 0o644)
            print("环境准备：通过 Docker 官方 APT 仓库安装 Engine 与 Compose...")
            run(apt + ["update"], env=apt_env, timeout=1800)
            run(apt + ["install", "-y", "docker-ce", "docker-ce-cli", "containerd.io",
                       "docker-buildx-plugin", "docker-compose-plugin"], env=apt_env, timeout=1800)
        run(["systemctl", "enable", "--now", "docker"], timeout=180)
        if (not shutil.which("docker") or not self.compose_supported()
                or run(["docker", "info", "--format", "{{.ServerVersion}}"], capture=True,
                       check=False, timeout=30).returncode):
            raise StackError("宿主机依赖安装完成，但 Docker Engine 或 Compose 校验失败。")
        print("环境准备：Docker Engine、Compose、curl 与 OpenSSL 已安装并启动。")

    @staticmethod
    def recommended_swap_mib(memory_mib, enabled):
        """Return a conservative swap size for this stack, or zero when RAM is sufficient."""
        if set(enabled) & {"cpa", "blog"}:
            if memory_mib < 2048:
                return 2048
            if memory_mib < 4096:
                return 1024
            return 0
        return 1024 if memory_mib < 1536 else 0

    def ensure_swap(self, meminfo=Path("/proc/meminfo"), fstab=Path("/etc/fstab"),
                    swapfile=Path("/swapfile")):
        """Create a persistent swap file only when Linux has no active swap and RAM is low."""
        if sys.platform != "linux":
            return
        try:
            values = {}
            for line in safe_path(meminfo).read_text().splitlines():
                match = re.fullmatch(r"(MemTotal|SwapTotal):\s+(\d+)\s+kB", line)
                if match:
                    values[match.group(1)] = int(match.group(2))
            memory_mib = values["MemTotal"] // 1024
            swap_kib = values["SwapTotal"]
        except (KeyError, OSError, ValueError):
            raise StackError("无法读取 Linux 内存信息，未自动调整 Swap。")
        if swap_kib:
            swap_mib = max(1, swap_kib // 1024)
            print(f"资源检查：已有 {swap_mib} MiB Swap，保持不变。")
            return
        wanted_mib = self.recommended_swap_mib(memory_mib, self.config["enabled"])
        if not wanted_mib:
            print(f"资源检查：内存 {memory_mib} MiB，无需自动创建 Swap。")
            return
        if os.geteuid() != 0:
            raise StackError(
                f"内存仅 {memory_mib} MiB 且没有 Swap；请以 root 运行，以自动创建 {wanted_mib} MiB Swap。")
        swapfile = safe_path(swapfile)
        fstab = safe_path(fstab)
        if swapfile.exists():
            raise StackError(f"检测到未启用的 {swapfile}，为避免覆盖已有文件，请先人工检查。")
        fstab_before = fstab.read_text() if fstab.exists() else ""
        active_lines = [line for line in fstab_before.splitlines()
                        if line.strip() and not line.lstrip().startswith("#")]
        if any(line.split()[0] == str(swapfile) for line in active_lines):
            raise StackError(f"{fstab} 已包含 {swapfile} 但系统未启用它，请先人工检查。")
        for executable in ("mkswap", "swapon"):
            if not shutil.which(executable):
                raise StackError(f"需要 {executable} 才能自动配置 Swap。")
        reserve = 2 * 1024 * 1024 * 1024
        wanted_bytes = wanted_mib * 1024 * 1024
        if shutil.disk_usage(swapfile.parent).free < wanted_bytes + reserve:
            raise StackError(
                f"内存仅 {memory_mib} MiB 且没有 Swap，但磁盘不足以创建 {wanted_mib} MiB Swap并保留 2 GiB 空间。")
        created = False
        fstab_changed = False
        try:
            try:
                descriptor = os.open(swapfile, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                os.close(descriptor)
                created = True
            except FileExistsError:
                raise StackError(f"创建 {swapfile} 时发现路径已被占用，未覆盖该文件。")
            if shutil.which("fallocate"):
                result = run(["fallocate", "-l", f"{wanted_mib}M", str(swapfile)],
                             capture=True, check=False)
                if result.returncode:
                    if not shutil.which("dd"):
                        raise StackError("fallocate 创建 Swap 失败，且系统没有 dd 可供回退。")
                    run(["dd", "if=/dev/zero", f"of={swapfile}", "bs=1M",
                         f"count={wanted_mib}", "status=none"])
            elif shutil.which("dd"):
                run(["dd", "if=/dev/zero", f"of={swapfile}", "bs=1M",
                     f"count={wanted_mib}", "status=none"])
            else:
                raise StackError("需要 fallocate 或 dd 才能自动创建 Swap 文件。")
            if not swapfile.is_file() or swapfile.is_symlink():
                raise StackError("Swap 文件创建结果异常，已停止配置。")
            os.chmod(swapfile, 0o600)
            run(["mkswap", str(swapfile)], capture=True)
            ending = "" if not fstab_before or fstab_before.endswith("\n") else "\n"
            write(fstab, fstab_before + ending + f"{swapfile} none swap sw 0 0\n", 0o644)
            fstab_changed = True
            run(["swapon", str(swapfile)], capture=True)
            active = run(["swapon", "--show=NAME", "--noheadings"], capture=True).stdout.splitlines()
            if str(swapfile) not in {line.strip() for line in active}:
                raise StackError("Swap 文件已创建，但启用状态校验失败。")
        except BaseException:
            status = run(["swapon", "--show=NAME", "--noheadings"], capture=True,
                         check=False).stdout.splitlines()
            is_active = str(swapfile) in {line.strip() for line in status}
            if not is_active:
                if fstab_changed:
                    write(fstab, fstab_before, 0o644)
                if created and swapfile.is_file() and not swapfile.is_symlink():
                    swapfile.unlink()
            raise
        print(f"资源检查：内存 {memory_mib} MiB，已创建并启用 {wanted_mib} MiB Swap。")

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
            if project not in (self.project, self.cpa_project) or labels.get("com.docker.compose.oneoff", "").lower() == "true":
                continue
            row["service"] = labels.get("com.docker.compose.service")
            row["project"] = project
            rows.append(row)
        return rows

    def running(self, module):
        project = self.cpa_project if module == "cpa" else self.project
        return any(row["project"] == project and row["service"] in SERVICES[module]
                   and row.get("State") == "running" for row in self.containers())

    def image_exists(self, image):
        return run(["docker", "image", "inspect", image], capture=True, check=False).returncode == 0

    def blog_image_current(self):
        image = self.config["images"]["blog"]
        if not self.image_exists(image):
            return False
        template = '{{ index .Config.Labels "' + BLOG_SOURCE_LABEL + '" }}'
        result = run(["docker", "image", "inspect", "--format", template, image],
                     capture=True, check=False, timeout=30)
        return result.returncode == 0 and result.stdout.strip() == blog_source_hash(self.root)

    def ensure_blog_schema_compatible(self):
        database = safe_path(self.store.data / "blog/runtime/database")
        if not database.exists() or not any(database.iterdir()):
            return
        if self.store.state().get("blog_schema_generation") != BLOG_SCHEMA_GENERATION:
            raise StackError(
                "博客数据库结构与当前应用不匹配。"
                "如果不需要保留数据，请执行 ./bootstrap.sh reset blog 重新初始化。")

    def verify_deployment(self):
        rows = self.containers()
        incomplete = []
        for module in ("gateway", *self.config["enabled"]):
            expected = SERVICES[module][:]
            if module == "vpn" and not self.config["gost"]:
                expected.remove("gost")
            project = self.cpa_project if module == "cpa" else self.project
            ready = {row["service"] for row in rows if row["project"] == project
                     and row.get("State") == "running" and "(unhealthy)" not in row.get("Status", "")}
            missing = set(expected) - ready
            if missing:
                incomplete.append(f"{module}: {', '.join(sorted(missing))}")
        if "cpa" in self.config["enabled"] and not self.cpa_ready():
            incomplete.append("cpa: 官方初始化未完成")
        if incomplete:
            raise StackError("部署尚未全部就绪（" + "；".join(incomplete) + "）。可查看 status / logs 后重试 deploy。")
        print("部署检查：所有已启用功能的容器均已运行。")

    def status(self):
        rows = self.containers()
        print("\n功能状态（运行状态不代表 CPA 已配置模型）")
        for module in ("gateway", *MODULES):
            expected = SERVICES[module][:]
            if module == "vpn" and not self.config["gost"]:
                expected.remove("gost")
            project = self.cpa_project if module == "cpa" else self.project
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
        certificate = self.store.data / f"gateway/certificates/live/{CERT_NAME}/fullchain.pem"
        if not cpa and certificate.is_file() and set(services) & {"xui", "gost"}:
            applied = self.store.state().get("certificate_applied", {})
            digest = hashlib.sha256(certificate.read_bytes()).hexdigest()
            for service in set(services) & {"xui", "gost"}:
                applied[service] = digest
            self.store.mark("certificate_applied", applied)

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
                  "BLOG_DOMAIN": domains["blog"], "BLOG_WWW_DOMAIN": www_alias(domains["blog"]),
                  "CERT_NAME": CERT_NAME,
                  "XUI_PANEL_PORT": "2053", "XUI_SUB_PORT": "2096"}
        content = (self.root / f"config/nginx-ui/sites/{name}.conf.template").read_text()
        for key, value in values.items():
            content = content.replace(f"__{key}__", value)
        if re.search(r"__[A-Z_]+__", content):
            raise StackError("Nginx 模板包含未填充的变量。")
        return content

    def sites(self, module, enabled=True, replace=False, offline=False):
        nginx = self.store.data / "gateway/nginx"
        snapshots = []
        try:
            for name in SITES[module]:
                path = safe_path(nginx / "sites-available" / (name + ".conf"))
                link = nginx / "sites-enabled" / (name + ".conf")
                # Validate ownership before recording or changing an enabled link.
                self.site_link(name, link.is_symlink())
                snapshots.append((name, path.read_text() if path.exists() else None,
                                  os.readlink(link) if link.is_symlink() else None))
                if enabled:
                    write(path, self.render_site(name), 0o644, overwrite=replace)
                self.site_link(name, enabled)
            if offline:
                self.compose("run", "--rm", "--no-deps", "--entrypoint", "nginx", "nginx-ui", "-t", capture=True)
            else:
                self.reload()
        except BaseException:
            # A failed nginx -t must not leave a newly invalid configuration on disk.
            for name, content, target in reversed(snapshots):
                path = nginx / "sites-available" / (name + ".conf")
                link = nginx / "sites-enabled" / (name + ".conf")
                self.site_link(name, False)
                if content is None:
                    if path.is_file():
                        safe_path(path).unlink()
                else:
                    write(path, content, 0o644)
                if target is not None:
                    link.symlink_to(target)
            raise

    def repair_sites(self, module):
        # Repair the selected disk configuration before trying to start/reload a broken gateway.
        certificate = self.store.data / f"gateway/certificates/live/{CERT_NAME}/fullchain.pem"
        if self.store.state().get("gateway_seeded") and certificate.is_file():
            self.network()
            self.sites(module, replace=True, offline=not self.running("gateway"))
            self.gateway()
        else:
            self.gateway()
            self.sites(module, replace=True)

    def reload(self):
        self.compose("exec", "-T", "nginx-ui", "nginx", "-t", capture=True)
        self.compose("exec", "-T", "nginx-ui", "nginx", "-s", "reload", capture=True)

    def certificate_domains(self):
        certificate = self.store.data / f"gateway/certificates/live/{CERT_NAME}/fullchain.pem"
        if not certificate.exists():
            return set(), False
        result = run(["openssl", "x509", "-in", str(certificate), "-noout", "-text"],
                     capture=True)
        names = set(re.findall(r"DNS:([a-zA-Z0-9.*-]+)", result.stdout))
        valid = run(["openssl", "x509", "-in", str(certificate), "-noout", "-checkend", "2592000"],
                    capture=True, check=False).returncode == 0
        return names, valid

    def certificate(self, renew=False):
        existing, valid = self.certificate_domains()
        wanted = set(self.store.domains(self.config)) | existing
        if not renew and valid and wanted <= existing:
            self.apply_certificate()
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
            print("证书文件未变化；仅补做此前未完成的证书加载。")
        self.apply_certificate()

    def apply_certificate(self):
        live = self.store.data / f"gateway/certificates/live/{CERT_NAME}"
        if not (live / "fullchain.pem").is_file() or not (live / "privkey.pem").is_file():
            raise StackError("共享证书或私钥缺失，请检查 data/gateway/certificates。")
        digest = hashlib.sha256((live / "fullchain.pem").read_bytes()).hexdigest()
        applied = self.store.state().get("certificate_applied", {})
        if applied.get("nginx-ui") != digest:
            self.reload()
            applied["nginx-ui"] = digest
            self.store.mark("certificate_applied", applied)
        rows = self.containers()
        for service in ("xui", "gost"):
            if applied.get(service) != digest and any(
                    row["project"] == self.project and row["service"] == service
                    and row.get("State") == "running" for row in rows):
                self.compose("restart", service)
                self.up([service])
                applied[service] = digest
                self.store.mark("certificate_applied", applied)

    def gateway(self):
        self.network()
        self.seed_gateway()
        self.up(["nginx-ui"])
        self.reload()  # Backend health alone does not prove Nginx config is valid.
        self.certificate()
        self.sites("gateway")

    def cpa_installed(self):
        return (self.cpa_dir / "compose.yaml").is_file() and (self.cpa_dir / ".env").is_file()

    def cpa_ready(self):
        return (self.cpa_installed() and self.store.state().get("cpa_ready", False)
                and all((self.cpa_dir / path).is_file()
                        for path in ("manager/usage.sqlite", "manager/data.key", "secrets/cpamp-admin-key")))

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
                   CPAMP_PROJECT_NAME=self.cpa_project)
        result = run(["bash", str(installer)], cwd=self.cpa_dir, env=env, check=False, timeout=None)
        if self.cpa_installed():
            self.validate_cpa()
        if result.returncode:
            raise StackError(f"官方安装器未完成（退出码 {result.returncode}）。请再次 start cpa，在官方菜单中继续安装或修复。")
        if not self.cpa_installed():
            print("官方安装器已退出，CPA 尚未安装。")
            return False
        # The official installer owns up/recreate. Exiting its menu must not start stopped services.
        running = {row["service"] for row in self.containers()
                   if row["project"] == self.cpa_project and row.get("State") == "running"}
        if not set(SERVICES["cpa"]) <= running:
            print("CPA 尚未全部运行，未自动补启动。可再次 start cpa 选择操作。")
            return False
        if not all((self.cpa_dir / path).is_file()
                   for path in ("manager/usage.sqlite", "manager/data.key", "secrets/cpamp-admin-key")):
            raise StackError("CPA 初始化数据不完整，请再次 start cpa，使用官方安装器完成安装或修复。")
        self.verify_cpa_running()
        self.store.mark("cpa_ready")
        return True

    def verify_cpa_running(self):
        rows = [row for row in self.containers() if row["project"] == self.cpa_project]
        for service in SERVICES["cpa"]:
            row = next((row for row in rows if row["service"] == service and row.get("State") == "running"), None)
            if not row:
                raise StackError(f"CPA 容器未运行：{service}，请在官方安装器中继续操作。")
            info = json.loads(run(["docker", "inspect", row["ID"]], capture=True).stdout)[0]
            networks = info["NetworkSettings"]["Networks"]
            mounts = info["Mounts"]
            if (self.config["network"]["name"] not in networks
                    or any(info["NetworkSettings"].get("Ports", {}).values())
                    or any(mount["Type"] != "bind" or not Path(mount["Source"]).resolve().is_relative_to(self.cpa_dir.resolve())
                           for mount in mounts)
                    or info["State"].get("Health", {}).get("Status", "healthy") != "healthy"):
                raise StackError(f"CPA 容器健康、网络或挂载检查失败：{service}。请在官方安装器中应用配置后重试。")

    def start(self, module, from_all=False, choose=None):
        if module == "all":
            for selected in self.config["enabled"]:
                self.start(selected, from_all=True, choose=choose)
            if not self.config["enabled"]:
                self.start("gateway")
            return
        if module != "gateway" and module not in self.config["enabled"]:
            raise StackError(f"{module} 未启用，请先执行 init {module}。")
        if module == "vpn":
            self.require_vpn_data()
        if module == "blog":
            if not self.image_exists(self.config["images"]["blog"]):
                raise StackError("博客镜像未准备好。请先执行 ./bootstrap.sh build blog，或 docker load 导入镜像。")
            if not self.blog_image_current():
                raise StackError("博客镜像不是由当前源码构建的，请先执行 ./bootstrap.sh build blog。")
            self.ensure_blog_schema_compatible()
        self.gateway()
        if module == "gateway":
            self.links(module)
            return
        if module == "vpn":
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
            self.up(["rurublog"])
            self.store.mark("blog_schema_generation", BLOG_SCHEMA_GENERATION)
        elif module == "cpa":
            self.cpa_override()
            if not self.cpa_ready():
                print("CPA 尚未完成初始化，进入官方安装器继续安装或修复。")
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

    def require_vpn_data(self):
        state = self.store.state()
        if (state.get("vpn_admin_ready") or state.get("vpn_ready")) and not (self.store.data / "vpn/xui/x-ui.db").is_file():
            raise StackError("VPN 初始化状态存在但数据库缺失，请检查 data/vpn/xui 的完整性。")
        if state.get("vpn_ready") and not state.get("vpn_admin_ready"):
            raise StackError("VPN 初始化状态不完整，请检查 data/system/state.json。")

    def stop(self, module):
        rows = self.containers()
        if module == "gateway" and any(
                row["service"] in SERVICES[item] and row["project"] == (self.cpa_project if item == "cpa" else self.project)
                and row.get("State") in ("running", "restarting", "paused") for row in rows for item in MODULES):
            raise StackError("业务仍在运行，不能单独停止网关。请先停止业务，或使用 stop all。")
        selected = ("gateway", *MODULES) if module == "all" else (module,)
        targets = [row["ID"] for row in rows
                   if any(row["project"] == (self.cpa_project if item == "cpa" else self.project)
                          and row["service"] in SERVICES[item] for item in selected)
                   and row.get("State") in ("running", "restarting", "paused")]
        if targets:
            if not all(re.fullmatch(r"[a-f0-9]{12,64}", item) for item in targets):
                raise StackError("Docker 返回的容器 ID 异常，未执行停止。")
            run(["docker", "stop", "--timeout", "30", *targets], capture=True, timeout=180)
        if module not in ("gateway", "all") and self.running("gateway"):
            try:
                self.sites(module, enabled=False)
            except (StackError, OSError):
                print("容器已停止，但网关站点未能停用；请使用 doctor 检查 Nginx 配置。")
        print(f"{module} 已停止；数据保留。")

    def restart(self, module):
        if module == "all":
            self.restart("gateway")
            for item in self.config["enabled"]:
                self.restart(item)
            return
        if module != "gateway" and module not in self.config["enabled"]:
            raise StackError(f"{module} 未启用。")
        if module == "vpn":
            self.require_vpn_data()
        if module == "blog":
            if not self.blog_image_current():
                raise StackError("博客镜像不是由当前源码构建的，请先执行 ./bootstrap.sh build blog。")
            self.ensure_blog_schema_compatible()
        if module == "vpn" and not self.store.state().get("vpn_ready"):
            self.start("vpn")
            return
        if module == "cpa" and not self.cpa_ready():
            raise StackError("CPA 尚未完成安装，请使用 start cpa。")
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
        if module == "blog":
            self.store.mark("blog_schema_generation", BLOG_SCHEMA_GENERATION)

    def build_blog(self):
        source_hash = blog_source_hash(self.root)
        try:
            values = {match.group(1): int(match.group(2)) for line in Path("/proc/meminfo").read_text().splitlines()
                      if (match := re.fullmatch(r"(MemTotal|MemAvailable):\s+(\d+)\s+kB", line))}
            if values.get("MemTotal", 4 * 1024 * 1024) < 3 * 1024 * 1024:
                available = values.get("MemAvailable", 0) // 1024
                print(f"低内存构建模式：当前可用约 {available} MiB；复用 Maven 缓存、限制构建堆并跳过重复测试。")
        except OSError:
            pass
        print(f"博客源码指纹：{source_hash[:12]}；开始构建生产镜像（显示完整阶段）。")
        self.compose("--progress", "plain", "build", "--build-arg", f"BLOG_SOURCE_HASH={source_hash}",
                     "--build-arg", "BLOG_BUILD_TESTS=false", "rurublog", timeout=3600,
                     env={**clean_env(), "BLOG_SOURCE_HASH": source_hash, "BLOG_BUILD_TESTS": "false"})
        if not self.blog_image_current():
            raise StackError("博客镜像构建完成，但源码指纹校验失败。")
        print("博客镜像构建完成并通过源码指纹校验。")

    def reset_blog(self):
        """Delete only the two managed blog data directories, then recreate fresh configuration."""
        self.compose("stop", "rurublog", capture=True, timeout=180)
        if self.running("gateway"):
            self.sites("blog", enabled=False)
        blog_root = safe_path(self.store.data / "blog")
        directory(blog_root)
        targets = [safe_path(blog_root / name) for name in ("config", "runtime")]
        for target in targets:
            if target.parent != blog_root or os.path.ismount(target):
                raise StackError(f"博客清理目标异常，拒绝删除：{target}")
            if target.exists() and not target.is_dir():
                raise StackError(f"博客清理目标不是目录，拒绝删除：{target}")
        for target in targets:
            if target.exists():
                shutil.rmtree(target)
        self.store.mark("blog_schema_generation", None)
        self.store.prepare(self.config)
        print("博客数据已重置：文章、上传、备份、日志、AI 设置和博客配置均已清空；其他功能数据未改动。")

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
            if self.running("cpa"):
                self.verify_cpa_running()
            print("CPA 内网与数据目录检查通过。")
        self.status()

    def renew(self):
        if not self.running("gateway"):
            print("网关已停止，本次跳过证书任务，不启动业务。")
            return
        self.certificate(renew=True)

    def install_timer(self):
        self.check_timer_support()
        root = str(self.root)
        if any(ch in root for ch in ('"', "\n", "%", "\\")):
            raise StackError("项目路径不能含引号、反斜线、百分号或换行。")
        unit = f'[Unit]\nDescription=Xiaoruru certificate renewal\nAfter=docker.service\n\n[Service]\nType=oneshot\nWorkingDirectory={root}\nExecStart="{root}/bootstrap.sh" tools renew\n'
        timer = "[Unit]\nDescription=Daily Xiaoruru certificate check\n\n[Timer]\nOnCalendar=daily\nRandomizedDelaySec=2h\nPersistent=true\n\n[Install]\nWantedBy=timers.target\n"
        write(Path("/etc/systemd/system/xiaoruru-cert-renew.service"), unit, 0o644)
        write(Path("/etc/systemd/system/xiaoruru-cert-renew.timer"), timer, 0o644)
        run(["systemctl", "daemon-reload"])
        run(["systemctl", "enable", "--now", "xiaoruru-cert-renew.timer"])

    def check_timer_support(self):
        if (sys.platform != "linux" or os.geteuid() != 0 or not shutil.which("systemctl")
                or not Path("/run/systemd/system").is_dir()):
            raise StackError("完整部署需要在使用 systemd 的 Linux 服务器上以 root 执行，以安装证书续期任务。")
