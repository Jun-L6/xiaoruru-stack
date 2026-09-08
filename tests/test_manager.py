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

from manager.__main__ import deployment_credentials, execute, initialize, lock
from manager.runtime import BLOG_SCHEMA_GENERATION, Runtime, blog_source_hash, clean_env
from manager.storage import CERT_NAME, ROOT, StackError, Store, validate, write


@contextmanager
def fixture():
    with tempfile.TemporaryDirectory(prefix="xiaoruru-manager-test-") as temporary:
        root = Path(temporary).resolve()
        shutil.copytree(ROOT / "config", root / "config")
        shutil.copytree(ROOT / "apps/blog", root / "apps/blog",
                        ignore=shutil.ignore_patterns("target", ".idea"))
        shutil.copyfile(ROOT / "compose.yaml", root / "compose.yaml")
        store = Store(root)
        config = store.defaults()
        config["email"] = "admin@example.com"
        store.prepare(config)
        store.save(config)
        yield store, Runtime(store)


class StorageTest(unittest.TestCase):
    def test_credentials_show_enabled_logins_without_database_secret(self):
        with fixture() as (store, _):
            write(store.data / "cpa/secrets/cpamp-admin-key", "cpamp_test_admin\n")
            write(store.data / "cpa/secrets/cpa-management-key", "cpa_test_management\n")
            write(store.data / "cpa/secrets/cpa-demo-client-key", "sk-test-client\n")
            generated = json.loads((store.system / "secrets.json").read_text())
            output = io.StringIO()
            output.isatty = lambda: True
            with patch("sys.stdin.isatty", return_value=True), patch("sys.stdout", output):
                deployment_credentials(store)
            text = output.getvalue()
            self.assertIn(generated["gateway_password"], text)
            self.assertIn(generated["vpn_password"], text)
            self.assertIn(generated["blog_admin"], text)
            self.assertIn("cpamp_test_admin", text)
            self.assertIn("cpa_test_management", text)
            self.assertIn("sk-test-client", text)
            self.assertNotIn(generated["blog_db"], text)
            self.assertIn("用户名：admin", text)

    def test_credentials_refuse_noninteractive_output(self):
        with fixture() as (store, _), patch("sys.stdin.isatty", return_value=False), \
                self.assertRaisesRegex(StackError, "交互终端"):
            deployment_credentials(store)

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
                             ["nginx-ui.xiaoruru.beer", "www.xiaoruru.beer", "xiaoruru.beer"])

    def test_blog_site_redirects_www_to_configured_canonical_domain(self):
        with fixture() as (_, runtime):
            site = runtime.render_site("50-rurublog")
            self.assertIn("server_name xiaoruru.beer www.xiaoruru.beer;", site)
            self.assertIn("server_name www.xiaoruru.beer;", site)
            self.assertEqual(2, site.count("return 301 https://xiaoruru.beer$request_uri;"))
            self.assertNotIn("__BLOG_WWW_DOMAIN__", site)

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

    def test_malformed_configuration_gives_friendly_error(self):
        with fixture() as (_, runtime):
            for key, value in (("enabled", [{}]), ("domains", []), ("email", None),
                               ("network", []), ("images", [])):
                config = copy.deepcopy(runtime.config)
                config[key] = value
                with self.subTest(key=key), self.assertRaises(StackError):
                    validate(config)
            with self.assertRaises(StackError):
                validate([])
            for value in ("127.0.0.0/16", "0.0.0.0/16", "192.0.0.0/24"):
                runtime.config["network"]["subnet"] = value
                with self.assertRaises(StackError):
                    validate(runtime.config)

    def test_single_module_first_init_does_not_enable_unselected_features(self):
        with fixture() as (store, _):
            store.config_path.unlink()
            with patch("sys.stdin.isatty", return_value=True), patch("manager.__main__.ask", side_effect=lambda label, default: "admin@example.com" if "邮箱" in label else default):
                initialize(store, "blog")
            self.assertEqual(["blog"], store.load()["enabled"])

    def test_unchanged_prepare_preserves_inode_and_gost_changes_hash(self):
        with fixture() as (store, runtime):
            path = store.data / "vpn/gost/config.json"
            inode = path.stat().st_ino
            env = (store.system / "compose.env").read_bytes()
            store.prepare(runtime.config)
            self.assertEqual(inode, path.stat().st_ino)
            private = store.system / "secrets.json"
            secrets = json.loads(private.read_text())
            secrets["gost_password"] = "a-new-test-password"
            write(private, json.dumps(secrets))
            store.prepare(runtime.config)
            self.assertNotEqual(env, (store.system / "compose.env").read_bytes())
            self.assertIn("a-new-test-password", path.read_text())


