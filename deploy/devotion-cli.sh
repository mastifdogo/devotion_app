#!/bin/sh
# /usr/local/bin/devotion: runs the server with the installed settings.
#   devotion                        start the server (what the service does)
#   devotion reset-admin-password   forget the admin password; the web page then asks for a new one
set -eu
if [ -r /etc/devotion/devotion.env ]; then
	set -a
	. /etc/devotion/devotion.env
	set +a
fi
# Root must not create files in the data directory that the service can't write.
if [ "$(id -u)" -eq 0 ]; then
	exec runuser -u devotion -- /opt/devotion/current/node/bin/node --disable-warning=ExperimentalWarning /opt/devotion/current/server/src/main.js "$@"
fi
exec /opt/devotion/current/node/bin/node --disable-warning=ExperimentalWarning /opt/devotion/current/server/src/main.js "$@"
