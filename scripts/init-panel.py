#!/usr/bin/env python3
"""Idempotently configure a fresh 3x-ui panel through its authenticated API."""

from __future__ import annotations

import http.cookiejar
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


def env(name: str, default: str | None = None) -> str:
    value = os.environ.get(name, default)
    if value is None or value == "":
        raise RuntimeError(f"required environment variable is empty: {name}")
    return value


XUI_DOMAIN = env("XUI_DOMAIN")
CERT_NAME = env("CERT_NAME")
PANEL_PORT = int(env("XUI_PANEL_PORT"))
SUB_PORT = int(env("XUI_SUB_PORT"))
XRAY_PORT = int(env("XRAY_PORT"))
ADMIN_USERNAME = env("XUI_ADMIN_USERNAME")
ADMIN_PASSWORD = env("XUI_ADMIN_PASSWORD")
CLIENT_EMAIL = env("PRIMARY_CLIENT_EMAIL", "jun-main")
DOCKER_SUBNET = env("DOCKER_SUBNET")
MIN_CLIENT_VER = env("REALITY_MIN_CLIENT_VER", "1.0.0")
REALITY_CANDIDATES = os.environ.get("REALITY_TARGET_CANDIDATES", "")
BASE_PATH = os.environ.get("XUI_WEB_BASE_PATH", "/").strip()
if not BASE_PATH.startswith("/"):
    BASE_PATH = "/" + BASE_PATH
if not BASE_PATH.endswith("/"):
    BASE_PATH += "/"
BASE_URL = f"http://xui:{PANEL_PORT}{BASE_PATH}"


