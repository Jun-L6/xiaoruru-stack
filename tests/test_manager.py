"""Unit and real Compose-rendering tests; never start containers or request certificates."""
import argparse
from contextlib import contextmanager
import copy
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from manager.__main__ import execute, lock
from manager.runtime import Runtime, clean_env
from manager.storage import ROOT, StackError, Store, write


@contextmanager
def fixture():
    with tempfile.TemporaryDirectory(prefix="xiaoruru-manager-test-") as temporary:
        root = Path(temporary).resolve()
        shutil.copytree(ROOT / "config", root / "config")
        shutil.copyfile(ROOT / "compose.yaml", root / "compose.yaml")
        store = Store(root)
        config = store.defaults()
        config["email"] = "admin@example.com"
        store.prepare(config)
        store.save(config)
        yield store, Runtime(store)


class StorageTest(unittest.TestCase):
    def test_repeat_init_preserves_all_credentials_and_data(self):
        with fixture() as (store, runtime):
            secret = (store.system / "secrets.json").read_bytes()
            blog = (store.data / "blog/config/application.yml").read_bytes()
            (store.data / "vpn/xui/sentinel").write_text("user data")
            store.prepare(runtime.config)
            self.assertEqual(secret, (store.system / "secrets.json").read_bytes())
            self.assertEqual(blog, (store.data / "blog/config/application.yml").read_bytes())
            self.assertEqual(0o600, (store.system / "secrets.json").stat().st_mode & 0o777)

    def test_symlink_rejected_without_touching_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / "real").mkdir()
            (root / "link").symlink_to(root / "real")
            with self.assertRaises(StackError):
                write(root / "link/private", "should not write")
            self.assertFalse((root / "real/private").exists())

    def test_domains_only_selected_modules(self):
        with fixture() as (store, runtime):
            config = runtime.config
            config["enabled"] = ["blog"]
            self.assertEqual(store.domains(config),
                             ["nginx-ui.xiaoruru.beer", "xiaoruru.beer"])

    def test_bad_domain_and_network_injection_rejected(self):
        with fixture() as (store, runtime):
            for value in ("a.com; return 200", "https://a.com", "a..com"):
                config = copy.deepcopy(runtime.config)
                config["domains"]["blog"] = value
                with self.assertRaises(StackError):
                    store.save(config)
            config["domains"]["blog"] = "blog.example.com"
            config["network"]["name"] = "$(whoami)"
            with self.assertRaises(StackError):
                store.save(config)

    def test_concurrent_mutations_fail_fast(self):
        with fixture() as (store, runtime):
            with lock(store):
                with self.assertRaises(StackError):
                    with lock(store):
                        self.fail()

    def test_status_does_not_initialize(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = Store(Path(temporary))
            with patch("sys.stdout", new_callable=io.StringIO):
                execute(argparse.Namespace(command="status", module=None), store)
            self.assertFalse(store.data.exists())


class LifecycleTest(unittest.TestCase):
    def test_blog_starts_gateway_without_cpa(self):
        with fixture() as (_, runtime):
            runtime.gateway = Mock()
            runtime.up = Mock()
            runtime.sites = Mock()
            runtime.official_installer = Mock()
            with patch("manager.runtime.run", return_value=subprocess.CompletedProcess([], 0)):
                runtime.start("blog")
            runtime.gateway.assert_called_once()
            runtime.up.assert_called_once_with(["rurublog"])
            runtime.official_installer.assert_not_called()

    def test_gateway_failure_prevents_business_start(self):
        with fixture() as (_, runtime):
            runtime.gateway = Mock(side_effect=StackError("gateway unhealthy"))
            runtime.up = Mock()
            with self.assertRaises(StackError):
                runtime.start("blog")
            runtime.up.assert_not_called()

    def test_stop_gateway_refuses_running_business(self):
        with fixture() as (_, runtime):
            runtime.running = Mock(return_value=True)
            runtime.compose = Mock()
            with self.assertRaises(StackError):
                runtime.stop("gateway")
            runtime.compose.assert_not_called()

    def test_stop_all_still_works_with_broken_nginx(self):
        with fixture() as (_, runtime):
            runtime.cpa_installed = Mock(return_value=True)
            runtime.compose = Mock()
            runtime.stop("all")
            self.assertEqual(2, runtime.compose.call_count)
            self.assertFalse(any("down" in call.args for call in runtime.compose.call_args_list))

    def test_repeat_vpn_start_skips_initialization(self):
        with fixture() as (store, runtime):
            store.mark("vpn_admin_ready")
            store.mark("vpn_ready")
            runtime.gateway = Mock()
            runtime.up = Mock()
            runtime.compose = Mock()
            runtime.sites = Mock()
            runtime.start("vpn")
            runtime.start("vpn")
            self.assertFalse(any("run" in c.args for c in runtime.compose.call_args_list))

    def test_start_all_does_not_upgrade_existing_cpa(self):
        with fixture() as (_, runtime):
            runtime.config["enabled"] = ["cpa"]
            runtime.gateway = Mock()
            runtime.cpa_override = Mock()
            runtime.cpa_installed = Mock(return_value=True)
            runtime.up = Mock()
            runtime.sites = Mock()
            runtime.official_installer = Mock()
            runtime.start("all")
            runtime.up.assert_called_once_with(["cli-proxy-api", "cpa-manager-plus"], cpa=True)
            runtime.official_installer.assert_not_called()

    def test_running_cpa_enters_official_installer(self):
        with fixture() as (_, runtime):
            runtime.gateway = Mock()
            runtime.cpa_override = Mock()
            runtime.cpa_installed = Mock(return_value=True)
            runtime.running = Mock(return_value=True)
            runtime.sites = Mock()
            runtime.official_installer = Mock(return_value=True)
            runtime.start("cpa")
            runtime.official_installer.assert_called_once()

    def test_official_cancel_does_not_enable_site(self):
        with fixture() as (_, runtime):
            runtime.gateway = Mock()
            runtime.cpa_override = Mock()
            runtime.cpa_installed = Mock(return_value=False)
            runtime.sites = Mock()
            runtime.official_installer = Mock(return_value=False)
            runtime.start("cpa")
            runtime.sites.assert_not_called()

    def test_renewal_does_not_start_stopped_gateway(self):
        with fixture() as (_, runtime):
            runtime.running = Mock(return_value=False)
            runtime.certificate = Mock()
            runtime.renew()
            runtime.certificate.assert_not_called()

    def test_sites_keep_user_edits(self):
        with fixture() as (store, runtime):
            nginx = store.data / "gateway/nginx"
            (nginx / "sites-enabled").mkdir()
            runtime.reload = Mock()
            runtime.sites("blog")
            target = nginx / "sites-available/50-rurublog.conf"
            target.write_text("# User edited site\n")
            runtime.sites("blog")
            self.assertEqual("# User edited site\n", target.read_text())
            runtime.sites("blog", False)
            self.assertTrue(target.exists())
            self.assertFalse((nginx / "sites-enabled/50-rurublog.conf").exists())

    def test_external_compose_environment_is_not_inherited(self):
        with patch.dict(os.environ, COMPOSE_FILE="/other/compose.yaml", CPAMP_OPERATION="upgrade"):
            self.assertNotIn("COMPOSE_FILE", clean_env())
            self.assertNotIn("CPAMP_OPERATION", clean_env())


@unittest.skipUnless(shutil.which("docker"), "Docker Compose CLI is not installed")
class ComposeTest(unittest.TestCase):
    def test_core_services_ports_and_dependency_contract(self):
        with fixture() as (_, runtime):
            merged = json.loads(runtime.compose("config", "--format", "json", capture=True).stdout)
            self.assertEqual(set(merged["services"]),
                {"nginx-ui", "xui", "gost", "rurublog", "certbot", "panel-init"})
            for service in ("xui", "gost", "rurublog"):
                self.assertEqual(merged["services"][service]["depends_on"]["nginx-ui"]["condition"],
                                 "service_healthy")
            self.assertFalse(merged["services"]["rurublog"].get("ports"))
            self.assertEqual(set(merged["services"]["rurublog"]["depends_on"]), {"nginx-ui"})

    def test_official_compose_override_merges_by_mount_target(self):
        with fixture() as (store, runtime):
            # Minimal fixture of the official installer contract, never executed.
            write(runtime.cpa_dir / ".env", "CPA_IMAGE=eceasy/cli-proxy-api:latest\nCPAMP_IMAGE=seakee/cpa-manager-plus:latest\n")
            write(runtime.cpa_dir / "compose.yaml", """services:
  cli-proxy-api:
    image: ${CPA_IMAGE}
    ports: ["8317:8317"]
    volumes:
      - ./cliproxyapi/config.yaml:/CLIProxyAPI/config.yaml
      - ./cliproxyapi/auths:/root/.cli-proxy-api
      - ./cliproxyapi/logs:/CLIProxyAPI/logs
  cpa-manager-plus:
    image: ${CPAMP_IMAGE}
    ports: ["18317:18317"]
    volumes: [cpa-manager-plus-data:/data]
volumes:
  cpa-manager-plus-data: {}
""")
            runtime.cpa_override()
            runtime.validate_cpa()
            merged = json.loads(runtime.compose("config", "--format", "json", cpa=True, capture=True).stdout)
            self.assertFalse(merged["services"]["cli-proxy-api"].get("ports"))
            self.assertFalse(merged["services"]["cpa-manager-plus"].get("ports"))
            self.assertEqual(merged["services"]["cpa-manager-plus"]["volumes"][0]["type"], "bind")
            self.assertEqual(set(merged["services"]["cli-proxy-api"]["networks"]), {"default", "gateway"})


if __name__ == "__main__":
    unittest.main()
