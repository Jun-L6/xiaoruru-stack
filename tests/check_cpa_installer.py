"""Check today's unmodified official installer with execution disabled in a temporary directory."""
import os
import subprocess
import urllib.request
import uuid
from test_manager import fixture
from manager.runtime import CPA_INSTALLER, clean_env
from manager.storage import write


def main():
    with fixture() as (store, runtime):
        runtime.cpa_override()
        script = store.system / "install-cpamp.sh"
        with urllib.request.urlopen(CPA_INSTALLER, timeout=45) as response:
            content = response.read().decode()
        write(script, content, 0o700)
        env = clean_env()
        env.update(CPAMP_LANG="zh-CN", CPAMP_INSTALL_DIR=str(runtime.cpa_dir),
                   CPAMP_INSTALL_MODE="stack", CPAMP_DEPLOY_METHOD="docker",
                   CPAMP_PROJECT_NAME="xiaoruru-contract-" + uuid.uuid4().hex[:10],
                   CPAMP_NON_INTERACTIVE="1", CPAMP_CONFIRM="1", CPAMP_SKIP_EXECUTE="1")
        result = subprocess.run(["bash", str(script)], cwd=runtime.cpa_dir, env=env,
                                capture_output=True, text=True, timeout=180)
        if result.returncode:
            # Avoid disclosing any freshly generated credentials from official output.
            raise RuntimeError(f"Official installer contract test failed ({result.returncode})")
        assert runtime.cpa_installed(), "Official installer did not generate its Compose and env"
        runtime.validate_cpa()
        assert (runtime.cpa_dir / "secrets").is_dir()
        print("Official installer generated full-stack configuration with the override present; merged ports, networks and bind mounts passed. Execution was disabled.")


if __name__ == "__main__":
    main()
