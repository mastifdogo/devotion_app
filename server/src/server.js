// HTTP: the app's API (/api, bearer token), the admin page (/admin, password
// + session cookie) and Google's OAuth callback.
import http from 'node:http';
import { createReadStream, readFileSync } from 'node:fs';
import { createGzip } from 'node:zlib';
import { stat } from 'node:fs/promises';
import { createHash, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';
import { totalmem } from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { TOPICS } from './wallpapers.js';

const page = (name) => readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), name));
const ADMIN_HTML = page('admin.html');
const START_HTML = page('start.html');
const READ_HTML = page('read.html');
const PAGE_CSP = "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'";
const SESSION_MS = 12 * 60 * 60 * 1000;

class HttpError extends Error {
	constructor(status, message) {
		super(message);
		this.status = status;
	}
}

// Compares hashes, so neither length nor content leaks through timing.
const sameSecret = (a, b) => timingSafeEqual(createHash('sha256').update(String(a)).digest(), createHash('sha256').update(String(b)).digest());
const escapeHtml = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

export function hashPassword(password) {
	const salt = randomBytes(16);
	return `scrypt$${salt.toString('base64url')}$${scryptSync(password, salt, 32).toString('base64url')}`;
}

export function verifyPassword(password, stored) {
	const [scheme, salt, hash] = String(stored).split('$');
	if (scheme !== 'scrypt' || !salt || !hash) return false;
	const expected = Buffer.from(hash, 'base64url');
	const actual = scryptSync(String(password), Buffer.from(salt, 'base64url'), expected.length || 32);
	return expected.length === actual.length && timingSafeEqual(expected, actual);
}

function parseRefs(text) {
	try {
		const list = JSON.parse(text || '[]');
		return Array.isArray(list) ? list : [];
	} catch {
		return [];
	}
}

/**
 * One entry as the app sees it. `audio` lists every voice the entry can be
 * heard in; has_audio / audio_bytes / audio_sha256 describe the default voice,
 * for apps from before there was a choice.
 */
export function entryJson(row, audio = [], defaultVoice = '') {
	const main = audio.find((a) => a.voice === defaultVoice) || audio[0];
	return {
		id: row.id,
		kind: row.kind === 'lesson' ? 'lesson' : 'daily_reading',
		lesson_number: row.lesson_number ?? null,
		title: row.title,
		entry_date: row.entry_date,
		scripture: row.scripture,
		summary: row.summary ?? '',
		body: row.body,
		references: parseRefs(row.refs),
		has_audio: Boolean(main),
		audio_bytes: main?.bytes ?? 0,
		audio_sha256: main?.sha256 ?? null,
		audio: audio.map((a) => ({ voice: a.voice, bytes: a.bytes, sha256: a.sha256 })),
		source_from: row.source_from,
		source_subject: row.source_subject,
		created_at: row.created_at,
		updated_at: row.updated_at,
		archived: Boolean(row.archived_at),
		deleted: Boolean(row.deleted_at),
	};
}

const COLORS = new Set(['yellow', 'green', 'blue', 'pink', 'none']);

export function annotationJson(row) {
	return { id: row.id, entry_id: row.entry_id, start: row.start, end: row.end, color: row.color, comment: row.comment, updated_at: row.updated_at, deleted: Boolean(row.deleted) };
}

/** Checks an annotation sent by the app; returns it cleaned or throws. */
export function parseAnnotation(id, body) {
	const bad = (what) => Object.assign(new Error(`The annotation's ${what} is not valid.`), { status: 400 });
	if (!/^[A-Za-z0-9-]{8,64}$/.test(id)) throw bad('id');
	const int = (v) => Number.isInteger(v) && v >= 0 && v <= 10_000_000;
	if (!Number.isInteger(body.entry_id) || body.entry_id < 1) throw bad('entry_id');
	if (!int(body.start) || !int(body.end) || body.end < body.start) throw bad('range');
	if (!COLORS.has(body.color)) throw bad('color');
	if (typeof body.comment !== 'string' || body.comment.length > 20_000) throw bad('comment');
	const time = typeof body.updated_at === 'string' ? Date.parse(body.updated_at) : NaN;
	if (Number.isNaN(time)) throw bad('updated_at');
	return { id, entry_id: body.entry_id, start: body.start, end: body.end, color: body.color, comment: body.comment, updated_at: new Date(time).toISOString(), deleted: body.deleted === true ? 1 : 0 };
}

