"""Exercise actual manager start/stop/retry paths in a private, disposable Compose project.

Only certificate issuance is substituted with a local self-signed certificate. No real
domains, server, public ports or CPA accounts are used. Requires local service images.
"""
import http.client
import json
import shutil
import urllib.parse
import uuid

from test_manager import fixture
from manager.runtime import Runtime, run
from manager.storage import CERT_NAME, ROOT, StackError, directory, write


class LocalRuntime(Runtime):
    def certificate(self, renew=False):
        live = directory(self.store.data / f"gateway/certificates/live/{CERT_NAME}")
        if not (live / "fullchain.pem").exists():
            names = ",".join("DNS:" + name for name in self.store.domains(self.config))
            run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                 "-keyout", str(live / "privkey.pem"), "-out", str(live / "fullchain.pem"),
                 "-days", "90", "-subj", "/CN=gateway.xiaoruru.invalid",
                 "-addext", "subjectAltName=" + names], capture=True)
        super().certificate(renew=renew)


def check_tunnels(runtime):
    access = json.loads((runtime.store.data / "vpn/output/access.json").read_text())
    path = urllib.parse.urlparse(access["subscriptions"]["json"]).path
    fetched = runtime.compose("exec", "-T", "xui", "curl", "-fsS", "-H", "Host: " + runtime.config["domains"]["vpn"],
                              "http://127.0.0.1:2096" + path, capture=True, check=False)
    if fetched.returncode:
        raise AssertionError("JSON subscription fetch failed: " + fetched.stderr.replace(path, "[subscription]"))
    payload = json.loads(fetched.stdout)
    configs = payload if isinstance(payload, list) else [payload]
    outbounds = [item for config in configs for item in config.get("outbounds", [])
                 if item.get("protocol") in ("vless", "hysteria")]
    assert {item["protocol"] for item in outbounds} == {"vless", "hysteria"}, "Both protocols must be present in the JSON subscription"
    client = {"log": {"loglevel": "warning"}, "inbounds": [], "outbounds": [], "routing": {"rules": []}}
    for index, outbound in enumerate(outbounds):
        tag = "qa-" + str(index)
        outbound["tag"] = tag
        settings = outbound["settings"]
        if "vnext" in settings:
            settings["vnext"][0]["address"] = "xui"
        else:
            settings["address"] = "xui"
        stream = outbound["streamSettings"]
        if stream.get("security") == "tls":
            # Explicitly trust only the local test CA; do not disable certificate validation.
            stream["tlsSettings"]["certificates"] = [{"certificateFile": "/qa-ca/fullchain.pem", "usage": "verify"}]
        client["outbounds"].append(outbound)
        client["inbounds"].append({"tag": tag, "listen": "127.0.0.1", "port": 1080 + index,
                                   "protocol": "socks", "settings": {"auth": "noauth"}})
        client["routing"]["rules"].append({"type": "field", "inboundTag": [tag], "outboundTag": tag})
    config_file = runtime.store.system / "qa-client.json"
    write(config_file, json.dumps(client))
    image = runtime.config["images"]["xui"]
    architecture = run(["docker", "image", "inspect", image, "--format", "{{.Architecture}}"], capture=True).stdout.strip()
    assert architecture in ("amd64", "arm64")
    name = runtime.project + "-client"
    created = False
    try:
        run(["docker", "run", "-d", "--name", name, "--label", "xiaoruru.qa=" + runtime.project,
             "--network", runtime.config["network"]["name"],
             "-v", str(config_file) + ":/qa-client.json:ro",
             "-v", str(runtime.store.data / f"gateway/certificates/live/{CERT_NAME}") + ":/qa-ca:ro",
             "--entrypoint", "/app/bin/xray-linux-" + architecture, image,
             "run", "-c", "/qa-client.json"], capture=True)
        created = True
        for index, outbound in enumerate(outbounds):
            result = run(["docker", "exec", name, "curl", "--retry", "2", "--retry-all-errors",
                          "--connect-timeout", "10", "--max-time", "20", "--noproxy", "",
                          "--proxy", "socks5h://127.0.0.1:" + str(1080 + index), "-fsS", "-o", "/dev/null",
                          "-w", "%{http_code}", "https://example.com/"], capture=True, check=False, timeout=90)
            assert result.returncode == 0 and result.stdout == "200", f"{outbound['protocol']} tunnel request failed"
        print("PASS: generated VLESS Reality and Hysteria2 subscriptions both proxy HTTPS requests.", flush=True)
    finally:
        if created:
            info = json.loads(run(["docker", "inspect", name], capture=True).stdout)[0]
            assert info["Config"]["Labels"]["xiaoruru.qa"] == runtime.project
            run(["docker", "rm", "-f", name], capture=True)


