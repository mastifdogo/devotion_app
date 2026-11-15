#!/bin/sh
# /usr/local/sbin/devotion-update: installs a release of the server from the
# GitHub repository named in /etc/devotion/devotion.env. Runs as root, started
# by devotion-update.path when the admin page asks for an update, or by hand:
#   devotion-update v1.2.3
#
# The unprivileged server only names a version. This script downloads that
# release from the configured repository itself, checks it against the
# release's SHA256SUMS, and runs the installer inside it.
set -eu

APP=devotion
REQUEST=/var/lib/$APP/update-request
log() { echo "$APP-update: $*"; }
die() { log "ERROR: $*"; exit 1; }

tag=${1:-}
token=""
if [ -z "$tag" ]; then
	[ -e "$REQUEST" ] || die "no version given and no request file"
	tag=$(sed -n 1p "$REQUEST" | tr -d '\r\n ')
	token=$(sed -n 2p "$REQUEST" | tr -d '\r\n ')
fi
# Removed before anything else, so a failing update is not started again and again.
rm -f "$REQUEST"

echo "$tag" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' || die "'$tag' is not a release tag"
if [ -n "$token" ]; then echo "$token" | grep -Eq '^[A-Za-z0-9_]+$' || die "the GitHub token has unexpected characters"; fi

GITHUB_REPO=mastifdogo/devotion_app
[ -r "/etc/$APP/$APP.env" ] && GITHUB_REPO=$(sed -n 's/^GITHUB_REPO=//p' "/etc/$APP/$APP.env" | tail -n1)
echo "$GITHUB_REPO" | grep -Eq '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' || die "GITHUB_REPO is not owner/name"

file="$APP-$tag-linux-amd64.tar.gz"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"

log "downloading $file from $GITHUB_REPO"
if [ -n "$token" ]; then
	# A private repository: assets are fetched through the API with the token,
	# which is sent to api.github.com only.
	curl -fsSL --retry 3 -H "Authorization: Bearer $token" -H "Accept: application/vnd.github+json" \
		"https://api.github.com/repos/$GITHUB_REPO/releases/tags/$tag" -o release.json || die "GitHub has no release $tag (or the token can't read it)"
	for name in "$file" SHA256SUMS; do
		url=$(jq -r --arg n "$name" '.assets[] | select(.name == $n) | .url' release.json)
		[ -n "$url" ] || die "release $tag has no $name"
		curl -fsSL --retry 3 -H "Authorization: Bearer $token" -H "Accept: application/octet-stream" "$url" -o "$name" || die "could not download $name"
	done
else
	base="https://github.com/$GITHUB_REPO/releases/download/$tag"
	curl -fsSL --retry 3 -o "$file" "$base/$file" || die "could not download $file (is there a release $tag with it?)"
	curl -fsSL --retry 3 -o SHA256SUMS "$base/SHA256SUMS" || die "could not download SHA256SUMS"
fi

grep " $file\$" SHA256SUMS >sum || die "SHA256SUMS does not list $file"
sha256sum -c sum >/dev/null || die "$file does not match its checksum"

mkdir unpacked
tar -xzf "$file" -C unpacked --strip-components=1 --no-same-owner
[ -f unpacked/deploy/install.sh ] || die "the release has no installer"
log "installing $tag"
sh unpacked/deploy/install.sh
log "now running $(cat /opt/$APP/current/VERSION)"
