#!/bin/sh
# Installs (or updates to) the release this script came in. Run as root from
# the unpacked release:  sh deploy/install.sh
# Used three ways, all the same code: by hand on a Debian machine, inside the
# LXC template while it is built, and by devotion-update for a new version.
#
#   APP_UID=990     the service user's UID when it has to be created
#   SKIP_PIPER=1    don't download the Piper voice engine (audio then needs an API)
set -eu

APP=devotion
APP_UID=${APP_UID:-990}
PIPER_URL=https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz
PIPER_SHA256=a50cb45f355b7af1f6d758c1b360717877ba0a398cc8cbe6d2a7a3a26e225992
VOICE=en_US-lessac-medium
VOICE_SHA256=5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f
VOICE_URL=https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/lessac/medium/$VOICE.onnx
VOICE_DIR=/usr/local/share/$APP/voices

die() { echo "install: $*" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || die "run as root"
src=$(cd "$(dirname "$0")/.." && pwd)
[ -f "$src/VERSION" ] && [ -x "$src/node/bin/node" ] && [ -f "$src/server/src/main.js" ] || die "$src is not an unpacked release"
version=$(cat "$src/VERSION")

if ! id "$APP" >/dev/null 2>&1; then
	groupadd --system --gid "$APP_UID" "$APP"
	useradd --system --uid "$APP_UID" --gid "$APP" --home-dir "/var/lib/$APP" --no-create-home --shell /usr/sbin/nologin "$APP"
fi

# The code: root-owned, one directory per version, "current" points at the one in use.
dest=/opt/$APP/releases/$version
rm -rf "$dest.new"
mkdir -p "$dest.new"
cp -R "$src/VERSION" "$src/server" "$src/node" "$src/deploy" "$dest.new/"
chown -R root:root "$dest.new"
chmod -R u=rwX,go=rX "$dest.new"
rm -rf "$dest"
mv "$dest.new" "$dest"
# A relative link, so it also resolves when the image is inspected from outside.
ln -sfn "releases/$version" "/opt/$APP/current"
# Keep this version and the one before it.
ls -1dt /opt/$APP/releases/*/ | tail -n +3 | xargs -r rm -rf

install -d -m 0750 -o "$APP" -g "$APP" "/var/lib/$APP"
install -d -m 0750 -o root -g "$APP" "/etc/$APP"
install -m 0755 "$src/deploy/devotion-cli.sh" "/usr/local/bin/$APP"
install -m 0755 "$src/deploy/devotion-update.sh" "/usr/local/sbin/$APP-update"
for unit in "$APP.service" "$APP-update.service" "$APP-update.path"; do
	install -m 0644 "$src/deploy/$unit" "/etc/systemd/system/$unit"
done

# Settings that are not secret. Everything else is set in the web page and
# kept in the database; an existing file is left alone.
if [ ! -f "/etc/$APP/$APP.env" ]; then
	cat >"/etc/$APP/$APP.env" <<ENV
PORT=8787
DATA_DIR=/var/lib/$APP
UPDATE_REQUEST_FILE=/var/lib/$APP/update-request
GITHUB_REPO=mastifdogo/devotion_app
PIPER_BIN=/opt/piper/piper
PIPER_MODEL=$VOICE_DIR/$VOICE.onnx
ENV
	chown root:"$APP" "/etc/$APP/$APP.env"
	chmod 0640 "/etc/$APP/$APP.env"
fi

# Piper: the voice that reads the entries aloud, running on this machine.
if [ "${SKIP_PIPER:-0}" != 1 ] && [ ! -x /opt/piper/piper ]; then
	command -v curl >/dev/null || die "curl is needed to download Piper"
	tmp=$(mktemp -d)
	curl -fsSL --retry 3 -o "$tmp/piper.tar.gz" "$PIPER_URL"
	echo "$PIPER_SHA256  $tmp/piper.tar.gz" | sha256sum -c - >/dev/null || die "Piper download does not match its checksum"
	rm -rf /opt/piper
	tar -xzf "$tmp/piper.tar.gz" -C /opt --no-same-owner
	rm -rf "$tmp"
	[ -x /opt/piper/piper ] || die "Piper did not unpack to /opt/piper"
fi
if [ "${SKIP_PIPER:-0}" != 1 ] && [ ! -s "$VOICE_DIR/$VOICE.onnx" ]; then
	mkdir -p "$VOICE_DIR"
	curl -fsSL --retry 3 -o "$VOICE_DIR/$VOICE.onnx.json" "$VOICE_URL.json"
	curl -fsSL --retry 3 -o "$VOICE_DIR/$VOICE.onnx.part" "$VOICE_URL"
	echo "$VOICE_SHA256  $VOICE_DIR/$VOICE.onnx.part" | sha256sum -c - >/dev/null || die "the voice download does not match its checksum"
	grep -q '"sample_rate"' "$VOICE_DIR/$VOICE.onnx.json" || die "the voice's settings file is not what was expected"
	mv "$VOICE_DIR/$VOICE.onnx.part" "$VOICE_DIR/$VOICE.onnx"
fi

if [ -d /run/systemd/system ]; then
	systemctl daemon-reload
	systemctl enable --now "$APP-update.path" >/dev/null 2>&1
	systemctl enable "$APP.service" >/dev/null 2>&1
	systemctl restart "$APP.service"
fi
echo "install: $APP $version installed"
