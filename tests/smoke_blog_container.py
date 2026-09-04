"""Smoke-test the built blog image as its non-root user, without CPA or persistent test data."""
import http.client
import json
import secrets
import time
import uuid
from smoke_gateway import docker


def main():
    name = "xiaoruru-blog-qa-" + uuid.uuid4().hex[:12]
    docker("run", "-d", "--name", name, "--label", "xiaoruru.qa=" + name,
           "--read-only", "--memory", "768m", "--cpus", "1", "--pids-limit", "128",
           "--tmpfs", "/tmp:size=64m,mode=1777",
           "--tmpfs", "/data:size=128m,uid=10001,gid=10001,mode=750",
           "-e", "BLOG_ADMIN_SECRET=" + secrets.token_hex(24),
           "-e", "BLOG_BACKUP_ENABLED=false",
           "-e", "SERVER_TOMCAT_THREADS_MAX=32", "-e", "SERVER_TOMCAT_THREADS_MIN_SPARE=4",
           "-e", "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4",
           "-e", "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=1",
           "-p", "127.0.0.1::8080", "xiaoruru/rurublog:local")
    try:
        info = json.loads(docker("inspect", name))[0]
        port = int(info["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"])
        for _ in range(90):
            try:
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                conn.request("GET", "/actuator/health")
                response = conn.getresponse()
                status = response.status
                response.read()
                conn.close()
                if status == 200:
                    break
            except OSError:
                pass
            time.sleep(1)
        else:
            raise RuntimeError("Blog container did not become healthy")
        assert docker("exec", name, "id", "-u").strip() == "10001"
        for path in ("/", "/admin/login"):
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
            conn.request("GET", path)
            response = conn.getresponse()
            assert response.status == 200
            response.read()
            conn.close()
        print("Blog image healthy without CPA; UID 10001; read-only root; 768 MiB memory limit; homepage/login 200.")
    finally:
        info = json.loads(docker("inspect", name))[0]
        if info["Config"]["Labels"].get("xiaoruru.qa") != name:
            raise RuntimeError("Container ownership mismatch")
        docker("rm", "-f", name)


if __name__ == "__main__":
    main()
