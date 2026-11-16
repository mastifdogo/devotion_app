# Devotion

A small self-hosted server that reads devotional emails from a Gmail inbox,
turns each one into a clean text entry with a short summary (Claude), and
narrates it to an MP3. A companion Android app syncs the entries and keeps
text and audio on the phone for offline reading and listening, with
per-person highlights and notes and downloadable Bible versions.

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
3. Open `http://<container-address>:8787/` in a browser. The start page
   offers **Continue to Devotion** (read and listen in the browser) and the
   **admin panel**. The address is shown
   on the container's console, above the login prompt; there is no need to
   log in on the console.
4. The page asks you to choose an admin password, and then everything is set
   up there, one tab per subject (Status, Entries, Emails, Gmail, Claude,
   Audio, App & people, Wallpapers, Bibles, Archive, Updates, Password):

   - **Gmail**: the OAuth client and the keywords (see below).
   - **Reading the emails**: your Anthropic API key.
   - **Audio**: works as installed (the Piper voice runs inside the container).
     Download more voices and every entry is read in each of them; each person
     picks their voice in the app, and the default voice is what they hear
     until they do. There is also a speaking pace, and an OpenAI-compatible
     speech API can be used instead.
   - **Android app**: shows the address to type into the app, and the people
     who have connected.
   - **Bible versions**: which public-domain translations the server stores
     for the app to download.
   - **Archive**: the network share older entries are moved to.
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

### When the mail is checked

On the **Gmail** tab, *Check for new mail at* takes one or more times of day
(`06:00`, or `06:00, 18:00`), and *Time zone* says whose clock they are on
(the server's own by default; a Proxmox container often runs on UTC, so pick
yours). The tab shows the server's time now and the next check. If the
server was off at a check time, it checks once when it starts again. *Check
mail now* on the Status tab checks straight away.

### Which emails are imported

Only emails that contain at least one of your **keywords** (in the subject,
the sender or the text) are imported; with no keywords set, nothing is read.
The optional *extra Gmail filter* narrows the search further with normal
Gmail search syntax, for example `from:daily@example.org newer_than:30d`.

Each matching email is read once. Claude returns the devotion(s) in it as
entries (title, date, scripture reference, a short summary, full text, and
every Bible reference in the text so the app can link it to the passage).

Sorting into the app's tabs is done by rules, not by Claude, so it comes out
the same every time:

| Subject | Tab | Date | Lesson |
|---|---|---|---|
| `Lesson 11 – Victory After Struggling` | Lessons | the date printed under "Lesson 11" in the email (the Sunday it is taught) | 11 |
| `November 15, 2026 – The struggle is spiritual` | Daily readings | the date in the subject | the lesson named in the reading's heading |
| anything else | Daily readings | the date Claude found, or the day it arrived | none |

A daily reading's scripture is the passage printed under its dated heading.
If one is filed wrong, press **Edit** next to it in the admin page's
*Entries* table to set the tab, lesson number, date or title by hand; the
rules then leave it alone (*Sort automatically* hands it back to them). The
app shows the change on its next refresh.

Claude leaves out the newsletter material around them; an email with no devotion in
it creates nothing. The *Emails read* table shows what happened to each one.

### Archive: keeping only the last month on the server

Under **Archive**, enter an SMB share (`//server/share`), a folder, and a
user that may write there, then press *Test the share*. From then on, after
each mail check, entries older than the set number of days (30 by default,
counted from when the email arrived) are copied to the share as
`<folder>/<year>/<month>/<date>_<id>_<title>.txt` and `.mp3`. Only when the
share lists both files at their full size does the server drop the text and
audio; it keeps the title, date, scripture reference and summary. Phones keep
whatever they had already downloaded. Until a share is set, nothing is moved
or removed.

The share is written with `smbclient`, so nothing has to be mounted in the
container.

### Reading in a browser

The server's address opens a start page with two choices: **Continue to
Devotion** (`/read`), which shows the Daily readings and Lessons tabs, each
entry's summary and text, and plays its audio in any voice the server has at
the speed you choose; and **Sign in to the admin panel** (`/admin`). The
reader needs no sign-in, like the app. Highlights, notes and the Bible are
in the Android app only.

