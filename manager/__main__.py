"""Chinese keyboard-first terminal menus and the non-interactive command entry."""
import argparse
from contextlib import contextmanager
import copy
import fcntl
import json
import os
from pathlib import Path
import sys
import time

from .runtime import MODULES, Runtime, SERVICES
from .storage import StackError, Store, directory, safe_path, validate


def choice(title, options, back_label="返回"):
    if not sys.stdin.isatty():
        raise StackError("此操作需要交互终端；使用明确的子命令或 --yes。")
    while True:
        print("\n" + title)
        for index, option in enumerate(options, 1):
            print(f"  {index}. {option}")
        print("  0. " + back_label)
        raw = input("请输入编号：").strip()
        if raw.isdigit() and 0 <= int(raw) <= len(options):
            return int(raw)
        print("输入无效，请输入菜单中的编号。")


def ask(label, default=""):
    prompt = f"{label} [{default}]" if default else label
    value = input(prompt + "：").strip()
    if value.lower() == "q":
        raise KeyboardInterrupt()
    return value or default


def yes(label, default=True):
    while True:
        value = ask(label + "（y/n）", "y" if default else "n").lower()
        if value in ("y", "yes", "是"):
            return True
        if value in ("n", "no", "否"):
            return False
        print("请输入 y 或 n。")


@contextmanager
def lock(store, operation=None):
    directory(store.system)
    path = safe_path(store.system / "operation.lock")
    with path.open("a") as handle:
        os.chmod(path, 0o600)
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise StackError("另一项部署操作正在执行，请等待完成后再试。")
        outcome = "completed"
        try:
            yield
        except BaseException:
            outcome = "incomplete"
            raise
        finally:
            if operation:
                record = {"time": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                          "operation": operation, "outcome": outcome}
                try:
                    path = safe_path(store.system / "operations.jsonl")
                    with path.open("a") as log:
                        os.chmod(path, 0o600)
                        log.write(json.dumps(record, ensure_ascii=False) + "\n")
                except OSError:
                    print("操作日志写入失败，请检查 data/system 权限。", file=sys.stderr)


def initialize(store, module=None, source=None):
    if module not in (None, "gateway", *MODULES):
        raise StackError("初始化功能名只能为 gateway、vpn、cpa、blog。")
    exists = store.config_path.exists()
    config = store.load() if exists else store.defaults()
    if not exists and module is not None and not source:
        config["enabled"] = []
        config["gost"] = False
    previous = copy.deepcopy(config)
    if source:
        config = json.loads(safe_path(Path(source).absolute()).read_text())
    else:
        if not sys.stdin.isatty():
            raise StackError("首次初始化需要交互终端，或使用 init --config /绝对路径/config.json。")
        print("\n初始化只准备配置与目录，不启动容器。回车保留默认值，q 取消，密码自动生成。")
        config["email"] = ask("证书通知邮箱", config["email"])
        if module is None:
            for name in MODULES:
                enabled = yes(f"启用 {name}", name in config["enabled"])
                config["enabled"] = [item for item in config["enabled"] if item != name]
                if enabled:
                    config["enabled"].append(name)
        elif module in MODULES:
            if module not in config["enabled"]:
                config["enabled"].append(module)
        if "vpn" in config["enabled"] and module in (None, "vpn"):
            config["gost"] = yes("VPN 同时启用 GOST HTTPS 正向代理（9443）", config["gost"])
        if "vpn" not in config["enabled"]:
            config["gost"] = False
        keys = ["gateway"]
        if module is None:
            keys += (["vpn"] if "vpn" in config["enabled"] else [])
            keys += (["cpamp", "cpa_api"] if "cpa" in config["enabled"] else [])
            keys += (["blog"] if "blog" in config["enabled"] else [])
        else:
            keys += {"vpn": ["vpn"], "cpa": ["cpamp", "cpa_api"], "blog": ["blog"],
                     "gateway": []}.get(module, [])
        if config["gost"] and module in (None, "vpn"):
            keys.append("gost")
        for key in keys:
            config["domains"][key] = ask(f"{key} 域名", config["domains"][key]).lower()
    validate(config)
    if exists and config["network"] != previous["network"]:
        raise StackError("初始化后不能直接更换共享网络；请使用独立的新部署目录。")
    if exists:
        # Disablement is not an implicit destructive lifecycle action.
        removed = set(previous["enabled"]) - set(config["enabled"])
        if previous["gost"] and not config["gost"]:
            removed.add("vpn")
        runtime = Runtime(store)
        if removed and any(runtime.running(item) for item in removed):
            raise StackError("请先停止准备关闭的功能，再修改启用设置。")
    store.prepare(config)
    store.save(config)
    print(f"配置已保存：{store.config_path}")
    print(f"私密凭据：{store.system / 'secrets.json'}（不会在状态页输出）")
    print("可重复执行；已有账号、数据库和 VPN 客户端不会重置。")
    if exists and config["domains"] != previous["domains"]:
        print("域名已变化：启动网关取得新证书后，执行 tools sites 对相应站点应用配置。")
    print("下一步：./bootstrap.sh start <功能>；博客先执行 build blog 或导入镜像。")


