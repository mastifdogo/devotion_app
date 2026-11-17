#!/usr/bin/env bash
# Builds the Proxmox LXC template (a Debian rootfs .tar.zst) with the Devotion
# server, its own Node.js and the Piper voice engine installed.
#
# Run as root on Debian or Ubuntu (a Proxmox host works too):
#   apt install mmdebstrap zstd
#   make release VERSION=v1.2.3
#   sudo deploy/lxc-template/build-lxc-template.sh --release dist/devotion-v1.2.3-linux-amd64.tar.gz
#
# The template holds nothing secret and nothing unique: no passwords, SSH host
# keys or machine-id. The admin password is chosen in the browser on the first
# visit; devotion-firstboot makes the rest when a container first starts.
set -euo pipefail

APP=devotion
APP_USER_UID=990 # fixed, so a host directory can be chown'ed for it (100000 + this)
# --variant=apt installs almost nothing, so everything the container needs is listed:
#   systemd-sysv  init          login     the console's login prompt (not default from Debian 13)
#   dbus          systemctl     iproute2, ifupdown, isc-dhcp-client   Proxmox's network setup
#   lame          encodes the voice engine's output to MP3
#   curl, jq      the updater downloads releases with them
#   smbclient     writes the archive to a network share without mounting it
PACKAGES=(systemd-sysv dbus login iproute2 iputils-ping ifupdown isc-dhcp-client openssh-server ca-certificates curl jq lame smbclient less nano procps tzdata)
UNITS=("$APP-firstboot.service" "$APP.service" "$APP-update.path")
# Files that must exist in the finished image; the build fails if one is missing.
REQUIRE=(usr/bin/login sbin/init usr/bin/lame usr/bin/jq usr/bin/curl usr/bin/smbclient "usr/local/bin/$APP" "usr/local/sbin/$APP-firstboot" "usr/local/sbin/$APP-update"
	"opt/$APP/current/node/bin/node" "opt/$APP/current/server/src/main.js" "etc/$APP/$APP.env" opt/piper/piper
	"usr/local/share/$APP/voices/en_US-lessac-medium.onnx")

SUITE=trixie # Debian 13
ARCH=amd64
MIRROR=http://deb.debian.org/debian
OUT_DIR=""
RELEASE=""
VERSION=""

usage() {
	cat <<USAGE
Usage: $0 --release FILE [options]

  --release FILE   the release tarball from "make release"
  --suite NAME     Debian release to build on (default: $SUITE)
  --mirror URL     Debian mirror (default: $MIRROR)
  --version V      version in the file name (default: from the tarball's name)
  --out DIR        output directory (default: ./dist)
USAGE
}
die() { echo "ERROR: $*" >&2; exit 1; }
log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

while [ $# -gt 0 ]; do
	case "$1" in
	--release) RELEASE=${2:?}; shift ;;
	--suite) SUITE=${2:?}; shift ;;
	--mirror) MIRROR=${2:?}; shift ;;
	--version) VERSION=${2:?}; shift ;;
	--out) OUT_DIR=${2:?}; shift ;;
	-h | --help) usage; exit 0 ;;
	*) usage >&2; die "unknown option: $1" ;;
	esac
	shift
done

[ "$(id -u)" -eq 0 ] || die "run as root (mmdebstrap runs the install inside the image)"
command -v mmdebstrap >/dev/null || die "mmdebstrap not found: apt install mmdebstrap zstd"
command -v zstd >/dev/null || die "zstd not found: apt install zstd"
[ -f "$RELEASE" ] || die "--release FILE is required"
case "$SUITE" in
bookworm) OS_VERSION=12 ;;
trixie) OS_VERSION=13 ;;
*) die "unsupported --suite $SUITE (add its version number here once it has been tested)" ;;
esac

here=$(cd "$(dirname "$0")" && pwd)
OUT_DIR=${OUT_DIR:-$PWD/dist}
[ -n "$VERSION" ] || VERSION=$(basename "$RELEASE" | sed -n "s/^$APP-\(.*\)-linux-.*$/\1/p")
[ -n "$VERSION" ] || die "can't tell the version from $RELEASE; pass --version"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
stage="$work/release"
mkdir -p "$stage"
tar -xzf "$RELEASE" -C "$stage" --strip-components=1
[ -f "$stage/deploy/install.sh" ] || die "$RELEASE has no deploy/install.sh"
for f in "$APP-firstboot.sh" "$APP-firstboot.service" "$APP-after-firstboot.conf"; do
	[ -f "$here/$f" ] || die "missing $here/$f"
	cp "$here/$f" "$work/"
done

# Sources name Debian's keyring explicitly, so the build works on any host:
# apt on an Ubuntu machine doesn't trust Debian's keys by default, and an old
# keyring doesn't know a new release's.
keyring=/usr/share/keyrings/debian-archive-keyring.gpg
[ -s "$keyring" ] || die "$keyring not found on this machine: apt install debian-archive-keyring (from the release being built)"
sources=(
	"deb [signed-by=$keyring] $MIRROR $SUITE main"
	"deb [signed-by=$keyring] $MIRROR $SUITE-updates main"
	"deb [signed-by=$keyring] http://security.debian.org/debian-security $SUITE-security main"
)
pkg_list=$(IFS=,; echo "${PACKAGES[*]},debian-archive-keyring")

name="debian-$OS_VERSION-${APP}_${VERSION}_$ARCH.tar.zst"
mkdir -p "$OUT_DIR"
output="$OUT_DIR/$name"