The reader asks for your name the first time (the same profiles as the app)
and has three tabs: **Daily readings**, **Lessons** and **Bible**. The Bible
tab offers the versions you have downloaded in the app (the app tells the
server), or all of the server's if you have none yet; verse references in a
reading are links that open it at the passage. On a desktop the page uses
the whole width: the list, then the reading and the Bible side by side,
each in its own window that scrolls on its own; drag the divider between
them to share the width (double-click it to even them out) and close
either with its ×. On a phone, one at a time. The ⚙ icon opens a settings
page: your profile, the theme, a wallpaper from the server, the window
opacity, and how much darker list items are than the window. The house icon goes back to the start page.

### Wallpapers

Under **Wallpapers**, pick a topic (Nature, Mountains, Ocean, … or your own
words), which kinds (General, Anime, People), the order and whether only
phone-shaped images should show, then *Search Wallhaven*. Tap the ones you
like and press *Add selected* (or *Add the top 12* for the whole first page):
the server downloads them from [wallhaven.cc](https://wallhaven.cc), and the
app's Wallpapers screen shows them by topic. Only safe-for-work images are
ever searched, without a Wallhaven account. The phone never contacts
Wallhaven: it streams wallpapers from this server while it can reach it,
and keeps only the one it uses as the app's background.

### Bible versions

Under **Bible versions**, *Add* downloads a translation to the server; the
app can then download it for offline reading. The list holds public-domain
translations only (King James, American Standard, Young's Literal, Bible in
Basic English, Webster, Geneva, American King James, and the Spanish
Reina-Valera 1909); modern copyrighted translations are not included. Texts
come from [scrollmapper/bible_databases](https://github.com/scrollmapper/bible_databases)
at a fixed commit and are checked against a pinned checksum.

## The Android app

Install the APK from the release page and type in the server's address (just
the IP address is enough). There is no password or token: the app asks each
new person for their name and keeps their highlights and notes under that
profile, on the server, so they follow the person to another device. Because
there is no login, anyone on the network who knows the address can read the
entries and pick any profile: keep the server on your home network and don't
forward its port to the internet.

In an entry, tap a sentence to highlight it or attach a comment. The player
has a speed setting; Settings also has the theme (system, light, dark), the
profile, and **Check for updates**. The Bible screen downloads versions from
the server and opens the entry's scripture reference. The app syncs on start
and on refresh and works offline with what it has.

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

No authentication; meant for a home network.

| | |
|---|---|
| `GET /api/health` | `{"ok":true,"version":"…"}` |
| `GET /api/entries` | All entries, with `server_time`. Archived ones have `"archived":true` and no body or audio. |
| `GET /api/entries?since=<server_time>` | Entries changed since then; deleted ones come back with `"deleted":true`. |
| `GET /api/entries/<id>/audio` | The MP3. |
| `GET` / `POST /api/profiles` | List people; create one from `{"name":"…"}` (an existing name returns that profile). |
| `GET /api/profiles/<id>/annotations[?since=]` | That person's highlights and notes. |
| `PUT /api/profiles/<id>/annotations/<uuid>` | Save one; the later `updated_at` wins. Delete with `"deleted":true`. |
| `GET` / `PUT /api/profiles/<id>/bibles` | The Bible versions that person has downloaded in the app (`{"bibles":["kjv"]}`; PUT replaces the list). |
| `GET /api/bibles`, `GET /api/bibles/<id>` | Bible versions on the server, and one version's text. |
| `GET /api/wallpapers`, `/api/wallpapers/<id>`, `/api/wallpapers/<id>/thumb` | Wallpapers on the server by topic, a full image, a thumbnail. |

### Server settings

Everything is set on the admin page and stored in the database. The
environment (`/etc/devotion/devotion.env` in the container) only holds
`PORT`, `DATA_DIR`, `GITHUB_REPO`, the Piper paths, and optionally
`ADMIN_PASSWORD` to fix the password instead of choosing it in the browser.