def tools_menu(runtime, action, yes_flag=False):
    if not action:
        options = ["诊断部署", "申请／检查证书", "安装每日证书续期任务", "重新生成指定功能的反代站点"]
        selected = choice("临时工具（不会增加常驻容器）", options)
        if not selected:
            return
        action = ("doctor", "renew", "timer", "sites")[selected - 1]
    if action == "doctor":
        runtime.doctor()
    elif action == "renew":
        runtime.preflight(prepare=False)
        runtime.renew()
    elif action == "timer":
        runtime.preflight(prepare=False)
        runtime.install_timer()
    elif action == "sites":
        selected = choice("选择要重新生成的反代（会覆盖该站点的 Nginx UI 编辑）",
                          ["gateway", "vpn", "cpa", "blog"])
        if not selected:
            return
        module = ["gateway", "vpn", "cpa", "blog"][selected - 1]
        if not yes_flag and not yes(f"确认替换 {module} 站点配置", False):
            return
        runtime.preflight()
        runtime.repair_sites(module)
    else:
        raise StackError("工具支持 doctor、renew、timer、sites。")


def execute(args, store=None):
    store = store or Store()
    command, module = args.command, args.module
    if command == "status" and not store.config_path.exists():
        print("项目尚未初始化；运行 ./bootstrap.sh init。没有启动或修改容器。")
        return
    if command in ("init", "config"):
        with lock(store, command + " " + (module if module in SERVICES else "all")):
            initialize(store, module, args.config)
        return
    if command == "update" and module == "cpa":
        raise StackError("CPA 由官方安装器管理，请运行 start cpa，在官方菜单中选择升级。")
    if command in ("start", "stop", "restart") and module not in (*SERVICES.keys(), "all"):
        raise StackError("请指定 gateway、vpn、cpa、blog 或 all。")
    if command == "build" and module != "blog":
        raise StackError("只有博客需要构建：build blog。CPA 使用官方预构建镜像。")
    if command == "update" and module not in ("gateway", "vpn"):
        raise StackError("update 支持 gateway、vpn；博客使用 build blog 后 start blog。")
    if command == "tools" and module not in (None, "doctor", "renew", "timer", "sites"):
        raise StackError("工具支持 doctor、renew、timer、sites。")
    if command == "stop" and module == "all" and not args.yes:
        if not sys.stdin.isatty() or not yes("停止所有业务与网关（保留数据）", False):
            print("已取消。无人值守执行请显式使用 --yes。")
            return
    if command == "update" and not args.yes and (not sys.stdin.isatty() or not yes("拉取配置中的镜像并应用更新", False)):
        print("已取消。")
        return
    runtime = Runtime(store)
    if command == "status":
        runtime.status()
        return
    if command == "logs":
        if module not in SERVICES:
            raise StackError("logs 需要指定 gateway、vpn、cpa 或 blog。")
        runtime.logs(module)
        return
    with lock(store, command + " " + (module if module in (*SERVICES.keys(), "all") else "")):
        if command == "doctor":
            runtime.doctor()
            return
        if command == "tools":
            tools_menu(runtime, module, args.yes)
            return
        if command == "stop":
            # Stopping existing containers must not depend on valid Compose/Nginx files or regenerate secrets.
            runtime.stop(module)
            return
        runtime.preflight()
        if command in ("start", "stop", "restart"):
            if command == "start":
                runtime.start(module, choose=choice)
            else:
                getattr(runtime, command)(module)
        elif command == "build":
            runtime.build_blog()
        elif command == "update":
            services = SERVICES[module][:]
            if module == "vpn" and not runtime.config["gost"]:
                services.remove("gost")
            runtime.compose("pull", *services)
            runtime.start(module)
        else:
            raise StackError("不支持的操作，请使用 --help。")


def menu():
    options = ["查看功能状态", "初始化／调整部署配置", "启动功能", "停止功能", "重启功能",
               "查看日志", "构建博客镜像", "更新网关／VPN 镜像", "临时工具"]
    commands = ["status", "init", "start", "stop", "restart", "logs", "build", "update", "tools"]
    while True:
        selected = choice("小茹茹服务管理 · 全键盘操作", options, back_label="退出")
        if not selected:
            return
        command = commands[selected - 1]
        module = None
        if command in ("start", "stop", "restart", "logs", "update"):
            modules = ["gateway", "vpn", "cpa", "blog"]
            if command in ("start", "stop", "restart"):
                modules += ["all"]
            if command == "update":
                modules = ["gateway", "vpn", "cpa（进入官方安装器）"]
            item = choice("选择功能", modules)
            if not item:
                continue
            module = modules[item - 1].split("（")[0]
            if command == "update" and module == "cpa":
                command = "start"
        elif command == "build":
            module = "blog"
        try:
            execute(argparse.Namespace(command=command, module=module, config=None, yes=False))
        except (StackError, ValueError, OSError) as error:
            print(f"未完成：{error}", file=sys.stderr)
        except KeyboardInterrupt:
            print("\n已取消当前操作。已启动的容器和数据保留；可查看状态后继续。")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="小茹茹部署管理；不带参数进入中文交互菜单。")
    parser.add_argument("command", nargs="?", choices=["status", "init", "config", "start", "stop",
                        "restart", "logs", "doctor", "tools", "build", "update"])
    parser.add_argument("module", nargs="?", help="gateway / vpn / cpa / blog / all；tools 可接工具名")
    parser.add_argument("--config", help="init 读取 JSON 配置文件，不启动容器")
    parser.add_argument("--yes", action="store_true", help="确认本地管理器的停止／更新提示；不替 CPA 安装器确认")
    args = parser.parse_args()
    try:
        if args.command:
            execute(args)
        else:
            menu()
    except (StackError, ValueError, OSError) as error:
        print(f"未完成：{error}", file=sys.stderr)
        return 1
    except (KeyboardInterrupt, EOFError):
        print("\n已取消。数据保留；可运行 status 查看当前状态。")
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