class LifecycleTest(unittest.TestCase):
    def test_ready_host_environment_does_not_run_apt(self):
        with fixture() as (_, runtime):
            def command(args, **kwargs):
                if args[:3] == ["docker", "compose", "version"]:
                    return subprocess.CompletedProcess(args, 0, stdout="2.30.0\n", stderr="")
                if args[:2] == ["docker", "info"]:
                    return subprocess.CompletedProcess(args, 0, stdout="29.0.0\n", stderr="")
                self.fail(f"unexpected command: {args}")

            with patch("manager.runtime.shutil.which", return_value="/usr/bin/tool"), \
                    patch("manager.runtime.run", side_effect=command) as execute_command:
                runtime.ensure_host_environment()
            self.assertFalse(any(call.args[0][0] == "apt-get" for call in execute_command.call_args_list))

    def test_clean_ubuntu_host_installs_official_docker_packages(self):
        with fixture() as (_, runtime), tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            os_release = root / "os-release"
            os_release.write_text('ID="ubuntu"\nVERSION_CODENAME=noble\n')
            installed = False

            def available(name):
                if name == "docker":
                    return "/usr/bin/docker" if installed else None
                return f"/usr/bin/{name}"

            def command(args, **kwargs):
                nonlocal installed
                if args[0] == "dpkg-query":
                    return subprocess.CompletedProcess(args, 1, stdout="", stderr="")
                if args[:2] == ["dpkg", "--print-architecture"]:
                    return subprocess.CompletedProcess(args, 0, stdout="amd64\n", stderr="")
                if args[0] == "apt-get" and "docker-ce" in args:
                    installed = True
                if args[:3] == ["docker", "compose", "version"]:
                    return subprocess.CompletedProcess(args, 0, stdout="2.30.0\n", stderr="")
                if args[:2] == ["docker", "info"]:
                    return subprocess.CompletedProcess(args, 0, stdout="29.0.0\n", stderr="")
                return subprocess.CompletedProcess(args, 0, stdout="", stderr="")

            key = root / "keyrings/xiaoruru-docker.asc"
            source = root / "sources/xiaoruru-docker.sources"
            docker_key = b"-----BEGIN PGP PUBLIC KEY BLOCK-----\ntest\n"
            with patch("manager.runtime.sys.platform", "linux"), \
                    patch("manager.runtime.os.geteuid", return_value=0), \
                    patch("manager.runtime.shutil.which", side_effect=available), \
                    patch("manager.runtime.Path.is_dir", return_value=True), \
                    patch("manager.runtime.run", side_effect=command) as execute_command, \
                    patch("manager.runtime.urllib.request.urlopen") as download:
                download.return_value.__enter__.return_value.read.return_value = docker_key
                runtime.ensure_host_environment(os_release, key, source)
            self.assertTrue(installed)
            self.assertEqual(0o644, key.stat().st_mode & 0o777)
            self.assertEqual(0o755, key.parent.stat().st_mode & 0o777)
            self.assertIn("download.docker.com/linux/ubuntu", source.read_text())
            self.assertTrue(any(call.args[0][0] == "apt-get" and "docker-ce" in call.args[0]
                                for call in execute_command.call_args_list))

    def test_conflicting_docker_packages_are_not_removed(self):
        with fixture() as (_, runtime), tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            os_release = root / "os-release"
            os_release.write_text("ID=ubuntu\nVERSION_CODENAME=noble\n")

            def available(name):
                return None if name == "docker" else f"/usr/bin/{name}"

            def command(args, **kwargs):
                if args[0] == "dpkg-query" and args[-1] == "docker.io":
                    return subprocess.CompletedProcess(args, 0, stdout="install ok installed", stderr="")
                if args[0] == "dpkg-query":
                    return subprocess.CompletedProcess(args, 1, stdout="", stderr="")
                self.fail(f"unexpected modifying command: {args}")

            with patch("manager.runtime.sys.platform", "linux"), \
                    patch("manager.runtime.os.geteuid", return_value=0), \
                    patch("manager.runtime.shutil.which", side_effect=available), \
                    patch("manager.runtime.Path.is_dir", return_value=True), \
                    patch("manager.runtime.run", side_effect=command) as execute_command, \
                    self.assertRaisesRegex(StackError, "docker.io"):
                runtime.ensure_host_environment(os_release, root / "key", root / "source")
            self.assertFalse(any(call.args[0][0] == "apt-get" for call in execute_command.call_args_list))

    def test_swap_policy_matches_enabled_workload(self):
        self.assertEqual(2048, Runtime.recommended_swap_mib(1024, ["blog"]))
        self.assertEqual(1024, Runtime.recommended_swap_mib(3072, ["cpa"]))
        self.assertEqual(0, Runtime.recommended_swap_mib(4096, ["blog", "cpa"]))
        self.assertEqual(1024, Runtime.recommended_swap_mib(1024, ["vpn"]))
        self.assertEqual(0, Runtime.recommended_swap_mib(1536, ["vpn"]))

    def test_existing_swap_is_never_modified(self):
        with fixture() as (_, runtime), tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            meminfo = root / "meminfo"
            meminfo.write_text("MemTotal:        1048576 kB\nSwapTotal:        524288 kB\n")
            with patch("manager.runtime.sys.platform", "linux"), patch("manager.runtime.run") as execute_command:
                runtime.ensure_swap(meminfo, root / "fstab", root / "swapfile")
            execute_command.assert_not_called()
            self.assertFalse((root / "swapfile").exists())

    def test_low_memory_creates_persistent_swap_once(self):
        with fixture() as (_, runtime), tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            meminfo = root / "meminfo"
            fstab = root / "fstab"
            swapfile = root / "swapfile"
            meminfo.write_text("MemTotal:        1048576 kB\nSwapTotal:             0 kB\n")
            fstab.write_text("# test fstab\n")

            def command(args, **kwargs):
                if args[0] in ("fallocate", "dd"):
                    swapfile.write_bytes(b"swap")
                output = f"{swapfile}\n" if args[:2] == ["swapon", "--show=NAME"] else ""
                return subprocess.CompletedProcess(args, 0, stdout=output, stderr="")

            disk = shutil._ntuple_diskusage(10 << 30, 1 << 30, 9 << 30)
            with patch("manager.runtime.sys.platform", "linux"), \
                    patch("manager.runtime.os.geteuid", return_value=0), \
                    patch("manager.runtime.shutil.which", return_value="/usr/bin/tool"), \
                    patch("manager.runtime.shutil.disk_usage", return_value=disk), \
                    patch("manager.runtime.run", side_effect=command) as execute_command:
                runtime.ensure_swap(meminfo, fstab, swapfile)
            self.assertEqual(0o600, swapfile.stat().st_mode & 0o777)
            self.assertEqual(1, fstab.read_text().count(f"{swapfile} none swap sw 0 0"))
            commands = [call.args[0][0] for call in execute_command.call_args_list]
            self.assertIn("mkswap", commands)
            self.assertIn("swapon", commands)

    def test_inactive_swapfile_is_not_overwritten(self):
        with fixture() as (_, runtime), tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            meminfo = root / "meminfo"
            meminfo.write_text("MemTotal:        1048576 kB\nSwapTotal:             0 kB\n")
            (root / "swapfile").write_text("keep me")
            with patch("manager.runtime.sys.platform", "linux"), \
                    patch("manager.runtime.os.geteuid", return_value=0), \
                    self.assertRaisesRegex(StackError, "避免覆盖"):
                runtime.ensure_swap(meminfo, root / "fstab", root / "swapfile")
            self.assertEqual("keep me", (root / "swapfile").read_text())

    def test_deploy_builds_missing_blog_and_runs_final_checks(self):
        with fixture() as (store, _):
            runtime = Mock()
            runtime.config = store.load()
            runtime.blog_image_current.return_value = False
            with patch("manager.__main__.Runtime", return_value=runtime):
                execute(argparse.Namespace(command="deploy", module=None, config=None, yes=False), store)
            runtime.preflight.assert_called_once_with()
            runtime.check_timer_support.assert_called_once_with()
            runtime.build_blog.assert_called_once_with()
            runtime.start.assert_called_once()
            self.assertEqual("all", runtime.start.call_args.args[0])
            runtime.verify_deployment.assert_called_once_with()
            runtime.install_timer.assert_called_once_with()
            runtime.doctor.assert_called_once_with()

    def test_deploy_rejects_module_without_changing_runtime(self):
        with fixture() as (store, _), patch("manager.__main__.Runtime") as runtime:
            with self.assertRaisesRegex(StackError, "不接功能名"):
                execute(argparse.Namespace(command="deploy", module="blog", config=None, yes=False), store)
            runtime.assert_not_called()

    def test_blog_starts_gateway_without_cpa(self):
        with fixture() as (_, runtime):
            runtime.gateway = Mock()
            runtime.up = Mock()
            runtime.sites = Mock()
            runtime.official_installer = Mock()
            runtime.image_exists = Mock(return_value=True)
            runtime.blog_image_current = Mock(return_value=True)
            runtime.ensure_blog_schema_compatible = Mock()
            runtime.start("blog")
            runtime.gateway.assert_called_once()
            runtime.up.assert_called_once_with(["rurublog"])
            runtime.official_installer.assert_not_called()
            self.assertEqual(BLOG_SCHEMA_GENERATION, runtime.store.state()["blog_schema_generation"])

    def test_blog_source_hash_changes_with_build_input(self):
        with fixture() as (store, _):
            before = blog_source_hash(store.root)
            source = store.root / "apps/blog/src/main/resources/application.yml"
            source.write_text(source.read_text() + "\n# source hash test\n")
            self.assertNotEqual(before, blog_source_hash(store.root))

    def test_incompatible_blog_schema_requires_explicit_reset(self):
        with fixture() as (store, runtime):
            database = store.data / "blog/runtime/database"
            database.mkdir()
            (database / "blog.mv.db").write_text("incompatible")
            with self.assertRaisesRegex(StackError, "reset blog"):
                runtime.ensure_blog_schema_compatible()
            store.mark("blog_schema_generation", BLOG_SCHEMA_GENERATION)
            runtime.ensure_blog_schema_compatible()

    def test_reset_blog_removes_only_blog_data_and_recreates_config(self):
        with fixture() as (store, runtime):
            (store.data / "blog/runtime/article.txt").write_text("delete")
            (store.data / "vpn/xui/keep.txt").write_text("keep")
            runtime.compose = Mock()
            runtime.running = Mock(return_value=False)
            runtime.reset_blog()
            runtime.compose.assert_called_once_with("stop", "rurublog", capture=True, timeout=180)
            self.assertFalse((store.data / "blog/runtime/article.txt").exists())
            self.assertTrue((store.data / "blog/config/application.yml").is_file())
            self.assertTrue((store.data / "vpn/xui/keep.txt").is_file())
            self.assertIsNone(store.state()["blog_schema_generation"])

    def test_reset_blog_requires_two_confirmations_or_yes_flag(self):
        with fixture() as (store, _):
            runtime = Mock()
            with patch("manager.__main__.Runtime", return_value=runtime), \
                    patch("manager.__main__.yes", side_effect=[True, False]) as confirm, \
                    patch("sys.stdin.isatty", return_value=True):
                execute(argparse.Namespace(command="reset", module="blog", config=None, yes=False), store)
            self.assertEqual(2, confirm.call_count)
            runtime.reset_blog.assert_not_called()
            runtime.reset_mock()
            with patch("manager.__main__.Runtime", return_value=runtime):
                execute(argparse.Namespace(command="reset", module="blog", config=None, yes=True), store)
            runtime.preflight.assert_called_once_with()
            runtime.reset_blog.assert_called_once_with()

    def test_gateway_failure_prevents_business_start(self):
        with fixture() as (_, runtime):
            runtime.gateway = Mock(side_effect=StackError("gateway unhealthy"))
            runtime.up = Mock()
            with self.assertRaises(StackError):
                runtime.start("blog")
            runtime.up.assert_not_called()

    def test_stop_gateway_refuses_running_business(self):
        with fixture() as (_, runtime):
            runtime.containers = Mock(return_value=[{"project": "xiaoruru", "service": "rurublog", "State": "restarting"}])
            runtime.compose = Mock()
            with self.assertRaises(StackError):
                runtime.stop("gateway")
            runtime.compose.assert_not_called()

    def test_stop_all_still_works_with_broken_nginx(self):
        with fixture() as (_, runtime):
            runtime.compose = Mock()
            runtime.containers = Mock(return_value=[{"project": "xiaoruru", "service": "nginx-ui",
                                                     "State": "running", "ID": "a" * 12}])
            with patch("manager.runtime.run") as run:
                runtime.stop("all")
            runtime.compose.assert_not_called()
            self.assertEqual(["docker", "stop", "--timeout", "30", "a" * 12], run.call_args.args[0])

    def test_repeat_vpn_start_skips_initialization(self):
        with fixture() as (store, runtime):
            store.mark("vpn_admin_ready")
            store.mark("vpn_ready")
            write(store.data / "vpn/xui/x-ui.db", "test-database")
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
            runtime.cpa_ready = Mock(return_value=True)
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
            runtime.cpa_ready = Mock(return_value=True)
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
        with patch.dict(os.environ, COMPOSE_FILE="/other/compose.yaml", CPAMP_OPERATION="upgrade",
                        GATEWAY_NETWORK="wrong", BLOG_IMAGE="wrong", CPA_IMAGE="wrong", DOCKER_HOST="keep"):
            for key in ("COMPOSE_FILE", "CPAMP_OPERATION", "GATEWAY_NETWORK", "BLOG_IMAGE", "CPA_IMAGE"):
                self.assertNotIn(key, clean_env())
            self.assertEqual("keep", clean_env()["DOCKER_HOST"])

    def test_invalid_or_cancelled_commands_do_not_prepare_data(self):
        with fixture() as (store, _), patch.object(Store, "prepare") as prepare, patch.object(Runtime, "preflight") as preflight:
            with self.assertRaises(StackError):
                execute(argparse.Namespace(command="start", module="unknown", yes=False), store)
            with patch("sys.stdin.isatty", return_value=False):
                execute(argparse.Namespace(command="stop", module="all", yes=False), store)
            prepare.assert_not_called()
            preflight.assert_not_called()

    def test_stop_does_not_run_preflight(self):
        with fixture() as (store, _), patch.object(Runtime, "stop") as stop, patch.object(Runtime, "preflight") as preflight:
            execute(argparse.Namespace(command="stop", module="blog", yes=False), store)
            stop.assert_called_once_with("blog")
            preflight.assert_not_called()

    def test_failed_site_validation_restores_files_and_links(self):
        with fixture() as (store, runtime):
            nginx = store.data / "gateway/nginx"
            (nginx / "sites-enabled").mkdir()
            runtime.reload = Mock()
            runtime.sites("blog")
            target = nginx / "sites-available/50-rurublog.conf"
            target.write_text("# Original user configuration\n")
            runtime.reload.side_effect = StackError("invalid configuration")
            with self.assertRaises(StackError):
                runtime.sites("blog", replace=True)
            self.assertEqual("# Original user configuration\n", target.read_text())
            self.assertTrue((nginx / "sites-enabled/50-rurublog.conf").is_symlink())
            with self.assertRaises(StackError):
                runtime.sites("cpa")
            self.assertFalse((nginx / "sites-enabled/30-cpa-manager-plus.conf").exists())
            self.assertFalse((nginx / "sites-available/30-cpa-manager-plus.conf").exists())

    def test_certificate_application_resumes_after_restart_failure(self):
        with fixture() as (store, runtime):
            live = store.data / f"gateway/certificates/live/{CERT_NAME}"
            write(live / "fullchain.pem", "test certificate")
            write(live / "privkey.pem", "test key")
            runtime.reload = Mock()
            runtime.containers = Mock(return_value=[{"project": "xiaoruru", "service": "xui", "State": "running"}])
            runtime.compose = Mock(side_effect=StackError("restart interrupted"))
            runtime.up = Mock()
            with self.assertRaises(StackError):
                runtime.apply_certificate()
            self.assertNotIn("xui", store.state()["certificate_applied"])
            runtime.compose.side_effect = None
            runtime.apply_certificate()
            runtime.compose.assert_called_with("restart", "xui")
            self.assertIn("xui", store.state()["certificate_applied"])
            runtime.compose.reset_mock()
            runtime.apply_certificate()
            runtime.compose.assert_not_called()
            runtime.reload.assert_called_once()

    def test_first_vpn_start_sets_credentials_before_starting_panel(self):
        with fixture() as (_, runtime):
            events = []
            runtime.gateway = Mock()
            runtime.sites = Mock()
            runtime.compose = Mock(side_effect=lambda *args, **kwargs: events.append(args))
            runtime.up = Mock(side_effect=lambda services: events.append(("up", *services)))
            runtime.start("vpn")
            first_up = events.index(("up", "xui"))
            self.assertTrue(any("ADMIN_USER" in args for args in events[:first_up]))

    def test_restart_fresh_vpn_initializes_and_disabled_restart_has_no_gateway_effect(self):
        with fixture() as (_, runtime):
            runtime.start = Mock()
            runtime.gateway = Mock()
            runtime.restart("vpn")
            runtime.start.assert_called_once_with("vpn")
            runtime.config["enabled"] = []
            with self.assertRaises(StackError):
                runtime.restart("blog")
            runtime.gateway.assert_not_called()

    def test_partial_cpa_install_must_return_to_official_installer(self):
        with fixture() as (store, runtime):
            write(runtime.cpa_dir / "compose.yaml", "services: {}")
            write(runtime.cpa_dir / ".env", "")
            self.assertTrue(runtime.cpa_installed())
            self.assertFalse(runtime.cpa_ready())
            runtime.gateway = Mock()
            runtime.cpa_override = Mock()
            runtime.up = Mock()
            runtime.sites = Mock()
            runtime.official_installer = Mock(return_value=False)
            runtime.start("cpa", from_all=True)
            runtime.official_installer.assert_called_once()
            runtime.up.assert_not_called()
            runtime.sites.assert_not_called()

    def test_official_installer_failure_is_not_reported_as_success(self):
        with fixture() as (_, runtime):
            runtime.cpa_override = Mock()
            runtime.cpa_installed = Mock(return_value=False)
            runtime.containers = Mock()
            with patch("sys.stdin.isatty", return_value=True), patch("manager.runtime.urllib.request.urlopen") as download:
                download.return_value.__enter__.return_value.read.return_value = b"#!/bin/bash\nexit 23\n"
                with patch("manager.runtime.run", side_effect=[subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 23)]):
                    with self.assertRaisesRegex(StackError, "23"):
                        runtime.official_installer()
            runtime.containers.assert_not_called()

    def test_missing_vpn_database_blocks_start_and_restart_before_gateway_changes(self):
        with fixture() as (store, runtime):
            store.mark("vpn_ready")
            store.mark("vpn_admin_ready")
            runtime.gateway = Mock()
            for action in (runtime.start, runtime.restart):
                with self.assertRaisesRegex(StackError, "数据库缺失"):
                    action("vpn")
            runtime.gateway.assert_not_called()

    def test_restart_all_includes_gateway_without_enabled_businesses(self):
        with fixture() as (_, runtime):
            runtime.config["enabled"] = []
            runtime.gateway = Mock()
            runtime.compose = Mock()
            runtime.up = Mock()
            runtime.restart("all")
            runtime.compose.assert_called_once_with("restart", "nginx-ui")
            runtime.up.assert_called_once_with(["nginx-ui"])

    def test_systemd_working_directory_is_not_quoted(self):
        with fixture() as (_, runtime), patch("manager.runtime.sys.platform", "linux"), \
                patch("manager.runtime.os.geteuid", return_value=0), \
                patch("manager.runtime.shutil.which", return_value="/usr/bin/systemctl"), \
                patch("manager.runtime.Path.is_dir", return_value=True), \
                patch("manager.runtime.write") as write_file, patch("manager.runtime.run"):
            runtime.install_timer()
            service = write_file.call_args_list[0].args[1]
            self.assertIn(f"WorkingDirectory={runtime.root}\n", service)
            self.assertNotIn(f'WorkingDirectory="{runtime.root}"', service)


