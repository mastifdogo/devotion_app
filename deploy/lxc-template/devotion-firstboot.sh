#!/bin/sh
# First boot of a container made from the template. Installed as
# /usr/local/sbin/devotion-firstboot and run once by devotion-firstboot.service,
# before devotion.service. It gives the container its own SSH host keys, checks
# the data directory, and puts the address of the setup page on the console.
# There is no password to make here: the admin password is chosen in the
# browser on the first visit.
# Safe to run again: it stops at once if the state file exists. Works without
# systemd, so it can be tried in a chroot.
set -eu

APP=devotion
# Not in the data directory: that may be a mount shared with an older container.
STATE=/etc/$APP/provisioned
DATA_DIR=/var/lib/$APP
PORT=$(sed -n 's/^PORT=//p' "/etc/$APP/$APP.env" 2>/dev/null | tail -n1)
PORT=${PORT:-8787}

log() { echo "$APP-firstboot: $*"; }
[ -f "$STATE" ] && { log "already provisioned"; exit 0; }

if command -v ssh-keygen >/dev/null 2>&1 && ! ls /etc/ssh/ssh_host_*_key >/dev/null 2>&1; then
	log "generating SSH host keys"
	ssh-keygen -A
fi

# The data directory (often a bind mount from the host).
if ! runuser -u "$APP" -- sh -c "touch $DATA_DIR/.write-test && rm $DATA_DIR/.write-test" 2>/dev/null; then
	log "WARNING: $DATA_DIR is not writable by the $APP user (UID $(id -u "$APP"))."
	log "         On the Proxmox host: chown $((100000 + $(id -u "$APP"))):$((100000 + $(id -g "$APP"))) <the mounted directory>"
fi

# Shown above the console's login prompt. \4 is replaced by this container's
# address each time the prompt is drawn.
cat >/etc/issue <<ISSUE
Debian GNU/Linux \\n \\l

  Devotion server: set it up in a web browser at
      http://\\4:$PORT/
  (no need to log in here)

ISSUE

cat >/etc/motd <<MOTD

  Devotion server
  Setup and settings:  http://<this container's address>:$PORT/   (address: hostname -I)
  Logs:                journalctl -u $APP -f
  Data:                $DATA_DIR
  Forgot the admin password:  $APP reset-admin-password

MOTD

# Last, so a run that failed part-way is tried again at the next boot.
date -u +%Y-%m-%dT%H:%M:%SZ >"$STATE"
log "done"
