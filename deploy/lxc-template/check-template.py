#!/usr/bin/env python3
"""Looks inside a Proxmox LXC template (.tar.zst, .tar.gz or .tar.xz) for the
mistakes that only show up on a server.

  check-template.py TEMPLATE [--require PATH ...] [--list]

--require PATH   a file that must be in the image (e.g. usr/local/bin/myapp); repeatable
--list           print every path in the image and exit

Exit status 0 when nothing is wrong, 1 otherwise. Needs zstd support for
.tar.zst: the `zstandard` Python module, or the `zstd` program.
"""
import argparse
import re
import shutil
import subprocess
import sys
import tarfile


def open_tar(path):
    if not path.endswith(".zst"):
        return tarfile.open(path, "r|*"), None
    try:
        import zstandard

        f = open(path, "rb")
        return tarfile.open(fileobj=zstandard.ZstdDecompressor().stream_reader(f), mode="r|"), None
    except ImportError:
        if not shutil.which("zstd"):
            sys.exit("needs the zstandard Python module or the zstd program (apt install zstd)")
        p = subprocess.Popen(["zstd", "-dc", path], stdout=subprocess.PIPE)
        return tarfile.open(fileobj=p.stdout, mode="r|"), p


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("template")
    ap.add_argument("--require", action="append", default=[], metavar="PATH")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()

    tar, proc = open_tar(a.template)
    entries = {}
    for m in tar:
        name = re.sub(r"^\./", "", m.name).rstrip("/")
        entries[name] = m
        if a.list:
            print(name)
    if proc:
        proc.wait()
    if a.list:
        return 0

    def has(*paths):
        return any(p in entries for p in paths)

    def any_match(pattern):
        rx = re.compile(pattern)
        return sorted(n for n in entries if rx.search(n))

    problems, notes = [], []

    # Things a container can't do without.
    if not has("usr/bin/login", "bin/login"):
        problems.append("no login program (usr/bin/login): the console returns to 'login:' without asking for a password. Add the 'login' package.")
    if not has("sbin/init", "usr/sbin/init", "usr/lib/systemd/systemd", "lib/systemd/systemd"):
        problems.append("no init (systemd): the container won't boot. Add 'systemd-sysv'.")
    if not has("usr/bin/dbus-daemon", "usr/bin/dbus-broker"):
        notes.append("no dbus: systemctl and hostnamectl may misbehave. Add 'dbus'.")
    if not any_match(r"^etc/ssl/certs/ca-certificates\.crt$") and not any_match(r"^usr/share/ca-certificates/"):
        problems.append("no CA certificates: HTTPS requests from the container fail. Add 'ca-certificates'.")
    if not has("usr/bin/ip", "bin/ip", "sbin/ip", "usr/sbin/ip"):
        notes.append("no 'ip' command (iproute2): Proxmox's network setup for Debian guests expects it.")
    if not has("etc/network/interfaces") and not any_match(r"systemd-networkd"):
        notes.append("neither ifupdown nor systemd-networkd: check the container gets an address.")

    # Things that must not be shared between containers.
    keys = any_match(r"^etc/ssh/ssh_host_.*_key(\.pub)?$")
    if keys:
        problems.append(f"SSH host keys in the image ({len(keys)} files): every container would have the same identity. Remove /etc/ssh/ssh_host_*.")
    mid = entries.get("etc/machine-id")
    if mid is not None and mid.size > 1:
        problems.append("etc/machine-id is not empty: every container would share it. Truncate it.")
    if has("var/lib/dbus/machine-id") and entries["var/lib/dbus/machine-id"].isfile() and entries["var/lib/dbus/machine-id"].size > 1:
        problems.append("var/lib/dbus/machine-id is set: remove it.")
    if has("etc/hostname") and entries["etc/hostname"].size > 0:
        notes.append("etc/hostname is set; Proxmox writes its own, but remove it to be tidy.")
    pg = any_match(r"^var/lib/postgresql/\d+/main/PG_VERSION$")
    if pg:
        problems.append("a PostgreSQL cluster is in the image: every container would share its files and passwords. pg_dropcluster it and create one at first boot.")
    mysql = any_match(r"^var/lib/mysql/(mysql|ibdata1)")
    if mysql:
        problems.append("a MySQL/MariaDB data directory is in the image: create it at first boot instead.")

    # Secrets and leftovers.
    for n, m in entries.items():
        if re.search(r"(^|/)(\.env|[^/]*\.env|id_rsa|id_ed25519|[^/]*\.pem|[^/]*\.key)$", n) and m.isfile() and m.size > 0:
            if n.startswith(("etc/ssl/", "usr/", "var/lib/dpkg/", "etc/ca-certificates")) or n.endswith(".env.example"):
                continue
            if m.mode & 0o004:
                problems.append(f"{n} looks like a secret and is world-readable (mode {oct(m.mode & 0o777)}).")
            else:
                notes.append(f"{n} looks like a secret baked into the image; it should be made at first boot.")
    if any_match(r"^root/\.bash_history$"):
        notes.append("root/.bash_history is in the image.")
    lists = any_match(r"^var/lib/apt/lists/.+_Packages")
    if lists:
        notes.append("apt package lists are in the image (bigger download); remove /var/lib/apt/lists/*.")
    debs = any_match(r"^var/cache/apt/archives/.+\.deb$")
    if debs:
        notes.append(f"{len(debs)} downloaded .deb files left in var/cache/apt/archives.")

    for r in a.require:
        if not has(r.lstrip("/")):
            problems.append(f"required file missing: {r}")

    print(f"{a.template}: {len(entries)} entries")
    for p in problems:
        print(f"  FAIL  {p}")
    for n in notes:
        print(f"  note  {n}")
    if not problems:
        print("  ok    nothing wrong found (this is not a boot test)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