export function createServer({ config, db, settings, gmail, pipeline, tts, updater, voices, bibles, archiver, wallpapers, audioDir, log = console }) {
	const sessions = new Map(); // sid -> expiry
	let loginFailures = 0;
	let lastBackgroundError = null; // shown once by the admin page, then cleared

	const send = (res, status, body, headers = {}) => {
		const data = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
		res.writeHead(status, {
			'content-type': typeof body === 'string' || Buffer.isBuffer(body) ? 'text/html; charset=utf-8' : 'application/json; charset=utf-8',
			'cache-control': 'no-store',
			'x-content-type-options': 'nosniff',
			...headers,
		});
		res.end(data);
	};

	const readJson = async (req) => {
		if (!/^application\/json\b/i.test(req.headers['content-type'] || '')) throw new HttpError(415, 'Send JSON.');
		const chunks = [];
		let size = 0;
		for await (const chunk of req) {
			size += chunk.length;
			if (size > 1_000_000) throw new HttpError(413, 'Too large.');
			chunks.push(chunk);
		}
		if (!size) return {};
		try {
			const parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
			if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error();
			return parsed;
		} catch {
			throw new HttpError(400, 'Not valid JSON.');
		}
	};

	// The admin password: fixed by ADMIN_PASSWORD if that is set, otherwise
	// chosen in the browser on the first visit and stored as a scrypt hash.
	const passwordSet = () => Boolean(config.adminPassword) || settings.get('admin_password_hash') !== '';
	const passwordOk = (password) => {
		if (typeof password !== 'string') return false;
		if (config.adminPassword) return sameSecret(password, config.adminPassword);
		return verifyPassword(password, settings.get('admin_password_hash'));
	};
	const checkNewPassword = (password) => {
		if (typeof password !== 'string' || password.length < 8) throw new HttpError(400, 'Choose a password of at least 8 characters.');
		if (password.length > 200) throw new HttpError(400, 'That password is too long.');
	};
	const startSession = (res) => {
		for (const [sid, exp] of sessions) if (exp < Date.now()) sessions.delete(sid);
		const sid = randomBytes(24).toString('base64url');
		sessions.set(sid, Date.now() + SESSION_MS);
		send(res, 200, { ok: true }, { 'set-cookie': `sid=${sid}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${SESSION_MS / 1000}` });
	};
	const slowDown = async () => {
		// Each wrong guess makes the next answer slower, up to 5 s.
		loginFailures = Math.min(loginFailures + 1, 10);
		await new Promise((r) => setTimeout(r, loginFailures * 500));
	};

	const sessionId = (req) => /(?:^|;\s*)sid=([A-Za-z0-9_-]+)/.exec(req.headers.cookie || '')?.[1];
	const requireAdmin = (req) => {
		const sid = sessionId(req);
		const expiry = sid && sessions.get(sid);
		if (!expiry || expiry < Date.now()) {
			if (sid) sessions.delete(sid);
			throw new HttpError(401, 'Log in first.');
		}
	};
	const adminState = async () => ({
		voices: await voices.list(),
		background_error: (() => { const e = lastBackgroundError; lastBackgroundError = null; return e; })(),
		...adminStateSync(),
	});
	const adminStateSync = () => ({
		version: config.version,
		settings: settings.publicView(),
		gmail: { configured: gmail.configured(), connected: gmail.connected(), email: settings.get('google_email'), redirect_uri: gmail.redirectUri() },
		pipeline: { ...pipeline.status, not_ready: pipeline.notReady(), audio_not_ready: tts.notReady(), audio_progress: pipeline.audioProgress },
		// In a Proxmox container this is the container's memory limit.
		memory_mb: Math.round((config.totalMemory ?? totalmem()) / 1048576),
		can_install_updates: updater.canInstall(),
		password_fixed: Boolean(config.adminPassword),
		entries: db.prepare(`SELECT id, kind, lesson_number, label_manual, title, entry_date, scripture, source_subject, created_at,
				(SELECT COUNT(*) FROM entry_audio a WHERE a.entry_id = e.id AND a.file IS NOT NULL) AS audio_count,
				(SELECT COALESCE(SUM(bytes), 0) FROM entry_audio a WHERE a.entry_id = e.id AND a.file IS NOT NULL) AS audio_bytes,
				(SELECT error FROM entry_audio a WHERE a.entry_id = e.id AND a.error IS NOT NULL LIMIT 1) AS audio_error
			FROM entries e WHERE deleted_at IS NULL AND archived_at IS NULL ORDER BY COALESCE(entry_date, created_at) DESC, id DESC LIMIT 100`).all(),
		voice_count: tts.voiceList().length,
		messages: db.prepare('SELECT * FROM messages ORDER BY processed_at DESC LIMIT 30').all(),
		profiles: db.prepare(`SELECT p.id, p.name, p.created_at, (SELECT COUNT(*) FROM annotations a WHERE a.profile_id = p.id AND a.deleted = 0) AS notes FROM profiles p ORDER BY p.name`).all(),
		bibles: bibles.list(),
		archive: { ...archiver.status, configured: archiver.configured(), running: archiver.running, waiting: archiver.due().length, archived_total: db.prepare('SELECT COUNT(*) AS n FROM entries WHERE archived_at IS NOT NULL AND deleted_at IS NULL').get().n },
	});

	const audioRows = db.prepare('SELECT voice, file, bytes, sha256 FROM entry_audio WHERE entry_id = ? AND file IS NOT NULL ORDER BY voice');
	const withAudio = (rows) => {
		const byEntry = new Map();
		for (const a of db.prepare('SELECT entry_id, voice, file, bytes, sha256 FROM entry_audio WHERE file IS NOT NULL ORDER BY voice').all()) {
			if (!byEntry.has(a.entry_id)) byEntry.set(a.entry_id, []);
			byEntry.get(a.entry_id).push(a);
		}
		const def = tts.defaultVoiceId();
		return rows.map((r) => entryJson(r, r.deleted_at || r.archived_at ? [] : byEntry.get(r.id) || [], def));
	};

	const routes = [];
	const route = (method, pattern, handler) => routes.push({ method, pattern, handler });

	// --- The app's API ----------------------------------------------------------
	route('GET', /^\/api\/health$/, (req, res) => send(res, 200, { ok: true, version: config.version }));

	route('GET', /^\/api\/entries$/, (req, res, m, url) => {
		const serverTime = new Date().toISOString();
		const since = url.searchParams.get('since');
		let rows;
		if (since) {
			if (Number.isNaN(Date.parse(since))) throw new HttpError(400, '"since" is not a timestamp.');
			// Changes after `since`, deletions included, so the app can drop them.
			rows = db.prepare('SELECT * FROM entries WHERE updated_at > ? ORDER BY id').all(new Date(since).toISOString());
		} else {
			rows = db.prepare('SELECT * FROM entries WHERE deleted_at IS NULL ORDER BY id').all();
		}
		send(res, 200, { entries: withAudio(rows), server_time: serverTime });
	});

	route('GET', /^\/api\/entries\/(\d+)$/, (req, res, m) => {
		const row = db.prepare('SELECT * FROM entries WHERE id = ? AND deleted_at IS NULL').get(Number(m[1]));
		if (!row) throw new HttpError(404, 'No such entry.');
		send(res, 200, withAudio([row])[0]);
	});

	route('GET', /^\/api\/voices$/, (req, res) => {
		send(res, 200, { voices: tts.voiceList(), default: tts.defaultVoiceId() || null });
	});

	// ?voice=<id> picks the voice; without it, the server's default voice.
	route('GET', /^\/api\/entries\/(\d+)\/audio$/, async (req, res, m, url) => {
		const live = db.prepare('SELECT 1 FROM entries WHERE id = ? AND deleted_at IS NULL AND archived_at IS NULL').get(Number(m[1]));
		const rows = live ? audioRows.all(Number(m[1])) : [];
		const wanted = url.searchParams.get('voice');
		const row = wanted ? rows.find((r) => r.voice === wanted) : rows.find((r) => r.voice === tts.defaultVoiceId()) || rows[0];
		if (!row) throw new HttpError(404, wanted ? 'This entry has no audio in that voice.' : 'This entry has no audio yet.');
		const file = path.join(audioDir, path.basename(row.file));
		const info = await stat(file).catch(() => null);
		if (!info) throw new HttpError(404, 'The audio file is missing on the server.');
		res.writeHead(200, { 'content-type': 'audio/mpeg', 'content-length': info.size, etag: `"${row.audio_sha256}"`, 'cache-control': 'private, max-age=31536000, immutable' });
		createReadStream(file).on('error', () => res.destroy()).pipe(res);
	});

	// Profiles: who is reading. There is no login on the app's side; a profile
	// is just a name that notes and highlights are kept under.
	const profileJson = (p) => ({ id: p.id, name: p.name, created_at: p.created_at });
	const requireProfile = (id) => {
		const profile = db.prepare('SELECT * FROM profiles WHERE id = ?').get(Number(id));
		if (!profile) throw new HttpError(404, 'No such profile.');
		return profile;
	};

	route('GET', /^\/api\/profiles$/, (req, res) => {
		send(res, 200, { profiles: db.prepare('SELECT * FROM profiles ORDER BY name').all().map(profileJson) });
	});

	route('POST', /^\/api\/profiles$/, async (req, res) => {
		const body = await readJson(req);
		const name = typeof body.name === 'string' ? body.name.replace(/\s+/g, ' ').trim() : '';
		if (!name) throw new HttpError(400, 'Enter a name.');
		if (name.length > 60) throw new HttpError(400, 'That name is too long.');
		const key = name.toLowerCase();
		const existing = db.prepare('SELECT * FROM profiles WHERE name_key = ?').get(key);
		if (existing) return send(res, 200, { ...profileJson(existing), existing: true });
		if (db.prepare('SELECT COUNT(*) AS n FROM profiles').get().n >= 200) throw new HttpError(400, 'There are too many profiles on this server; remove some on its admin page.');
		const info = db.prepare('INSERT INTO profiles (name, name_key, created_at) VALUES (?, ?, ?)').run(name, key, new Date().toISOString());
		send(res, 201, { ...profileJson(requireProfile(info.lastInsertRowid)), existing: false });
	});

	route('GET', /^\/api\/profiles\/(\d+)\/annotations$/, (req, res, m, url) => {
		const profile = requireProfile(m[1]);
		const serverTime = new Date().toISOString();
		const since = url.searchParams.get('since');
		if (since && Number.isNaN(Date.parse(since))) throw new HttpError(400, '"since" is not a timestamp.');
		const rows = since
			? db.prepare('SELECT * FROM annotations WHERE profile_id = ? AND server_updated_at > ? ORDER BY server_updated_at').all(profile.id, new Date(since).toISOString())
			: db.prepare('SELECT * FROM annotations WHERE profile_id = ? AND deleted = 0 ORDER BY entry_id, start').all(profile.id);
		send(res, 200, { annotations: rows.map(annotationJson), server_time: serverTime });
	});

	route('PUT', /^\/api\/profiles\/(\d+)\/annotations\/([^/]+)$/, async (req, res, m) => {
		const profile = requireProfile(m[1]);
		const incoming = parseAnnotation(m[2], await readJson(req));
		const stored = db.prepare('SELECT * FROM annotations WHERE profile_id = ? AND id = ?').get(profile.id, incoming.id);
		// The later edit wins, so two devices on one profile can't undo each other with stale data.
		if (!stored || incoming.updated_at >= stored.updated_at) {
			db.prepare(`INSERT INTO annotations (profile_id, id, entry_id, start, end, color, comment, updated_at, deleted, server_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT(profile_id, id) DO UPDATE SET entry_id = excluded.entry_id, start = excluded.start, end = excluded.end, color = excluded.color,
					comment = excluded.comment, updated_at = excluded.updated_at, deleted = excluded.deleted, server_updated_at = excluded.server_updated_at`)
				.run(profile.id, incoming.id, incoming.entry_id, incoming.start, incoming.end, incoming.color, incoming.comment, incoming.updated_at, incoming.deleted, new Date().toISOString());
		}
		send(res, 200, annotationJson(db.prepare('SELECT * FROM annotations WHERE profile_id = ? AND id = ?').get(profile.id, incoming.id)));
	});

	// Bible versions stored on this server, for the app to download.
	route('GET', /^\/api\/bibles$/, (req, res) => send(res, 200, { bibles: bibles.installed() }));

	route('GET', /^\/api\/bibles\/([a-z0-9]+)$/, async (req, res, m) => {
		const bible = bibles.get(m[1]);
		const info = bible && (await stat(bibles.file(bible.id)).catch(() => null));
		if (!info) throw new HttpError(404, 'This Bible version is not on the server.');
		const headers = { 'content-type': 'application/json; charset=utf-8', etag: `"${bible.sha256}"`, 'cache-control': 'private, max-age=86400' };
		const stream = createReadStream(bibles.file(bible.id)).on('error', () => res.destroy());
		// Several megabytes of text: compressed when the client can take it.
		if (/\bgzip\b/.test(req.headers['accept-encoding'] || '')) {
			res.writeHead(200, { ...headers, 'content-encoding': 'gzip', vary: 'accept-encoding' });
			stream.pipe(createGzip()).pipe(res);
		} else {
			res.writeHead(200, { ...headers, 'content-length': info.size });
			stream.pipe(res);
		}
	});

	// Wallpapers on this server: the phone gets them only from here.
	route('GET', /^\/api\/wallpapers$/, (req, res) => send(res, 200, (({ topics, wallpapers: list }) => ({ topics, wallpapers: list.map(({ topic_name, added_at, ...w }) => w) }))(wallpapers.list())));

	route('GET', /^\/api\/wallpapers\/([A-Za-z0-9]{1,16})(\/thumb)?$/, async (req, res, m) => {
		const row = wallpapers.row(m[1]);
		const [file, type] = m[2] ? [row?.thumb_file, row?.thumb_type] : [row?.file, row?.type];
		const info = file && (await stat(wallpapers.file(file)).catch(() => null));
		if (!info) throw new HttpError(404, 'No such wallpaper on this server.');
		res.writeHead(200, { 'content-type': type, 'content-length': info.size, etag: `"${row.sha256}${m[2] ? '-t' : ''}"`, 'cache-control': 'private, max-age=86400' });
		createReadStream(wallpapers.file(file)).on('error', () => res.destroy()).pipe(res);
	});

	// --- Admin ------------------------------------------------------------------
	// The start page: read and listen, or sign in to the admin panel.
	route('GET', /^\/$/, (req, res) => send(res, 200, START_HTML, { 'content-security-policy': PAGE_CSP }));
	route('GET', /^\/read\/?$/, (req, res) => send(res, 200, READ_HTML, { 'content-security-policy': PAGE_CSP }));
	route('GET', /^\/admin\/?$/, (req, res) => send(res, 200, ADMIN_HTML, { 'content-security-policy': "default-src 'self'; img-src 'self' https://th.wallhaven.cc; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'" }));

	// Tells the page which form to show before anyone is logged in.
	route('GET', /^\/admin\/api\/session$/, (req, res) => {
		const expiry = sessions.get(sessionId(req));
		send(res, 200, { version: config.version, needs_setup: !passwordSet(), logged_in: Boolean(expiry && expiry > Date.now()) });
	});

	// First visit: whoever opens the page first chooses the admin password.
	// Only possible while no password exists.
	route('POST', /^\/admin\/api\/setup$/, async (req, res) => {
		const { password } = await readJson(req);
		if (passwordSet()) throw new HttpError(409, 'This server is already set up. Log in instead.');
		checkNewPassword(password);
		settings.set('admin_password_hash', hashPassword(password));
		startSession(res);
	});

	route('POST', /^\/admin\/api\/login$/, async (req, res) => {
		const { password } = await readJson(req);
		if (!passwordSet()) throw new HttpError(409, 'No password has been chosen yet. Reload the page to set one.');
		if (!passwordOk(password)) {
			await slowDown();
			throw new HttpError(401, 'Wrong password.');
		}
		loginFailures = 0;
		startSession(res);
	});

	route('POST', /^\/admin\/api\/password$/, async (req, res) => {
		requireAdmin(req);
		const { current, password } = await readJson(req);
		if (config.adminPassword) throw new HttpError(409, 'The password is fixed by ADMIN_PASSWORD in the server\'s environment and cannot be changed here.');
		if (!passwordOk(current)) {
			await slowDown();
			throw new HttpError(403, 'The current password is wrong.');
		}
		checkNewPassword(password);
		settings.set('admin_password_hash', hashPassword(password));
		// Everyone else is logged out; this browser stays in.
		const mine = sessionId(req);
		for (const sid of sessions.keys()) if (sid !== mine) sessions.delete(sid);
		send(res, 200, { ok: true });
	});

	route('POST', /^\/admin\/api\/logout$/, (req, res) => {
		sessions.delete(sessionId(req));
		send(res, 200, { ok: true }, { 'set-cookie': 'sid=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0' });
	});

	route('GET', /^\/admin\/api\/state$/, async (req, res) => {
		requireAdmin(req);
		send(res, 200, await adminState());
	});

	route('POST', /^\/admin\/api\/settings$/, async (req, res) => {
		requireAdmin(req);
		settings.applyForm(await readJson(req));
		send(res, 200, await adminState());
	});

	route('POST', /^\/admin\/api\/oauth\/start$/, async (req, res) => {
		requireAdmin(req);
		await readJson(req);
		send(res, 200, { url: gmail.startAuth(), redirect_uri: gmail.redirectUri() });
	});

	// For servers Google can't redirect to: the admin pastes the address their
	// browser ended up on (http://localhost:…/oauth/callback?code=…).
	route('POST', /^\/admin\/api\/oauth\/complete$/, async (req, res) => {
		requireAdmin(req);
		const { url: pasted } = await readJson(req);
		let params;
		try {
			params = new URL(String(pasted).trim()).searchParams;
		} catch {
			throw new HttpError(400, 'Paste the whole address from the browser\'s address bar, starting with http.');
		}
		const email = await gmail.finishAuth(params);
		send(res, 200, { ok: true, email });
	});

	route('POST', /^\/admin\/api\/oauth\/disconnect$/, async (req, res) => {
		requireAdmin(req);
		await readJson(req);
		gmail.disconnect();
		send(res, 200, await adminState());
	});

	// Google's redirect. No session is needed (the browser arrives from another
	// site): the single-use `state` value proves this server started the flow.
	route('GET', /^\/oauth\/callback$/, async (req, res, m, url) => {
		const page = (title, text) => `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)}</title><body style="font:16px system-ui;max-width:32rem;margin:15vh auto;padding:0 1rem"><h1 style="font-size:1.3rem">${escapeHtml(title)}</h1><p>${escapeHtml(text)}</p><p><a href="/admin">Back to the admin page</a></p>`;
		try {
			const email = await gmail.finishAuth(url.searchParams);
			pipeline.runOnce().catch(() => {});
			send(res, 200, page('Gmail connected', `The server can now read mail for ${email}.`));
		} catch (err) {
			send(res, 400, page('Gmail was not connected', err.message));
		}
	});

	route('POST', /^\/admin\/api\/run$/, async (req, res) => {
		requireAdmin(req);
		await readJson(req);
		const blocked = pipeline.notReady();
		if (blocked) throw new HttpError(409, blocked);
		// Runs in the background; the page polls the state.
		pipeline.runOnce().catch(() => {});
		send(res, 202, { ok: true });
	});

	route('POST', /^\/admin\/api\/audio\/retry$/, async (req, res) => {
		requireAdmin(req);
		await readJson(req);
		const blocked = tts.notReady();
		if (blocked) throw new HttpError(409, blocked);
		pipeline.fillAudio({ retryFailed: true }).catch((err) => log.error('audio retry:', err.message));
		send(res, 202, { ok: true });
	});

	route('POST', /^\/admin\/api\/entries\/(\d+)\/delete$/, async (req, res, m) => {
		requireAdmin(req);
		await readJson(req);
		if (!(await pipeline.deleteEntry(Number(m[1])))) throw new HttpError(404, 'No such entry.');
		send(res, 200, await adminState());
	});

	// Correct an entry's tab, lesson number, date or title by hand.
	route('POST', /^\/admin\/api\/entries\/(\d+)\/label$/, async (req, res, m) => {
		requireAdmin(req);
		const body = await readJson(req);
		let label = 'auto';
		if (!body.auto) {
			const kind = body.kind;
			if (kind !== 'lesson' && kind !== 'daily_reading') throw new HttpError(400, 'Choose Daily reading or Lesson.');
			const number = body.lesson_number === null || body.lesson_number === '' || body.lesson_number === undefined ? null : Number(body.lesson_number);
			if (number !== null && !(Number.isInteger(number) && number > 0 && number < 10000)) throw new HttpError(400, 'The lesson number must be a whole number.');
			const date = typeof body.entry_date === 'string' && body.entry_date.trim() ? body.entry_date.trim() : null;
			if (date !== null && (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(date)))) throw new HttpError(400, 'The date must be a real date.');
			const title = typeof body.title === 'string' ? body.title.replace(/\s+/g, ' ').trim() : '';
			if (!title || title.length > 200) throw new HttpError(400, 'Enter a title.');
			label = { kind, lesson_number: number, entry_date: date, title };
		}
		if (!pipeline.relabel(Number(m[1]), label)) throw new HttpError(404, 'No such entry.');
		send(res, 200, await adminState());
	});

	route('POST', /^\/admin\/api\/entries\/(\d+)\/audio$/, async (req, res, m) => {
		requireAdmin(req);
		await readJson(req);
		const blocked = tts.notReady();
		if (blocked) throw new HttpError(409, blocked);
		const entry = db.prepare('SELECT * FROM entries WHERE id = ? AND deleted_at IS NULL').get(Number(m[1]));
		if (!entry) throw new HttpError(404, 'No such entry.');
		pipeline.remakeAudio(entry).catch(() => {});
		send(res, 202, { ok: true });
	});

	route('POST', /^\/admin\/api\/messages\/([A-Za-z0-9_-]+)\/retry$/, async (req, res, m) => {
		requireAdmin(req);
		await readJson(req);
		pipeline.retryMessage(m[1]);
		pipeline.runOnce().catch(() => {});
		send(res, 202, { ok: true });
	});

	route('POST', /^\/admin\/api\/profiles\/(\d+)\/delete$/, async (req, res, m) => {
		requireAdmin(req);
		await readJson(req);
		db.prepare('DELETE FROM annotations WHERE profile_id = ?').run(Number(m[1]));
		db.prepare('DELETE FROM profiles WHERE id = ?').run(Number(m[1]));
		send(res, 200, await adminState());
	});

	// Downloads run in the background; the page polls the state for progress.
	const background = (promise, what) => promise.catch((err) => {
		log.error(`${what}: ${err.message}`);
		lastBackgroundError = `${what}: ${err.message}`;
	});
	// install: download a voice, after which every entry is also made in it.
	// select: make it the default (what new listeners and older apps hear).
	// delete: remove it and every entry's audio in it.
	route('POST', /^\/admin\/api\/voices\/([A-Za-z0-9_-]+)\/(install|select|delete)$/, async (req, res, m) => {
		requireAdmin(req);
		await readJson(req);
		const [, id, action] = m;
		const before = tts.defaultVoiceId();
		if (action === 'delete') {
			await voices.remove(id);
			await pipeline.removeVoiceAudio(id);
		} else if (voices.isInstalled(id)) {
			if (action === 'select') await voices.select(id);
		} else {
			// Downloads run in the background; audio in the new voice follows.
			background(voices[action](id).then(() => {
				if (tts.defaultVoiceId() !== before) pipeline.touchAll();
				return pipeline.fillAudio();
			}), 'Voice download');
		}
		if (tts.defaultVoiceId() !== before) pipeline.touchAll();
		send(res, 202, await adminState());
	});

	route('POST', /^\/admin\/api\/bibles\/([a-z0-9]+)\/(install|delete)$/, async (req, res, m) => {
		requireAdmin(req);
		await readJson(req);
		if (m[2] === 'delete') await bibles.remove(m[1]);
		else if (!bibles.list().some((b) => b.id === m[1])) throw new HttpError(404, 'No such Bible version.');
		else background(bibles.install(m[1]), 'Bible download');
		send(res, 202, await adminState());
	});

	const searchOptions = (b) => ({
		q: typeof b.q === 'string' ? b.q : '',
		kinds: { general: Boolean(b.kinds?.general), anime: Boolean(b.kinds?.anime), people: Boolean(b.kinds?.people) },
		order: ['top', 'latest', 'random'].includes(b.order) ? b.order : 'top',
		portrait: b.portrait !== false,
		page: Number(b.page) || 1,
		seed: typeof b.seed === 'string' ? b.seed : '',
	});
	route('GET', /^\/admin\/api\/wallpapers$/, (req, res) => {
		requireAdmin(req);
		send(res, 200, { catalog: TOPICS, ...wallpapers.list() });
	});
	route('POST', /^\/admin\/api\/wallpapers\/search$/, async (req, res) => {
		requireAdmin(req);
		send(res, 200, await wallpapers.search(searchOptions(await readJson(req))));
	});
	route('POST', /^\/admin\/api\/wallpapers\/add$/, async (req, res) => {
		requireAdmin(req);
		const b = await readJson(req);
		if (Array.isArray(b.ids)) {
			// Several at once: downloaded in the background.
			const ids = b.ids.filter((id) => typeof id === 'string').slice(0, 48);
			background((async () => { for (const id of ids) await wallpapers.add(id, b.topic); })(), 'Wallpaper download');
			return send(res, 202, { ok: true, queued: ids.length });
		}
		send(res, 200, await wallpapers.add(String(b.id || ''), b.topic));
	});
	route('POST', /^\/admin\/api\/wallpapers\/add-top$/, async (req, res) => {
		requireAdmin(req);
		const b = await readJson(req);
		background(wallpapers.addTop(searchOptions(b), b.topic, Number(b.count) || 12).then((r) => {
			if (r.errors.length) throw new Error(`${r.added} added; ${r.errors.length} failed (${r.errors[0]})`);
		}), 'Wallpaper download');
		send(res, 202, { ok: true });
	});
	route('POST', /^\/admin\/api\/wallpapers\/([A-Za-z0-9]{1,16})\/delete$/, async (req, res, m) => {
		requireAdmin(req);
		await readJson(req);
		if (!(await wallpapers.remove(m[1]))) throw new HttpError(404, 'No such wallpaper on this server.');
		send(res, 200, { catalog: TOPICS, ...wallpapers.list() });
	});

	route('POST', /^\/admin\/api\/archive\/test$/, async (req, res) => {
		requireAdmin(req);
		await readJson(req);
		send(res, 200, await archiver.test());
	});

	route('POST', /^\/admin\/api\/archive\/run$/, async (req, res) => {
		requireAdmin(req);
		await readJson(req);
		if (!archiver.configured()) throw new HttpError(409, 'Enter and save the share first.');
		background(archiver.runOnce(), 'Archive');
		send(res, 202, { ok: true });
	});

	route('GET', /^\/admin\/api\/update\/check$/, async (req, res) => {
		requireAdmin(req);
		send(res, 200, await updater.check());
	});

	route('POST', /^\/admin\/api\/update\/install$/, async (req, res) => {
		requireAdmin(req);
		const { tag } = await readJson(req);
		await updater.requestInstall(String(tag || ''));
		send(res, 202, { ok: true });
	});

	return http.createServer(async (req, res) => {
		let url;
		try {
			url = new URL(req.url, 'http://localhost');
		} catch {
			return send(res, 400, { error: 'Bad request.' });
		}
		try {
			for (const r of routes) {
				const m = r.pattern.exec(url.pathname);
				if (!m) continue;
				if (r.method !== req.method) continue;
				return await r.handler(req, res, m, url);
			}
			throw new HttpError(routes.some((r) => r.pattern.test(url.pathname)) ? 405 : 404, 'Not found.');
		} catch (err) {
			const status = err.status && err.status >= 400 && err.status < 600 ? err.status : err.name === 'GmailError' ? 400 : 500;
			// Errors this server raises on purpose carry a message meant for the person at the page.
			const ours = ['UpdateError', 'ArchiveError', 'VoiceError', 'BibleError', 'GmailError', 'WallpaperError'].includes(err.name) || err instanceof HttpError;
			if (status >= 500 && !ours) log.error(`${req.method} ${url.pathname}:`, err.stack || err.message);
			if (res.headersSent) return res.destroy();
			send(res, status, { error: status === 500 && !ours ? 'Something went wrong on the server; see its log.' : err.message });
		}
	});
}
