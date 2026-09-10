"""Run the built JAR with Compose's small-memory settings and isolated temporary data."""
from concurrent.futures import ThreadPoolExecutor
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]


def main():
    blog_env = {
        "BLOG_COOKIE_SECURE": "true", "SERVER_TOMCAT_THREADS_MAX": "16",
        "SERVER_TOMCAT_THREADS_MIN_SPARE": "2",
        "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "3",
        "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE": "1",
        "JAVA_TOOL_OPTIONS": "-Xms48m -Xmx224m -XX:MaxMetaspaceSize=128m "
            "-XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=32m -Xss512k "
            "-XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1 "
            "-XX:+ExitOnOutOfMemoryError",
    }
    with tempfile.TemporaryDirectory(prefix="xiaoruru-blog-smoke-") as temporary:
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        secret = secrets.token_hex(24)
        env = {**os.environ, **blog_env, "BLOG_DATA_DIR": temporary + "/data",
               "SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": str(port),
               "BLOG_ADMIN_SECRET": secret, "BLOG_DB_PASSWORD": secrets.token_hex(24),
               "BLOG_BACKUP_ENABLED": "false",
               "SPRING_CONFIG_LOCATION": "classpath:/application.yml"}

        def request(path, method="GET", body=None, headers=None):
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=20)
            conn.request(method, path, body=body, headers={"Host": "xiaoruru.beer",
                "X-Forwarded-Proto": "https", "X-Forwarded-Host": "xiaoruru.beer", **(headers or {})})
            response = conn.getresponse()
            result = response.status, dict(response.getheaders()), response.read().decode()
            conn.close()
            return result

        with open(temporary + "/runtime.log", "w+") as log:
            process = subprocess.Popen(["java", "-jar", str(ROOT / "apps/blog/target/rurublog.jar")],
                                       cwd=temporary, env=env, stdout=log, stderr=subprocess.STDOUT)
            try:
                started = time.monotonic()
                while time.monotonic() - started < 90:
                    if process.poll() is not None:
                        raise RuntimeError("Blog exited during startup")
                    try:
                        if request("/actuator/health")[0] == 200:
                            break
                    except (OSError, http.client.HTTPException):
                        pass
                    time.sleep(0.3)
                else:
                    raise RuntimeError("Blog startup timed out")
                startup = time.monotonic() - started
                assert request("/")[0] == 200
                health_status, _, health_body = request("/actuator/health")
                assert health_status == 200 and '"status":"UP"' in health_body
                status, headers, body = request("/admin/login")
                assert status == 200
                csrf = re.search(r'name="_csrf"[^>]*value="([^"]+)"', body).group(1)
                cookie = headers["Set-Cookie"].split(";", 1)[0]
                assert "Secure" in headers["Set-Cookie"]
                status, headers, _ = request("/admin/login", "POST", urllib.parse.urlencode(
                    {"username": "admin", "password": secret, "_csrf": csrf}),
                    {"Cookie": cookie, "Content-Type": "application/x-www-form-urlencoded"})
                assert status == 302 and headers["Location"].endswith("/admin"), (status, headers)
                admin_cookie = headers.get("Set-Cookie", cookie).split(";", 1)[0]
                assert request("/admin", headers={"Cookie": admin_cookie})[0] == 200
                for asset in ("/webjars/highlightjs__cdn-assets/11.11.1/highlight.min.js",
                              "/webjars/katex/0.16.44/dist/katex.min.css",
                              "/webjars/mermaid/11.17.1/dist/mermaid.min.js"):
                    assert request(asset)[0] == 200, asset
                status, _, settings_page = request("/admin/ai-settings", headers={"Cookie": admin_cookie})
                assert status == 200 and "关闭 AI" in settings_page and "Docker 内网" in settings_page
                with ThreadPoolExecutor(max_workers=4) as pool:
                    statuses = list(pool.map(lambda _: request("/")[0], range(100)))
                assert all(status == 200 for status in statuses)
                rss_kib = int(subprocess.check_output(["ps", "-o", "rss=", "-p", str(process.pid)]))
                print(json.dumps({"startup_seconds": round(startup, 1), "homepage_requests": len(statuses),
                    "concurrency": 4, "admin_login": "passed", "secure_cookie": "passed",
                    "rss_mib": round(rss_kib / 1024, 1), "platform": os.uname().sysname}, ensure_ascii=False))
            except Exception:
                log.flush()
                log.seek(0)
                print(log.read()[-6000:])
                raise
            finally:
                process.terminate()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    main()
