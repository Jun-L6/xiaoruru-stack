"""Run an isolated Nginx UI smoke test, with local-only ports and a self-signed certificate."""
import http.client
import json
import os
from pathlib import Path
import secrets
import shutil
import ssl
import socket
import subprocess
import tempfile
import time
import uuid

from test_manager import fixture
from manager.storage import write, directory


def docker(*args, capture=True):
    return subprocess.run(["docker", *args], text=True, capture_output=capture, check=True).stdout


def main():
    name = "xiaoruru-qa-" + uuid.uuid4().hex[:12]
    label = "xiaoruru.qa=" + name
    created = False
    with fixture() as (store, runtime):
        runtime.config["domains"] = {key: key.replace("_", "-") + ".xiaoruru.invalid"
                                     for key in runtime.config["domains"]}
        nginx = store.data / "gateway/nginx"
        certificates = store.data / "gateway/certificates"
        live = directory(certificates / "live/xiaoruru-services")
        docker("run", "--rm", "--label", label, "--entrypoint", "/bin/sh",
               "-v", str(nginx) + ":/fixture", runtime.config["images"]["nginx_ui"],
               "-c", "cp -a /usr/local/etc/nginx/. /fixture/")
        write(nginx / "nginx.conf", (store.root / "config/nginx-ui/nginx.conf").read_text(), 0o644)
        write(nginx / "conf.d/nginx-ui.conf", (store.root / "config/nginx-ui/common.conf").read_text(), 0o644)
        write(nginx / "sites-available/00-default.conf",
              (store.root / "config/nginx-ui/sites/00-default.conf.template").read_text(), 0o644)
        runtime.site_link("00-default", True)
        docker("run", "--rm", "--label", label, "--entrypoint", "nginx",
               "-v", str(nginx) + ":/etc/nginx", runtime.config["images"]["nginx_ui"], "-t")
        for names in ("10-nginx-ui", "20-3x-ui", "30-cpa-manager-plus", "40-cli-proxy-api", "50-rurublog"):
            write(nginx / f"sites-available/{names}.conf", runtime.render_site(names), 0o644)
            runtime.site_link(names, True)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                        "-keyout", str(live / "privkey.pem"), "-out", str(live / "fullchain.pem"),
                        "-days", "1", "-subj", "/CN=gateway.xiaoruru.invalid"],
                       capture_output=True, check=True)
        acme = store.data / "gateway/acme"
        challenge = acme / ".well-known/acme-challenge"
        challenge.mkdir(parents=True)
        for path in (acme, challenge.parent, challenge):
            path.chmod(0o755)
        write(challenge / "qa-token", "local-acme-ok", 0o644)
        try:
            docker("run", "-d", "--name", name, "--label", label,
                   "-p", "127.0.0.1::80", "-p", "127.0.0.1::443",
                   "-e", "NGINX_UI_NODE_SKIP_INSTALLATION=true",
                   "-e", "NGINX_UI_PREDEFINED_USER_NAME=qa-admin",
                   "-e", "NGINX_UI_PREDEFINED_USER_PASSWORD=" + secrets.token_hex(24),
                   "-e", "NGINX_UI_IGNORE_DOCKER_SOCKET=true",
                   "-e", "NGINX_UI_SERVER_RUN_MODE=release",
                   "-v", str(nginx) + ":/etc/nginx",
                   "-v", str(store.data / "gateway/nginx-ui") + ":/etc/nginx-ui",
                   "-v", str(certificates) + ":/etc/letsencrypt:ro",
                   "-v", str(acme) + ":/var/www/certbot:ro",
                   runtime.config["images"]["nginx_ui"])
            created = True
            info = json.loads(docker("inspect", name))[0]
            http_port = int(info["NetworkSettings"]["Ports"]["80/tcp"][0]["HostPort"])
            tls_port = int(info["NetworkSettings"]["Ports"]["443/tcp"][0]["HostPort"])
            for _ in range(60):
                check = subprocess.run(["docker", "exec", name, "wget", "-q", "-O", "/dev/null",
                                        "http://127.0.0.1:9000/healthz"], capture_output=True)
                if check.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError("Nginx UI health failed")
            docker("exec", name, "nginx", "-t")
            conn = http.client.HTTPConnection("127.0.0.1", http_port, timeout=10)
            conn.request("GET", "/.well-known/acme-challenge/qa-token", headers={"Host": "blog.xiaoruru.invalid"})
            response = conn.getresponse()
            assert response.status == 200 and response.read() == b"local-acme-ok"
            conn.close()
            conn = http.client.HTTPConnection("127.0.0.1", http_port, timeout=10)
            conn.request("GET", "/article?id=1", headers={"Host": "www.blog.xiaoruru.invalid"})
            response = conn.getresponse()
            assert response.status == 301, response.status
            assert response.getheader("Location") == "https://blog.xiaoruru.invalid/article?id=1"
            response.read()
            conn.close()
            conn = http.client.HTTPSConnection("127.0.0.1", tls_port,
                        context=ssl._create_unverified_context(), timeout=10)
            conn.sock = ssl._create_unverified_context().wrap_socket(
                    socket.create_connection(("127.0.0.1", tls_port), timeout=10),
                    server_hostname="www.blog.xiaoruru.invalid")
            conn.request("GET", "/article?id=1", headers={"Host": "www.blog.xiaoruru.invalid"})
            response = conn.getresponse()
            assert response.status == 301, response.status
            assert response.getheader("Location") == "https://blog.xiaoruru.invalid/article?id=1"
            response.read()
            conn.close()
            conn = http.client.HTTPSConnection("127.0.0.1", tls_port,
                        context=ssl._create_unverified_context(), timeout=10)
            conn.sock = ssl._create_unverified_context().wrap_socket(
                    socket.create_connection(("127.0.0.1", tls_port), timeout=10),
                    server_hostname="gateway.xiaoruru.invalid")
            conn.request("GET", "/", headers={"Host": "gateway.xiaoruru.invalid"})
            response = conn.getresponse()
            assert response.status == 200, response.status
            response.read()
            conn.close()
            print("Nginx UI healthy; all TLS vhosts validate; ACME GET 200; blog www redirects to canonical; HTTPS UI 200.")
        except Exception:
            if created:
                print(docker("logs", "--tail", "60", name))
            raise
        finally:
            if created:
                info = json.loads(docker("inspect", name))[0]
                if info["Config"]["Labels"].get("xiaoruru.qa") != name:
                    raise RuntimeError("Refusing to remove a container not owned by this test")
                docker("rm", "-f", name)


if __name__ == "__main__":
    main()
