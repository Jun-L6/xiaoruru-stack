"""Check the generated GOST TLS proxy config against a local-only synthetic origin."""
import base64
import http.client
import json
import ssl
import subprocess
import time
import uuid
from test_manager import fixture
from manager.storage import directory, write
from smoke_gateway import docker


def main():
    token = "xiaoruru-gost-qa-" + uuid.uuid4().hex[:12]
    network = token + "-net"
    names = []
    label = "xiaoruru.qa=" + token
    docker("network", "create", "--label", label, network)
    try:
        with fixture() as (store, runtime):
            live = directory(store.data / "gateway/certificates/live/xiaoruru-services")
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", str(live / "privkey.pem"), "-out", str(live / "fullchain.pem"),
                "-days", "1", "-subj", "/CN=gost.xiaoruru.invalid"], capture_output=True, check=True)
            origin = directory(store.data / "origin", 0o755)
            write(origin / "index.html", "gost-local-origin-ok", 0o644)
            origin_name = token + "-origin"
            docker("run", "-d", "--name", origin_name, "--label", label, "--network", network,
                   "--network-alias", "origin", "-v", str(origin) + ":/www:ro",
                   runtime.config["images"]["panel_init"], "python3", "-m", "http.server",
                   "8888", "--directory", "/www")
            names.append(origin_name)
            docker("run", "-d", "--name", token, "--label", label, "--network", network,
                   "-p", "127.0.0.1::9443",
                   "-v", str(store.data / "vpn/gost/config.json") + ":/etc/gost/config.json:ro",
                   "-v", str(store.data / "gateway/certificates") + ":/etc/letsencrypt:ro",
                   runtime.config["images"]["gost"], "-C", "/etc/gost/config.json")
            names.append(token)
            port = int(json.loads(docker("inspect", token))[0]["NetworkSettings"]["Ports"]["9443/tcp"][0]["HostPort"])
            for _ in range(30):
                try:
                    conn = http.client.HTTPSConnection("127.0.0.1", port,
                            context=ssl._create_unverified_context(), timeout=5)
                    conn.request("GET", "http://origin:8888/")
                    response = conn.getresponse()
                    assert response.status == 407, response.status
                    response.read()
                    conn.close()
                    break
                except OSError:
                    time.sleep(1)
            else:
                raise RuntimeError("GOST did not start")
            credentials = json.loads((store.system / "secrets.json").read_text())
            auth = base64.b64encode((credentials["gost_username"] + ":" + credentials["gost_password"]).encode()).decode()
            conn = http.client.HTTPSConnection("127.0.0.1", port,
                    context=ssl._create_unverified_context(), timeout=10)
            conn.request("GET", "http://origin:8888/", headers={"Proxy-Authorization": "Basic " + auth})
            response = conn.getresponse()
            assert response.status == 200, response.status
            assert response.read() == b"gost-local-origin-ok"
            conn.close()
            print("GOST: unauthenticated HTTPS proxy request rejected (407); authenticated forwarding passed (200).")
    finally:
        for name in reversed(names):
            info = json.loads(docker("inspect", name))[0]
            if info["Config"]["Labels"].get("xiaoruru.qa") != token:
                raise RuntimeError("Container ownership mismatch")
            docker("rm", "-f", name)
        info = json.loads(docker("network", "inspect", network))[0]
        if info["Labels"].get("xiaoruru.qa") != token:
            raise RuntimeError("Network ownership mismatch")
        docker("network", "rm", network)


if __name__ == "__main__":
    main()
