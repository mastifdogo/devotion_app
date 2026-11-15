#!/usr/bin/env bash
# For a GitHub Actions (Ubuntu) runner: installs mmdebstrap and Debian's
# archive keyring straight from Debian's servers, checksummed. The runner's
# own keyring doesn't know a new Debian release's signing keys, so the keyring
# is taken from the release being built.
#   ci-install-build-tools.sh trixie
set -euo pipefail
SUITE=${1:?usage: $0 SUITE}
work=$(mktemp -d)
get() { curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 --max-time 300 "$1" -o "$2"; }
get "https://deb.debian.org/debian/dists/$SUITE/main/binary-all/Packages.xz" "$work/Packages.xz"
for pkg in debian-archive-keyring mmdebstrap; do
	read -r file sum < <(xz -dc "$work/Packages.xz" | awk -v p="$pkg" '
		$1 == "Package:" { hit = ($2 == p) }
		hit && $1 == "Filename:" { f = $2 }
		hit && $1 == "SHA256:" { s = $2 }
		hit && f && s { print f, s; exit }') || true
	[ -n "${file:-}" ] || { echo "::error::$pkg not found on deb.debian.org"; exit 1; }
	get "https://deb.debian.org/debian/$file" "$work/$pkg.deb"
	echo "$sum  $work/$pkg.deb" | sha256sum -c -
done
# Its dependencies (perl, apt) are already on the runner.
sudo dpkg -i --force-depends "$work/debian-archive-keyring.deb" "$work/mmdebstrap.deb"
command -v zstd >/dev/null
mmdebstrap --version
