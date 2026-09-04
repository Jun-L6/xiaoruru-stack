"""Local-only deployment tests: isolated files, fake Docker, no ACME/server access."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE_DOMAINS = ["3x-ui.xiaoruru.beer", "nginx-ui.xiaoruru.beer", "cpa-mp.xiaoruru.beer",
                "cli-proxy-api.xiaoruru.beer", "www.xiaoruru.beer"]
MOCK_DOCKER = r'''#!/usr/bin/env python3
import json, os, pathlib, shutil, sys
args = sys.argv[1:]
root = pathlib.Path.cwd()
with (root / "docker-calls.jsonl").open("a") as f:
    f.write(json.dumps(args) + "\n")
if "certonly" in args:
    if os.environ.get("CERTBOT_FAIL") == "1":
        sys.exit(2)
    dest = root / "data/letsencrypt/live/xiaoruru-services/fullchain.pem"
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / "issued.pem", dest)
if "ps" in args:
    print("nginx-ui\nxui\ngost")
'''


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="xiaoruru-deployment-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copytree(ROOT / "scripts", self.root / "scripts")
        shutil.copytree(ROOT / "config", self.root / "config",
                        ignore=shutil.ignore_patterns("application.yml", "application.yaml"))
        shutil.copyfile(ROOT / "compose.yaml", self.root / "compose.yaml")
        self.uid = 10001 if os.getuid() == 0 else os.getuid()
        self.gid = 10001 if os.getuid() == 0 else os.getgid()
        example = (ROOT / ".env.example").read_text().replace("change_me", "test_only")
        (self.root / ".env").write_text(example.replace("BLOG_UID=10001", f"BLOG_UID={self.uid}")
                                       .replace("BLOG_GID=10001", f"BLOG_GID={self.gid}"))
        (self.root / "bin").mkdir()
        mock = self.root / "bin/docker"
        mock.write_text(MOCK_DOCKER)
        mock.chmod(0o755)
        self.env = {**os.environ, "PATH": f"{self.root / 'bin'}:{os.environ['PATH']}",
                    "BLOG_UID": str(self.uid), "BLOG_GID": str(self.gid)}
        nginx = self.root / "data/nginx"
        nginx.mkdir(parents=True)
        (nginx / "nginx.conf").write_text("events {}\nhttp { include sites-enabled/*; }\n")
        self.certificate = self.root / "data/letsencrypt/live/xiaoruru-services/fullchain.pem"

    def run_script(self, name, *args, success=True):
        command = ["python3" if name.endswith(".py") else "bash", f"scripts/{name}", *args]
        result = subprocess.run(command, cwd=self.root, env=self.env, text=True, capture_output=True, umask=0o077)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def cert(self, target, domains):
        config = self.root / "openssl.cnf"
        config.write_text("[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n"
                          "[dn]\nCN=xiaoruru.beer\n[ext]\nsubjectAltName=" +
                          ",".join("DNS:" + name for name in domains) + "\n")
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                        "-config", str(config), "-keyout", str(self.root / "test.key"), "-out", str(target)],
                       check=True, capture_output=True)

    def calls(self):
        path = self.root / "docker-calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def test_first_certificate_contains_six_domains(self):
        self.cert(self.root / "issued.pem", BASE_DOMAINS + ["xiaoruru.beer"])
        self.run_script("ensure-certificate.sh")
        calls = self.calls()
        issue = next(c for c in calls if "certonly" in c)
        names = [issue[i + 1] for i, arg in enumerate(issue) if arg == "--domain"]
        self.assertEqual(set(names), set(BASE_DOMAINS + ["xiaoruru.beer"]))
        self.assertIn("--expand", issue)
        self.assertTrue((self.root / "data/nginx/sites-enabled/00-bootstrap.conf").is_symlink())
        self.assertFalse(any("restart" in c for c in calls))

    def test_expand_existing_preserves_extra_san_and_live_sites(self):
        existing = BASE_DOMAINS + ["extra.xiaoruru.beer"]
        self.cert(self.certificate, existing)
        self.cert(self.root / "issued.pem", existing + ["xiaoruru.beer"])
        self.run_script("ensure-certificate.sh")
        calls = self.calls()
        issue = next(c for c in calls if "certonly" in c)
        self.assertIn("extra.xiaoruru.beer", issue)
        self.assertFalse((self.root / "data/nginx/sites-enabled/00-bootstrap.conf").exists())
        self.assertIn(["compose", "restart", "gost"], calls)
        self.assertIn(["compose", "restart", "xui"], calls)

    def test_existing_six_domain_certificate_is_not_reissued(self):
        self.cert(self.certificate, BASE_DOMAINS + ["xiaoruru.beer"])
        self.run_script("ensure-certificate.sh")
        self.assertFalse(any("certonly" in c or "restart" in c for c in self.calls()))

    def test_failed_expansion_keeps_certificate_and_does_not_restart(self):
        self.cert(self.certificate, BASE_DOMAINS)
        before = self.certificate.read_bytes()
        self.env["CERTBOT_FAIL"] = "1"
        self.run_script("ensure-certificate.sh", success=False)
        self.assertEqual(before, self.certificate.read_bytes())
        self.assertFalse(any("restart" in c for c in self.calls()))

    def test_incomplete_issued_certificate_is_rejected(self):
        self.cert(self.certificate, BASE_DOMAINS)
        shutil.copyfile(self.certificate, self.root / "issued.pem")
        self.run_script("ensure-certificate.sh", success=False)
        self.assertFalse(any("restart" in c for c in self.calls()))

    def test_blog_renderer_preserves_ui_changes_and_other_sites(self):
        self.run_script("render-nginx-sites.sh", "final")
        site = self.root / "data/nginx/sites-available/50-rurublog.conf"
        initial = site.read_text()
        self.assertIn("server_name xiaoruru.beer;", initial)
        self.assertIn("set $blog_backend rurublog:8080;", initial)
        self.assertIn("/live/xiaoruru-services/fullchain.pem", initial)
        self.assertNotIn("__", initial)
        site.write_text(initial + "\n# user edit\n")
        other = self.root / "data/nginx/sites-enabled/20-3x-ui.conf"
        other_before = other.lstat().st_ino
        self.run_script("render-nginx-sites.sh", "blog")
        self.assertTrue(site.read_text().endswith("# user edit\n"))
        self.assertEqual(other_before, other.lstat().st_ino)

    def test_init_credentials_are_private_and_idempotent(self):
        self.run_script("init-blog.py")
        path = self.root / "config/rurublog/application.yml"
        first = path.read_bytes()
        self.assertNotIn(b"__ADMIN_SECRET__", first)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.run_script("init-blog.py")
        self.assertEqual(path.read_bytes(), first)

    def test_init_refuses_symlink(self):
        (self.root / "data/rurublog").symlink_to(self.root / "data", target_is_directory=True)
        self.run_script("init-blog.py", success=False)

    def test_init_refuses_replacing_password_for_existing_database(self):
        database = self.root / "data/rurublog/database"
        database.mkdir(parents=True)
        (database / "blog.mv.db").write_text("existing data")
        self.run_script("init-blog.py", success=False)
        self.assertFalse((self.root / "config/rurublog/application.yml").exists())

    def test_incremental_deployment_never_runs_panel_initialization(self):
        self.cert(self.certificate, BASE_DOMAINS + ["xiaoruru.beer"])
        self.run_script("deploy-blog.sh")
        calls = self.calls()
        self.assertTrue(any("up" in c and "rurublog" in c and "--no-build" in c for c in calls))
        self.assertFalse(any("panel-init" in c or "restart" in c or "stop" in c for c in calls))
        self.assertTrue((self.root / "data/nginx/sites-enabled/50-rurublog.conf").is_symlink())

    def test_blog_health_failure_does_not_request_certificate_or_enable_site(self):
        mock = self.root / "bin/docker"
        mock.write_text(MOCK_DOCKER + '\nif "up" in args and "rurublog" in args:\n    sys.exit(3)\n')
        self.run_script("deploy-blog.sh", success=False)
        self.assertFalse(any("certonly" in c for c in self.calls()))
        self.assertFalse((self.root / "data/nginx/sites-enabled/50-rurublog.conf").exists())


if __name__ == "__main__":
    unittest.main()
