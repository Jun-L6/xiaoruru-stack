#!/usr/bin/env python3
"""Initialize only this stack's blog paths; never import or overwrite local blog data."""
import os
from pathlib import Path
import secrets
import stat


def main():
    root = Path(__file__).resolve().parent.parent
    uid = int(os.environ.get("BLOG_UID", "10001"))
    gid = int(os.environ.get("BLOG_GID", "10001"))
    if uid <= 0 or gid <= 0:
        raise SystemExit("BLOG_UID and BLOG_GID must be positive non-root IDs")
    if os.geteuid() != 0 and (os.geteuid(), os.getegid()) != (uid, gid):
        raise SystemExit("Run as root on the server, or set BLOG_UID/BLOG_GID to your local IDs")

    def directory(path, owner=False):
        if path.is_symlink():
            raise SystemExit(f"Refusing symbolic-link directory: {path}")
        if path.exists():
            if not path.is_dir():
                raise SystemExit(f"Not a directory: {path}")
        else:
            path.mkdir(mode=0o750 if owner else 0o755)
            path.chmod(0o750 if owner else 0o755)
            if owner and os.geteuid() == 0:
                os.chown(path, uid, gid)
        if owner:
            info = path.stat()
            if info.st_uid != uid or not info.st_mode & stat.S_IWUSR or not info.st_mode & stat.S_IXUSR:
                raise SystemExit(f"Directory must be owned and writable/searchable by UID {uid}: {path}")

    directory(root / "config")
    directory(root / "data")
    directory(root / "config/rurublog")
    config_info = (root / "config/rurublog").stat()
    config_bits = (config_info.st_mode >> (6 if config_info.st_uid == uid else
                                          3 if config_info.st_gid == gid else 0)) & 0o7
    if config_bits & 0o5 != 0o5:
        raise SystemExit("config/rurublog must be readable/searchable by the configured blog UID/GID")
    directory(root / "data/rurublog", owner=True)
    for name in ("database", "uploads", "backups", "logs", "temp"):
        directory(root / "data/rurublog" / name, owner=True)

    target = root / "config/rurublog/application.yml"
    if target.is_symlink():
        raise SystemExit(f"Refusing symbolic-link config: {target}")
    if not target.exists():
        # Never silently pair an existing H2 database with a different password.
        if any((root / "data/rurublog/database").iterdir()):
            raise SystemExit("Existing database found; provide its original application.yml first")
        template = (root / "config/rurublog/application.example.yml").read_text(encoding="utf-8")
        content = template.replace("__ADMIN_SECRET__", secrets.token_hex(24)).replace(
            "__DATABASE_PASSWORD__", secrets.token_hex(24))
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(content)
        if os.geteuid() == 0:
            os.chown(target, uid, gid)
        print(f"Generated private blog credentials: {target} (values not printed)")
    else:
        info = target.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != uid or stat.S_IMODE(info.st_mode) != 0o600:
            raise SystemExit(f"Config must be a regular file owned by UID {uid}, mode 600: {target}")
        print("Existing blog configuration preserved")
    print("Blog data directories ready; original xiaoruru-blog/data was not modified")


if __name__ == "__main__":
    main()