@unittest.skipUnless(shutil.which("docker"), "Docker Compose CLI is not installed")
class ComposeTest(unittest.TestCase):
    def test_core_services_ports_and_dependency_contract(self):
        with fixture() as (_, runtime):
            with patch.dict(os.environ, GATEWAY_NETWORK="wrong-network", BLOG_IMAGE="wrong/image", VPN_PASSWORD="wrong-password"):
                merged = json.loads(runtime.compose("config", "--format", "json", capture=True).stdout)
            self.assertEqual(runtime.config["network"]["name"], merged["networks"]["gateway"]["name"])
            self.assertEqual(runtime.config["images"]["blog"], merged["services"]["rurublog"]["image"])
            self.assertNotEqual("wrong-password", merged["services"]["panel-init"]["environment"]["XUI_ADMIN_PASSWORD"])
            self.assertEqual("/etc/gost", merged["services"]["gost"]["volumes"][0]["target"])
            self.assertEqual(set(merged["services"]),
                {"nginx-ui", "xui", "gost", "rurublog", "certbot", "panel-init"})
            for service in ("xui", "gost", "rurublog"):
                self.assertEqual(merged["services"][service]["depends_on"]["nginx-ui"]["condition"],
                                 "service_healthy")
            self.assertFalse(merged["services"]["rurublog"].get("ports"))
            self.assertEqual(set(merged["services"]["rurublog"]["depends_on"]), {"nginx-ui"})
            self.assertEqual(merged["services"]["rurublog"]["build"]["args"]["BLOG_BUILD_TESTS"], "false")

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
