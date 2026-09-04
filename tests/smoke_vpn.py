"""Initialize real 3x-ui twice on an isolated local Docker network; no public ports or real CA."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import uuid

from test_manager import fixture
from manager.storage import ROOT, directory
from smoke_gateway import docker


def main():
    token = "xiaoruru-vpn-qa-" + uuid.uuid4().hex[:12]
    network = token + "-net"
    label = "xiaoruru.qa=" + token
    created = False
    docker("network", "create", "--label", label, network)
    try:
        with fixture() as (store, runtime):
            certificates = store.data / "gateway/certificates"
            live = directory(certificates / "live/xiaoruru-services")
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                            "-keyout", str(live / "privkey.pem"), "-out", str(live / "fullchain.pem"),
                            "-days", "1", "-subj", "/CN=vpn.xiaoruru.invalid"],
                           capture_output=True, check=True)
            password = secrets.token_hex(24)
            env = {**os.environ, "QA_PASSWORD": password}
            command = ["docker", "run", "--rm", "--label", label,
                       "--entrypoint", "/bin/sh", "-e", "QA_PASSWORD",
                       "-v", str(store.data / "vpn/xui") + ":/etc/x-ui",
                       runtime.config["images"]["xui"], "-c",
                       'exec /app/x-ui setting -username admin -password "$QA_PASSWORD" -webBasePath / -listenIP 0.0.0.0']
            result = subprocess.run(command, env=env, capture_output=True, text=True)
            if result.returncode:
                raise RuntimeError("3x-ui credential initialization failed")
            docker("run", "-d", "--name", token, "--label", label,
                   "--network", network, "--network-alias", "xui",
                   "-e", "XUI_PORT=2053", "-e", "XUI_INIT_WEB_BASE_PATH=/",
                   "-e", "XUI_DB_FOLDER=/etc/x-ui",
                   "-v", str(store.data / "vpn/xui") + ":/etc/x-ui",
                   "-v", str(certificates) + ":/etc/letsencrypt:ro",
                   runtime.config["images"]["xui"])
            created = True
            for _ in range(60):
                ready = subprocess.run(["docker", "exec", token, "curl", "-fsS", "http://127.0.0.1:2053/"],
                                       capture_output=True)
                if ready.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError("3x-ui did not start")
            helper = ["docker", "run", "--rm", "--label", label, "--network", network,
                      "-v", str(ROOT / "manager/runtime/init-panel.py") + ":/opt/init-panel.py:ro",
                      "-v", str(store.data / "vpn/output") + ":/output"]
            variables = {"XUI_DOMAIN": "vpn.xiaoruru.invalid", "CERT_NAME": "xiaoruru-services",
                "XUI_PANEL_PORT": "2053", "XUI_SUB_PORT": "2096", "XRAY_PORT": "8443",
                "XUI_ADMIN_USERNAME": "admin", "XUI_ADMIN_PASSWORD": password,
                "DOCKER_SUBNET": "172.16.0.0/12", "PRIMARY_CLIENT_EMAIL": "qa-client"}
            helper_env = {**os.environ, **variables}
            for key in variables:
                helper += ["-e", key]
            helper += [runtime.config["images"]["panel_init"], "python3", "/opt/init-panel.py"]
            for iteration in range(2):
                result = subprocess.run(helper, env=helper_env, text=True, capture_output=True, timeout=240)
                if result.returncode:
                    print(result.stderr.replace(password, "[REDACTED]"))
                    raise RuntimeError("3x-ui API initialization failed")
                access = json.loads((store.data / "vpn/output/access.json").read_text())
                if iteration == 0:
                    first = access
                else:
                    assert access == first, "Second initialization changed client access data"
            assert len(first["links"]) >= 2
            print("3x-ui v3.7.0: credentials, subscription, Reality, Hysteria2 and client initialized; second run is idempotent.")
    finally:
        if created:
            info = json.loads(docker("inspect", token))[0]
            if info["Config"]["Labels"].get("xiaoruru.qa") != token:
                raise RuntimeError("Container ownership mismatch")
            docker("rm", "-f", token)
        info = json.loads(docker("network", "inspect", network))[0]
        if info["Labels"].get("xiaoruru.qa") != token:
            raise RuntimeError("Network ownership mismatch")
        docker("network", "rm", network)


if __name__ == "__main__":
    main()