def main():
    token = "xiaoruru-lifecycle-qa-" + uuid.uuid4().hex[:10]
    with fixture() as (store, _):
        config = store.load()
        config["enabled"] = ["vpn", "blog"]
        config["gost"] = True
        config["domains"] = {key: key.replace("_", "-") + ".xiaoruru.invalid" for key in config["domains"]}
        config["network"]["name"] = token + "-net"
        config["network"]["subnet"] = "172.29.253.0/24"
        store.prepare(config)
        store.save(config)
        shutil.copytree(ROOT / "manager/runtime", store.root / "manager/runtime")
        compose = (store.root / "compose.yaml").read_text()
        compose = compose.replace('ports: ["80:80", "443:443"]', 'ports: ["127.0.0.1::80", "127.0.0.1::443"]')
        compose = compose.replace('ports: ["8443:8443/tcp", "8443:8443/udp"]', 'ports: []')
        compose = compose.replace('ports: ["9443:9443"]', 'ports: ["127.0.0.1::9443"]')
        write(store.root / "compose.yaml", compose)
        runtime = LocalRuntime(store)
        runtime.project = token
        runtime.cpa_project = token + "-cpa"
        network_id = None
        try:
            runtime.preflight()
            runtime.network()
            network_id = json.loads(run(["docker", "network", "inspect", config["network"]["name"]], capture=True).stdout)[0]["Id"]
            runtime.start("vpn")
            check_tunnels(runtime)
            first_access = (store.data / "vpn/output/access.json").read_bytes()
            first_ids = {row["service"]: row["ID"] for row in runtime.containers()}
            first_started = {row["ID"]: json.loads(run(["docker", "inspect", row["ID"]], capture=True).stdout)[0]["State"]["StartedAt"]
                             for row in runtime.containers()}
            runtime.start("vpn")
            assert first_access == (store.data / "vpn/output/access.json").read_bytes()
            assert first_ids == {row["service"]: row["ID"] for row in runtime.containers()}
            assert first_started == {row["ID"]: json.loads(run(["docker", "inspect", row["ID"]], capture=True).stdout)[0]["State"]["StartedAt"]
                                     for row in runtime.containers()}, "Repeated start must not restart unchanged services"
            # Directory bind plus config digest must recreate GOST only when configuration changes.
            credentials = json.loads((store.system / "secrets.json").read_text())
            credentials["gost_password"] = "isolated-test-password-" + uuid.uuid4().hex
            write(store.system / "secrets.json", json.dumps(credentials))
            store.prepare(config)
            runtime.start("vpn")
            next_ids = {row["service"]: row["ID"] for row in runtime.containers()}
            assert next_ids["gost"] != first_ids["gost"] and next_ids["xui"] == first_ids["xui"]
            # Exercise actual Certbot-container permissions on the challenge directory.
            runtime.compose("run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "certbot", "-c",
                            "umask 022; mkdir -p /var/www/certbot/.well-known/acme-challenge; "
                            "printf local-acme-ok > /var/www/certbot/.well-known/acme-challenge/qa-token")
            info = json.loads(run(["docker", "inspect", next_ids["nginx-ui"]], capture=True).stdout)[0]
            port = int(info["NetworkSettings"]["Ports"]["80/tcp"][0]["HostPort"])
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
            connection.request("GET", "/.well-known/acme-challenge/qa-token", headers={"Host": config["domains"]["blog"]})
            response = connection.getresponse()
            assert response.status == 200 and response.read() == b"local-acme-ok"
            connection.close()
            runtime.start("blog")
            assert runtime.running("blog") and not runtime.running("cpa")
            runtime.stop("blog")
            runtime.stop("blog")
            assert runtime.running("gateway")
            # Invalid generated site must not poison the previously valid configuration.
            original = runtime.render_site
            runtime.render_site = lambda name: "invalid nginx directive;"
            try:
                runtime.sites("blog", replace=True)
                raise AssertionError("Invalid Nginx configuration was accepted")
            except StackError:
                pass
            finally:
                runtime.render_site = original
            runtime.reload()
            # An unrelated user configuration error must not block stop or mutate config data.
            broken = store.data / "gateway/nginx/conf.d/qa-broken.conf"
            write(broken, "invalid nginx directive;", 0o644)
            runtime.stop("vpn")
            assert not runtime.running("vpn")
            broken.unlink()
            runtime.start("vpn")
            assert first_access == (store.data / "vpn/output/access.json").read_bytes()
            runtime.restart("all")
            runtime.stop("all")
            runtime.stop("all")
            assert not any(row.get("State") == "running" for row in runtime.containers())
            print("PASS: fresh manager deployment; repeat start/stop; VPN credentials; GOST config reload; "
                  "ACME permissions; blog without CPA; invalid Nginx recovery; restart all.")
        finally:
            rows = runtime.containers()
            for row in rows:
                info = json.loads(run(["docker", "inspect", row["ID"]], capture=True).stdout)[0]
                assert info["Config"]["Labels"]["com.docker.compose.project"] == token
                run(["docker", "rm", "-f", row["ID"]], capture=True)
            if network_id:
                info = json.loads(run(["docker", "network", "inspect", network_id], capture=True).stdout)[0]
                assert info["Name"] == token + "-net" and not info["Containers"]
                run(["docker", "network", "rm", network_id], capture=True)


if __name__ == "__main__":
    main()
