# Devotion

A small self-hosted server that reads devotional emails from a Gmail inbox,
turns each one into a clean text entry with Claude, and narrates it to an MP3.
A companion Android app syncs the entries and keeps text and audio on the
phone for offline reading and listening.

```
Gmail ──(OAuth, read-only)──▶ server ──▶ Claude: email → entries
                                │    └──▶ voice engine: entry → MP3
                                ▼
                          Android app (syncs, caches, plays)
```

| Folder | What it is |
|---|---|
| `server/` | The server: Node.js, SQLite, one dependency (the Anthropic SDK). API, admin web page, Gmail, extraction, audio, update check. |
| `android/` | The app: Kotlin, Jetpack Compose, Media3. |
| `deploy/` | Installer, systemd units, updater, and the Proxmox LXC template build (`deploy/lxc-template/`). |
| `.github/workflows/` | `ci.yml` (tests and builds on every push), `release.yml` (publishes a release). |

## Set up the server on Proxmox

1. On the Proxmox host, download the template from the
   [latest release](https://github.com/mastifdogo/devotion_app/releases/latest)
   (the release notes give the exact `wget` and checksum lines), or use
   *CT Templates → Download from URL* in the web interface.
2. *Create CT* from it: **Unprivileged**, **Nesting on** (Options → Features),
   1 GB memory, 8 GB disk. Start it.
3. Open `http://<container-address>:8787/` in a browser. The address is shown
   on the container's console, above the login prompt; there is no need to
   log in on the console.
4. The page asks you to choose an admin password, and then everything is set
   up there:

   - **Gmail**: the OAuth client and the keywords (see below).
   - **Reading the emails**: your Anthropic API key.
   - **Audio**: works as installed (the Piper voice runs inside the container).
     An OpenAI-compatible speech API can be used instead.
   - **Android app**: shows the address and token to type into the app.
   - **Updates**, **Admin password**, and a light/dark theme switch at the top.

To keep the data on the host instead of inside the container, bind-mount a
directory before the first start and give it to the service user (UID 990 in
the container is 100990 on the host):

```sh
pct set <ctid> -mp0 /path/on/host,mp=/var/lib/devotion
chown 100990:100990 /path/on/host
```

Forgot the admin password? On the Proxmox host:

```sh
pct exec <ctid> -- devotion reset-admin-password
```

The next visit to the page asks for a new one.

### Connecting Gmail

Google requires your own OAuth client; it takes a few minutes, once.

1. In the [Google Cloud console](https://console.cloud.google.com/) create a
   project, enable the **Gmail API**, and configure the OAuth consent screen
   (External; add your own address as a test user).
2. Create credentials → **OAuth client ID** → type **Web application**. Under
   *Authorized redirect URIs* add the URI the admin page shows in its Gmail
   section (by default `http://localhost:8787/oauth/callback`).
3. Paste the client ID and secret into the admin page, enter your keywords,
   save, and press **Connect Gmail**.
4. Approve access in the tab that opens. Google then sends the browser to
   `http://localhost:8787/…`, which shows an error page unless the server
   runs on your own computer: that is expected. Copy the whole address from
   the address bar into the box on the admin page and press *Finish
   connecting*. (If the server has a public `https` address, enter it under
   *public https address* and the redirect lands on the server directly.)

The server asks for read-only access (`gmail.readonly`) and never changes
anything in the mailbox.

While the Google project's consent screen is in *Testing*, Google expires the
connection after 7 days and the admin page will ask you to reconnect. Press
*Publish app* on the consent screen to make it permanent; for a client only
you use, no review is needed.

### Which emails are imported

Only emails that contain at least one of your **keywords** (in the subject,
the sender or the text) are imported; with no keywords set, nothing is read.
The optional *extra Gmail filter* narrows the search further with normal
Gmail search syntax, for example `from:daily@example.org newer_than:30d`.

Each matching email is read once. Claude returns the devotion(s) in it as
entries (title, date, scripture reference, one-line summary, full text) and
leaves out the newsletter material around them; an email with no devotion in
it creates nothing. The *Emails read* table shows what happened to each one.

## The Android app

Install the APK from the release page, open **Settings**, and enter the
server address and API token shown on the admin page. The app syncs on start
and on refresh, stores entries and audio on the phone, and works offline with
what it has. Settings also has the theme (system, light, dark) and
**Check for updates**.

## Updates

Nothing checks for updates on its own. Both buttons ask GitHub for this
repository's latest release only when pressed.

- **Server**: admin page → *Updates* → *Check for updates* → *Install*. A
  root-owned updater in the container downloads that release, verifies it
  against the release's `SHA256SUMS`, installs it and restarts the server.
- **App**: Settings → *Check for updates* → *Download and install*.

If the repository is private, save a GitHub token (read access to this
repository) on the admin page and in the app's settings.

### Publishing a release

```sh
git tag v1.2.3 && git push origin v1.2.3
```

or run the *Release* workflow from the Actions tab. It publishes the server
tarball, the Proxmox template, the APK and `SHA256SUMS`.

Android only installs an update over an app signed with the same key. Create
a keystore once and store it as repository secrets (names are listed at the
top of `.github/workflows/release.yml`):

```sh
keytool -genkeypair -v -keystore release.jks -alias devotion -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # → ANDROID_KEYSTORE_BASE64
```

Without the secrets the APK is signed with a throwaway key and each new
version needs the old one uninstalled first.

## Development

```sh
cd server && npm ci
npm test                                  # no network or keys needed
DATA_DIR=./data node src/main.js          # http://localhost:8787/
```

```sh
cd android && ./gradlew assembleDebug     # needs the Android SDK
```

```sh
make release VERSION=v0.0.0-dev           # dist/devotion-…-linux-amd64.tar.gz
sudo deploy/lxc-template/build-lxc-template.sh --release dist/devotion-v0.0.0-dev-linux-amd64.tar.gz
python3 deploy/lxc-template/check-template.py dist/debian-13-devotion_v0.0.0-dev_amd64.tar.zst
```

### The app's API

All but `health` need `Authorization: Bearer <token>`.

| | |
|---|---|
| `GET /api/health` | `{"ok":true,"version":"…"}` |
| `GET /api/entries` | All entries, with `server_time`. |
| `GET /api/entries?since=<server_time>` | Entries changed since then; deleted ones come back with `"deleted":true`. |
| `GET /api/entries/<id>/audio` | The MP3. |

### Server settings

Everything is set on the admin page and stored in the database. The
environment (`/etc/devotion/devotion.env` in the container) only holds
`PORT`, `DATA_DIR`, `GITHUB_REPO`, the Piper paths, and optionally
`ADMIN_PASSWORD` to fix the password instead of choosing it in the browser.