class PanelAPI:
    def __init__(self) -> None:
        jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        self.csrf = ""

    def request(
        self,
        method: str,
        path: str,
        *,
        payload: object | None = None,
        form: dict[str, str] | None = None,
        require_success: bool = True,
    ) -> dict:
        url = urllib.parse.urljoin(BASE_URL, path.lstrip("/"))
        headers = {"Accept": "application/json", "Host": XUI_DOMAIN}
        data = None
        if payload is not None:
            data = json.dumps(payload, separators=(",", ":")).encode()
            headers["Content-Type"] = "application/json"
        elif form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        if self.csrf and method not in {"GET", "HEAD", "OPTIONS"}:
            headers["X-CSRF-Token"] = self.csrf
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=45) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            body = exc.read().decode(errors="replace")[:500]
            raise RuntimeError(f"panel API HTTP {exc.code} for {path}: {body}") from exc
        try:
            result = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise RuntimeError(f"panel API returned non-JSON for {path}") from exc
        if require_success and not result.get("success", False):
            raise RuntimeError(f"panel API rejected {path}: {result.get('msg', 'unknown error')}")
        return result

    def login(self) -> None:
        for attempt in range(30):
            try:
                token = self.request("GET", "csrf-token").get("obj")
                if not isinstance(token, str) or not token:
                    raise RuntimeError("panel returned an empty CSRF token")
                self.csrf = token
                self.request(
                    "POST",
                    "login",
                    payload={"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD},
                )
                return
            except (OSError, RuntimeError):
                if attempt == 29:
                    raise
                time.sleep(2)


def configure_settings(api: PanelAPI) -> None:
    result = api.request("POST", "panel/api/setting/all", payload={})
    settings = result.get("obj")
    if not isinstance(settings, dict):
        raise RuntimeError("could not read panel settings")
    for key in list(settings):
        if key.startswith("has"):
            settings.pop(key)
    settings.update(
        {
            "webListen": "0.0.0.0",
            "webDomain": "",
            "webPort": PANEL_PORT,
            "webCertFile": "",
            "webKeyFile": "",
            "webBasePath": BASE_PATH,
            "trustedProxyCIDRs": DOCKER_SUBNET,
            "timeLocation": os.environ.get("TZ", "Asia/Shanghai"),
            "subEnable": True,
            "subJsonEnable": True,
            "subJsonAutoDetect": False,
            "subJsonAlwaysArray": False,
            "subClashEnable": True,
            "subClashAutoDetect": False,
            "subListen": "0.0.0.0",
            "subPort": SUB_PORT,
            "subPath": "/sub/",
            "subDomain": XUI_DOMAIN,
            "subCertFile": "",
            "subKeyFile": "",
            "subURI": f"https://{XUI_DOMAIN}/sub/",
            "subJsonPath": "/json/",
            "subJsonURI": f"https://{XUI_DOMAIN}/json/",
            "subClashPath": "/clash/",
            "subClashURI": f"https://{XUI_DOMAIN}/clash/",
        }
    )
    api.request("POST", "panel/api/setting/update", payload=settings)
    print("Panel and subscription settings configured.")


def list_inbounds(api: PanelAPI) -> list[dict]:
    rows = api.request("GET", "panel/api/inbounds/list").get("obj")
    if not isinstance(rows, list):
        raise RuntimeError("could not list inbounds")
    return rows


def inbound_by_remark(api: PanelAPI, remark: str) -> dict | None:
    return next((row for row in list_inbounds(api) if row.get("remark") == remark), None)


def select_reality_target(api: PanelAPI) -> dict:
    result = api.request(
        "POST",
        "panel/api/server/scanRealityTargets",
        form={"targets": REALITY_CANDIDATES},
    ).get("obj")
    if not isinstance(result, list):
        raise RuntimeError("Reality target scan returned no result list")
    for candidate in result:
        if candidate.get("feasible") and not candidate.get("privateTarget"):
            host = candidate.get("host")
            target = candidate.get("target")
            if isinstance(host, str) and host and isinstance(target, str) and target:
                return candidate
    reasons = "; ".join(
        f"{item.get('target', '?')}: {item.get('reason', 'not feasible')}" for item in result[:5]
    )
    raise RuntimeError(f"no feasible Reality target was found: {reasons}")


def create_reality(api: PanelAPI) -> dict:
    remark = "VLESS-Reality-8443"
    existing = inbound_by_remark(api, remark)
    if existing:
        if existing.get("protocol") != "vless" or int(existing.get("port", 0)) != XRAY_PORT:
            raise RuntimeError(f"existing {remark} has unexpected protocol or port")
        print(f"Inbound already exists: {remark}")
        return existing

    target = select_reality_target(api)
    keypair = api.request("GET", "panel/api/server/getNewX25519Cert").get("obj")
    if not isinstance(keypair, dict) or not keypair.get("privateKey") or not keypair.get("publicKey"):
        raise RuntimeError("Reality key generation failed")
    target_host = target["host"]
    payload = {
        "up": 0,
        "down": 0,
        "total": 0,
        "remark": remark,
        "enable": True,
        "expiryTime": 0,
        "trafficReset": "never",
        "trafficResetDay": 1,
        "lastTrafficResetTime": 0,
        "listen": "",
        "port": XRAY_PORT,
        "protocol": "vless",
        "tag": "inbound-vless-reality-8443",
        "shareAddrStrategy": "custom",
        "shareAddr": XUI_DOMAIN,
        "subSortIndex": 1,
        "disableFlow": False,
        "settings": {"clients": [], "decryption": "none", "encryption": "none", "fallbacks": []},
        "streamSettings": {
            "network": "tcp",
            "tcpSettings": {"acceptProxyProtocol": False, "header": {"type": "none"}},
            "security": "reality",
            "realitySettings": {
                "show": False,
                "xver": 0,
                "target": target["target"],
                "serverNames": [target_host],
                "privateKey": keypair["privateKey"],
                "minClientVer": MIN_CLIENT_VER,
                "maxClientVer": "",
                "maxTimediff": 0,
                "shortIds": [secrets.token_hex(8)],
                "mldsa65Seed": "",
                "settings": {
                    "publicKey": keypair["publicKey"],
                    "fingerprint": "chrome",
                    "serverName": "",
                    "spiderX": "/",
                    "mldsa65Verify": "",
                },
            },
        },
        "sniffing": {
            "enabled": True,
            "destOverride": ["http", "tls", "quic", "fakedns"],
            "metadataOnly": False,
            "routeOnly": False,
            "ipsExcluded": [],
            "domainsExcluded": [],
        },
    }
    api.request("POST", "panel/api/inbounds/add", payload=payload)
    created = inbound_by_remark(api, remark)
    if not created:
        raise RuntimeError(f"inbound was not found after creation: {remark}")
    print(f"Inbound created: {remark}; Reality target: {target['target']}")
    return created


def create_hysteria(api: PanelAPI) -> dict:
    remark = "Hysteria2-8443"
    existing = inbound_by_remark(api, remark)
    if existing:
        if existing.get("protocol") != "hysteria" or int(existing.get("port", 0)) != XRAY_PORT:
            raise RuntimeError(f"existing {remark} has unexpected protocol or port")
        print(f"Inbound already exists: {remark}")
        return existing

    cert_dir = f"/etc/letsencrypt/live/{CERT_NAME}"
    payload = {
        "up": 0,
        "down": 0,
        "total": 0,
        "remark": remark,
        "enable": True,
        "expiryTime": 0,
        "trafficReset": "never",
        "trafficResetDay": 1,
        "lastTrafficResetTime": 0,
        "listen": "",
        "port": XRAY_PORT,
        "protocol": "hysteria",
        "tag": "inbound-hysteria2-8443",
        "shareAddrStrategy": "custom",
        "shareAddr": XUI_DOMAIN,
        "subSortIndex": 2,
        "disableFlow": False,
        "settings": {"version": 2, "clients": []},
        "streamSettings": {
            "network": "hysteria",
            "hysteriaSettings": {"version": 2, "udpIdleTimeout": 60},
            "security": "tls",
            "tlsSettings": {
                "serverName": XUI_DOMAIN,
                "minVersion": "1.2",
                "maxVersion": "1.3",
                "cipherSuites": "",
                "rejectUnknownSni": False,
                "disableSystemRoot": False,
                "enableSessionResumption": False,
                "certificates": [
                    {
                        "certificateFile": f"{cert_dir}/fullchain.pem",
                        "keyFile": f"{cert_dir}/privkey.pem",
                        "ocspStapling": 0,
                        "oneTimeLoading": False,
                        "usage": "encipherment",
                        "buildChain": False,
                    }
                ],
                "alpn": ["h3"],
                "echServerKeys": "",
                "settings": {
                    "fingerprint": "",
                    "echConfigList": "",
                    "pinnedPeerCertSha256": [],
                    "verifyPeerCertByName": "",
                },
            },
        },
        "sniffing": {
            "enabled": False,
            "destOverride": ["http", "tls", "quic", "fakedns"],
            "metadataOnly": False,
            "routeOnly": False,
            "ipsExcluded": [],
            "domainsExcluded": [],
        },
    }
    api.request("POST", "panel/api/inbounds/add", payload=payload)
    created = inbound_by_remark(api, remark)
    if not created:
        raise RuntimeError(f"inbound was not found after creation: {remark}")
    print(f"Inbound created: {remark}")
    return created


def get_client(api: PanelAPI) -> dict | None:
    rows = api.request("GET", "panel/api/clients/list").get("obj")
    if not isinstance(rows, list):
        raise RuntimeError("could not list clients")
    return next((row for row in rows if row.get("email") == CLIENT_EMAIL), None)


def create_or_attach_client(api: PanelAPI, inbound_ids: list[int]) -> dict:
    existing = get_client(api)
    if existing is None:
        payload = {
            "client": {
                "id": str(uuid.uuid4()),
                "security": "auto",
                "password": secrets.token_urlsafe(18),
                "auth": secrets.token_urlsafe(18),
                "flow": "xtls-rprx-vision",
                "email": CLIENT_EMAIL,
                "limitIp": 0,
                "totalGB": 0,
                "expiryTime": 0,
                "enable": True,
                "tgId": 0,
                "subId": secrets.token_hex(8),
                "group": "",
                "comment": "Automatically created primary client",
                "reset": 0,
            },
            "inboundIds": inbound_ids,
        }
        api.request("POST", "panel/api/clients/add", payload=payload)
        print(f"Client created: {CLIENT_EMAIL}")
    detail = api.request("GET", f"panel/api/clients/get/{urllib.parse.quote(CLIENT_EMAIL)}").get("obj")
    if not isinstance(detail, dict) or not isinstance(detail.get("client"), dict):
        raise RuntimeError("could not read the primary client after creation")
    attached = {int(value) for value in detail.get("inboundIds", [])}
    missing = [value for value in inbound_ids if value not in attached]
    if missing:
        api.request(
            "POST",
            f"panel/api/clients/{urllib.parse.quote(CLIENT_EMAIL)}/attach",
            payload={"inboundIds": missing},
        )
        detail = api.request("GET", f"panel/api/clients/get/{urllib.parse.quote(CLIENT_EMAIL)}").get("obj")
        print(f"Client attached to {len(missing)} missing inbound(s).")
    client = detail["client"]
    if not client.get("auth"):
        raise RuntimeError("Hysteria client authentication is empty")
    if client.get("flow") != "xtls-rprx-vision":
        raise RuntimeError("primary client does not use xtls-rprx-vision")
    return client


def write_access_file(api: PanelAPI, client: dict, reality: dict, hysteria: dict) -> None:
    links = api.request(
        "GET", f"panel/api/clients/links/{urllib.parse.quote(CLIENT_EMAIL)}"
    ).get("obj")
    if not isinstance(links, list) or len(links) < 2:
        raise RuntimeError("panel did not generate both client links")
    sub_id = client.get("subId")
    if not isinstance(sub_id, str) or not sub_id:
        raise RuntimeError("primary client has no subscription id")
    output = {
        "panel": f"https://{XUI_DOMAIN}/",
        "client": CLIENT_EMAIL,
        "subscriptions": {
            "base": f"https://{XUI_DOMAIN}/sub/{sub_id}",
            "json": f"https://{XUI_DOMAIN}/json/{sub_id}",
            "clash": f"https://{XUI_DOMAIN}/clash/{sub_id}",
        },
        "inbounds": {
            "vlessReality": {"id": reality["id"], "port": XRAY_PORT, "network": "tcp"},
            "hysteria2": {"id": hysteria["id"], "port": XRAY_PORT, "network": "udp"},
        },
        "links": links,
    }
    output_path = Path("/output/access.json")
    output_path.write_text(json.dumps(output, indent=2, ensure_ascii=False) + "\n")
    output_path.chmod(0o600)
    print("Private access details written to /output/access.json")


def main() -> int:
    api = PanelAPI()
    api.login()
    configure_settings(api)
    reality = create_reality(api)
    hysteria = create_hysteria(api)
    inbound_ids = [int(reality["id"]), int(hysteria["id"])]
    client = create_or_attach_client(api, inbound_ids)
    write_access_file(api, client, reality, hysteria)
    print("Panel initialization completed successfully.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001 - bootstrap must fail closed with a concise reason.
        print(f"Panel initialization failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
