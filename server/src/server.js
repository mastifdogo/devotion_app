// HTTP: the app's API (/api, bearer token), the admin page (/admin, password
// + session cookie) and Google's OAuth callback.
import http from 'node:http';
import { createReadStream, readFileSync } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createHash, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ADMIN_HTML = readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'admin.html'));
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

export function entryJson(row) {
	return {
		id: row.id,
		title: row.title,
		entry_date: row.entry_date,
		scripture: row.scripture,
		summary: row.summary,
		body: row.body,
		has_audio: Boolean(row.audio_file),
		audio_bytes: row.audio_bytes ?? 0,
		audio_sha256: row.audio_sha256,
		source_from: row.source_from,
		source_subject: row.source_subject,
		created_at: row.created_at,
		updated_at: row.updated_at,
		deleted: Boolean(row.deleted_at),
	};
}

export function createServer({ config, db, settings, gmail, pipeline, tts, updater, audioDir, log = console }) {
	const sessions = new Map(); // sid -> expiry
	let loginFailures = 0;

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
	const requireApp = (req) => {
		const token = /^Bearer\s+(\S+)$/i.exec(req.headers.authorization || '')?.[1];
		if (!token || !sameSecret(token, settings.appToken())) throw new HttpError(401, 'Missing or wrong API token.');
	};

	const adminState = () => ({
		version: config.version,
		settings: settings.publicView(),
		app_token: settings.appToken(),
		gmail: { configured: gmail.configured(), connected: gmail.connected(), email: settings.get('google_email'), redirect_uri: gmail.redirectUri() },
		pipeline: { ...pipeline.status, not_ready: pipeline.notReady(), audio_not_ready: tts.notReady() },
		can_install_updates: updater.canInstall(),
		password_fixed: Boolean(config.adminPassword),
		entries: db.prepare('SELECT id, title, entry_date, scripture, audio_file, audio_bytes, audio_error, source_subject, created_at FROM entries WHERE deleted_at IS NULL ORDER BY COALESCE(entry_date, created_at) DESC, id DESC LIMIT 100').all()
			.map((e) => ({ ...e, has_audio: Boolean(e.audio_file), audio_file: undefined })),
		messages: db.prepare('SELECT * FROM messages ORDER BY processed_at DESC LIMIT 30').all(),
	});

	const routes = [];
	const route = (method, pattern, handler) => routes.push({ method, pattern, handler });

	// --- The app's API ----------------------------------------------------------
	route('GET', /^\/api\/health$/, (req, res) => send(res, 200, { ok: true, version: config.version }));

	route('GET', /^\/api\/entries$/, (req, res, m, url) => {
		requireApp(req);
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
		send(res, 200, { entries: rows.map(entryJson), server_time: serverTime });
	});

	route('GET', /^\/api\/entries\/(\d+)$/, (req, res, m) => {
		requireApp(req);
		const row = db.prepare('SELECT * FROM entries WHERE id = ? AND deleted_at IS NULL').get(Number(m[1]));
		if (!row) throw new HttpError(404, 'No such entry.');
		send(res, 200, entryJson(row));
	});

	route('GET', /^\/api\/entries\/(\d+)\/audio$/, async (req, res, m) => {
		requireApp(req);
		const row = db.prepare('SELECT audio_file, audio_sha256 FROM entries WHERE id = ? AND deleted_at IS NULL').get(Number(m[1]));
		if (!row?.audio_file) throw new HttpError(404, 'This entry has no audio yet.');
		const file = path.join(audioDir, path.basename(row.audio_file));
		const info = await stat(file).catch(() => null);
		if (!info) throw new HttpError(404, 'The audio file is missing on the server.');
		res.writeHead(200, { 'content-type': 'audio/mpeg', 'content-length': info.size, etag: `"${row.audio_sha256}"`, 'cache-control': 'private, max-age=31536000, immutable' });
		createReadStream(file).on('error', () => res.destroy()).pipe(res);
	});

	// --- Admin ------------------------------------------------------------------
	route('GET', /^\/$/, (req, res) => send(res, 302, '', { location: '/admin' }));
	route('GET', /^\/admin\/?$/, (req, res) => send(res, 200, ADMIN_HTML, { 'content-security-policy': "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'" }));

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

	route('GET', /^\/admin\/api\/state$/, (req, res) => {
		requireAdmin(req);
		send(res, 200, adminState());
	});

	route('POST', /^\/admin\/api\/settings$/, async (req, res) => {
		requireAdmin(req);
		settings.applyForm(await readJson(req));
		send(res, 200, adminState());
	});

	route('POST', /^\/admin\/api\/token\/rotate$/, async (req, res) => {
		requireAdmin(req);
		await readJson(req);
		settings.rotateAppToken();
		send(res, 200, adminState());
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
		send(res, 200, adminState());
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
		send(res, 200, adminState());
	});

	route('POST', /^\/admin\/api\/entries\/(\d+)\/audio$/, async (req, res, m) => {
		requireAdmin(req);
		await readJson(req);
		const blocked = tts.notReady();
		if (blocked) throw new HttpError(409, blocked);
		const entry = db.prepare('SELECT * FROM entries WHERE id = ? AND deleted_at IS NULL').get(Number(m[1]));
		if (!entry) throw new HttpError(404, 'No such entry.');
		pipeline.makeAudio(entry).catch(() => {});
		send(res, 202, { ok: true });
	});

	route('POST', /^\/admin\/api\/messages\/([A-Za-z0-9_-]+)\/retry$/, async (req, res, m) => {
		requireAdmin(req);
		await readJson(req);
		pipeline.retryMessage(m[1]);
		pipeline.runOnce().catch(() => {});
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
			if (status >= 500 && !['UpdateError'].includes(err.name)) log.error(`${req.method} ${url.pathname}:`, err.stack || err.message);
			if (res.headersSent) return res.destroy();
			send(res, status, { error: status === 500 ? 'Something went wrong on the server; see its log.' : err.message });
		}
	});
}