# --- In-image customisation (runs on the build host; $1 is the image's root) ------
cat >"$work/customize.sh" <<CUSTOMIZE
#!/bin/sh
set -eu
root=\$1
in_root() { chroot "\$root" env DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "\$@"; }
need() { [ -e "\$root/\$1" ] || { echo "the image is missing /\$1" >&2; exit 1; }; }

mkdir -p "\$root/opt/$APP-release"
cp -R "$stage/." "\$root/opt/$APP-release/"

# The application, installed by its own installer (the one a manual install
# and the updater use). It downloads the voice engine, so the image needs
# name resolution for this step only.
made_resolv=0
if [ ! -s "\$root/etc/resolv.conf" ]; then cp /etc/resolv.conf "\$root/etc/resolv.conf"; made_resolv=1; fi
in_root env APP_UID=$APP_USER_UID sh /opt/$APP-release/deploy/install.sh
[ "\$made_resolv" = 1 ] && rm -f "\$root/etc/resolv.conf"
rm -rf "\$root/opt/$APP-release"

# First-boot provisioning, and the application waits for it.
install -m 0755 "$work/$APP-firstboot.sh" "\$root/usr/local/sbin/$APP-firstboot"
install -m 0644 "$work/$APP-firstboot.service" "\$root/etc/systemd/system/$APP-firstboot.service"
install -d "\$root/etc/systemd/system/$APP.service.d"
install -m 0644 "$work/$APP-after-firstboot.conf" "\$root/etc/systemd/system/$APP.service.d/firstboot.conf"
in_root systemctl enable ${UNITS[*]}

cat >"\$root/etc/motd" <<'MOTD'

  Devotion server: first-boot setup has not finished yet.
  Check:  systemctl status devotion-firstboot

MOTD

# What a person finds missing only on their own server: check it now.
for f in ${REQUIRE[*]}; do need "\$f"; done
for u in ${UNITS[*]}; do
	in_root systemctl is-enabled "\$u" >/dev/null || { echo "\$u is not enabled in the image" >&2; exit 1; }
done

# The real thing, end to end: the server's own runtime starts, and the voice
# engine turns a sentence into an MP3, as the service user.
in_root /opt/$APP/current/node/bin/node -e 'require("node:sqlite"); console.log("node", process.version, "with sqlite")'
in_root runuser -u $APP -- sh -c 'cd /tmp && echo "This is the voice that will read your devotions." | /opt/piper/piper --model /usr/local/share/$APP/voices/en_US-lessac-medium.onnx --output_file t.wav >/dev/null 2>&1 && lame --quiet -m m -V 6 t.wav t.mp3 && [ "\$(wc -c <t.mp3)" -gt 5000 ] && rm t.wav t.mp3' \
	|| { echo "the voice engine does not produce audio in the image" >&2; exit 1; }
# The Bible is read a verse per line, and the server times each verse from
# the file the voice engine writes for it: one file per line, in order.
in_root runuser -u $APP -- sh -c 'cd /tmp && rm -rf pv && mkdir pv && printf "Verse one.\\nVerse two is a little longer.\\nVerse three.\\n" | /opt/piper/piper --model /usr/local/share/$APP/voices/en_US-lessac-medium.onnx --output_dir pv >/dev/null 2>&1 && [ "\$(ls pv/*.wav | wc -l)" -eq 3 ] && rm -rf pv' \
	|| { echo "the voice engine does not write one file per line in the image" >&2; exit 1; }
in_root runuser -u $APP -- smbclient --version
echo "voice engine check: ok"
# The server must not have left a database behind while being checked.
[ -z "\$(ls -A "\$root/var/lib/$APP")" ] || { echo "/var/lib/$APP is not empty" >&2; exit 1; }

# Nothing that must be unique per container, and no caches.
rm -f "\$root"/etc/ssh/ssh_host_*
: >"\$root/etc/machine-id"
rm -f "\$root/var/lib/dbus/machine-id" "\$root/etc/hostname"
rm -rf "\$root"/var/lib/apt/lists/* "\$root"/var/cache/apt/*.bin "\$root"/var/cache/apt/archives/*.deb
find "\$root/var/log" -type f -exec truncate -s 0 {} +
rm -rf "\$root"/tmp/* "\$root"/var/tmp/* "\$root/root/.bash_history"
CUSTOMIZE
chmod +x "$work/customize.sh"

log "building $name ($APP $VERSION on Debian $OS_VERSION, $ARCH)"
mmdebstrap \
	--variant=apt \
	--architectures="$ARCH" \
	--include="$pkg_list" \
	--skip=output/dev \
	--dpkgopt='path-exclude=/usr/share/man/*' \
	--dpkgopt='path-exclude=/usr/share/doc/*' \
	--dpkgopt='path-include=/usr/share/doc/*/copyright' \
	--dpkgopt='path-exclude=/usr/share/locale/*' \
	--dpkgopt='path-include=/usr/share/locale/locale.alias' \
	--customize-hook="$work/customize.sh \"\$1\"" \
	"$SUITE" "$output" "${sources[@]}"

log "template written: $output ($(du -h "$output" | cut -f1))"
cat <<NEXT

Check it:    python3 deploy/lxc-template/check-template.py $output --require usr/local/bin/$APP
Use it:      copy to /var/lib/vz/template/cache/ on the Proxmox host, then
             Create CT > Template: $name  (unprivileged, nesting on)
NEXT
