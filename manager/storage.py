"""Validated configuration and private, atomic runtime file generation."""
import copy
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import tempfile

ROOT = Path(__file__).resolve().parent.parent
MODULES = ("vpn", "cpa", "blog")
CERT_NAME = "xiaoruru-services"


class StackError(Exception):
    pass


def safe_path(path):
    path = Path(path).absolute()
    for part in (path, *path.parents):
        if part.is_symlink():
            raise StackError(f"不使用符号链接作为数据路径：{part}")
    return path


def directory(path, mode=0o750):
    path = safe_path(path)
    path.mkdir(parents=True, exist_ok=True, mode=mode)
    if not path.is_dir():
        raise StackError(f"不是目录：{path}")
    return path


def write(path, content, mode=0o600, overwrite=True):
    path = safe_path(path)
    directory(path.parent)
    if path.exists() and not overwrite:
        return
    descriptor, temporary = tempfile.mkstemp(prefix="." + path.name + ".", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def json_write(path, value, **kwargs):
    write(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n", **kwargs)


def validate(config):
    if config.get("schema") != 1:
        raise StackError("部署配置版本不支持。")
    enabled = config.get("enabled", [])
    if not isinstance(enabled, list) or len(set(enabled)) != len(enabled) or set(enabled) - set(MODULES):
        raise StackError("enabled 只能包含 vpn、cpa、blog，不能重复。")
    if type(config.get("gost")) is not bool:
        raise StackError("gost 必须为 true 或 false。")
    if config["gost"] and "vpn" not in enabled:
        raise StackError("正向代理属于 VPN，请同时启用 VPN。")
    if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", config.get("email", "")):
        raise StackError("请填写有效的证书通知邮箱。")
    values = config.get("domains", {})
    expected = ("gateway", "vpn", "cpamp", "cpa_api", "blog", "gost")
    for key in expected:
        domain = values.get(key, "")
        if len(domain) > 253 or "." not in domain or not all(
            re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
            for label in domain.split(".")
        ):
            raise StackError(f"域名格式错误：{key}")
    if len(set(values.values())) != len(expected):
        raise StackError("各入口请使用不同域名。")
    network = config.get("network", {})
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}", network.get("name", "")):
        raise StackError("Docker 网络名不合法。")
    try:
        subnet = ipaddress.ip_network(network["subnet"], strict=True)
        if subnet.version != 4 or not subnet.is_private or not 16 <= subnet.prefixlen <= 28:
            raise ValueError()
    except (ValueError, KeyError):
        raise StackError("网络需要独立的私有 IPv4 子网，前缀范围 /16 至 /28。")
    for image in config.get("images", {}).values():
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9./_:@-]+", image):
            raise StackError("镜像地址包含无效字符。")
    for key in ("nginx_ui", "xui", "gost", "certbot", "panel_init", "blog"):
        if key not in config.get("images", {}):
            raise StackError(f"缺少镜像设置：{key}")
    return config


class Store:
    def __init__(self, root=ROOT):
        self.root = Path(root)
        self.data = self.root / "data"
        self.system = self.data / "system"
        self.config_path = self.system / "config.json"

    def defaults(self):
        return json.loads((self.root / "config/defaults.json").read_text())

    def load(self):
        if not self.config_path.exists():
            raise StackError("尚未初始化，请运行 ./bootstrap.sh init。")
        return validate(json.loads(safe_path(self.config_path).read_text()))

    def save(self, config):
        validate(config)
        json_write(self.config_path, config)

    def state(self):
        path = self.system / "state.json"
        return json.loads(safe_path(path).read_text()) if path.exists() else {}

    def mark(self, key, value=True):
        state = self.state()
        state[key] = value
        json_write(self.system / "state.json", state)

    def prepare(self, config):
        validate(config)
        for path in ("system", "gateway/nginx", "gateway/nginx-ui", "gateway/certificates",
                     "gateway/acme", "gateway/logs", "gateway/certbot-logs",
                     "vpn/xui", "vpn/logs", "vpn/output", "vpn/gost", "cpa/manager",
                     "blog/config", "blog/runtime"):
            directory(self.data / path)
        os.chmod(self.data / "gateway/acme", 0o755)
        private = self.system / "secrets.json"
        if not private.exists():
            # An existing database must not be silently assigned fresh credentials.
            if any((self.data / "blog/runtime").iterdir()) or any((self.data / "vpn/xui").iterdir()):
                raise StackError("存在业务数据但缺少 data/system/secrets.json，请提供对应配置。")
            json_write(private, {
                "gateway_username": "admin", "gateway_password": secrets.token_hex(24),
                "vpn_username": "admin", "vpn_password": secrets.token_hex(24),
                "gost_username": "proxy", "gost_password": secrets.token_hex(24),
                "blog_admin": secrets.token_hex(24), "blog_db": secrets.token_hex(24),
            })
        credentials = json.loads(safe_path(private).read_text())
        env = {
            "GATEWAY_NETWORK": config["network"]["name"],
            "DOCKER_SUBNET": config["network"]["subnet"], "LE_EMAIL": config["email"],
            "BLOG_DOMAIN": config["domains"]["blog"], "VPN_DOMAIN": config["domains"]["vpn"],
            **{key.upper(): value for key, value in credentials.items()
               if key.startswith(("gateway_", "vpn_"))},
            **{key.upper() + "_IMAGE": value for key, value in config["images"].items()},
        }
        # Single quotes inhibit Compose expansion, not shell execution; never source this file.
        if any("'" in str(value) or "\n" in str(value) or "\r" in str(value) for value in env.values()):
            raise StackError("配置不能包含单引号或换行。")
        write(self.system / "compose.env", "".join(f"{key}='{value}'\n" for key, value in env.items()))
        blog_config = self.data / "blog/config/application.yml"
        if not blog_config.exists():
            if any((self.data / "blog/runtime").iterdir()):
                raise StackError("博客已有数据但缺少 application.yml。")
            # JSON is valid YAML and avoids interpolation/escaping vulnerabilities.
            json_write(blog_config, {
                "blog": {"admin": {"secret": credentials["blog_admin"]}},
                "spring": {"datasource": {"password": credentials["blog_db"]}},
            })
        if os.geteuid() == 0:
            for path in (self.data / "blog/config", self.data / "blog/runtime", blog_config):
                os.chown(path, 10001, 10001)
        tls = f"/etc/letsencrypt/live/{CERT_NAME}"
        json_write(self.data / "vpn/gost/config.json", {"services": [{
            "name": "https-forward-proxy", "addr": ":9443",
            "handler": {"type": "http", "auth": {
                "username": credentials["gost_username"], "password": credentials["gost_password"]}},
            "listener": {"type": "tls", "tls": {
                "certFile": tls + "/fullchain.pem", "keyFile": tls + "/privkey.pem"}}
        }]})

    def domains(self, config):
        keys = ["gateway"]
        if "vpn" in config["enabled"]:
            keys.append("vpn")
            if config["gost"]:
                keys.append("gost")
        if "cpa" in config["enabled"]:
            keys += ["cpamp", "cpa_api"]
        if "blog" in config["enabled"]:
            keys.append("blog")
        return sorted(config["domains"][key] for key in keys)
