// End to end with the outside world faked: Google, the Anthropic client, the
// speech engine and GitHub. Everything in between is the real code.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync, readFileSync, readdirSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { openDb } from '../src/db.js';
import { Settings } from '../src/settings.js';
import { Gmail } from '../src/gmail.js';
import { Extractor } from '../src/extract.js';
import { Tts, wavFile } from '../src/tts.js';
import { Pipeline } from '../src/pipeline.js';
import { Updater } from '../src/update.js';
import { createServer } from '../src/server.js';
import { Voices } from '../src/voices.js';
import { Bibles } from '../src/bibles.js';
import { BibleAudio, chapterParts } from '../src/bibleaudio.js';
import { Archiver } from '../src/archive.js';
import { Wallpapers } from '../src/wallpapers.js';
import { plan } from '../src/resources.js';

const b64 = (s) => Buffer.from(s).toString('base64url');
const json = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });
const quiet = { info() {}, error() {} };

async function setup(t, { extractResult, github, m1 } = {}) {
	const dir = mkdtempSync(path.join(tmpdir(), 'devotion-test-'));
	const audioDir = path.join(dir, 'audio');
	const config = { version: '1.0.0', port: 0, dataDir: dir, adminPassword: '', githubRepo: 'o/r', updateRequestFile: path.join(dir, 'update-request'), env: {} };
	const db = openDb(dir);
	const settings = new Settings(db, {});
	const calls = { google: [], claude: [], run: [], github: [], smb: [], web: [], speech: [] };
	const mails = {
		m1: { id: 'm1', internalDate: '1763200000000', payload: { mimeType: 'text/html', headers: [{ name: 'Subject', value: m1?.subject ?? 'Daily <b>bread</b>' }, { name: 'From', value: 'daily@example.org' }], body: { data: b64(m1?.html ?? '<p>Give us this day.</p><p>Unsubscribe</p>') } } },
		m2: { id: 'm2', internalDate: '1763300000000', payload: { mimeType: 'text/plain', headers: [{ name: 'Subject', value: 'Receipt' }], body: { data: b64('Thanks for your order') } } },
	};
	const googleFetch = async (url, init = {}) => {
		url = String(url);
		calls.google.push(url);
		if (url.startsWith('https://oauth2.googleapis.com/token')) {
			const grant = init.body.get('grant_type');
			if (grant === 'authorization_code' && init.body.get('code') !== 'good-code') return json({ error: 'invalid_grant' }, 400);
			return json({ access_token: 'at', expires_in: 3600, ...(grant === 'authorization_code' ? { refresh_token: 'rt' } : {}) });
		}
		assert.equal(init.headers.authorization, 'Bearer at');
		if (url.endsWith('/profile')) return json({ emailAddress: 'me@example.org' });
		if (url.includes('/messages?')) return json({ messages: [{ id: 'm2' }, { id: 'm1' }] });
		const id = /\/messages\/(\w+)\?/.exec(url)[1];
		return json(mails[id]);
	};
	const gmail = new Gmail({ settings, port: 8787, fetchImpl: googleFetch });
	const fakeClient = { beta: { messages: { stream: (params) => ({ finalMessage: async () => {
		calls.claude.push(params);
		const result = extractResult ? extractResult(params) : params.messages[0].content.includes('Receipt') ? { entries: [] } : { entries: [{ title: 'Daily Bread', entry_date: null, scripture: 'Matthew 6:11', summary: 'On provision.', body: 'Give us this day.', references: [{ text: 'this day', book: 'Matthew', chapter: 6, verse_start: 11, verse_end: null }, { text: 'not in the text', book: 'John', chapter: 1, verse_start: 1, verse_end: null }] }] };
		return { stop_reason: 'end_turn', content: [{ type: 'text', text: JSON.stringify(result) }] };
	} }) } } };
	const extractor = new Extractor({ settings, clientFactory: () => fakeClient });
	const runImpl = async (cmd, args, opts) => {
		calls.run.push({ cmd, args, input: opts?.input });
		// piper with --output_dir writes one wav per line (unless this "piper" is too old for it).
		if (cmd === 'piper' && args.includes('--output_dir')) {
			if (calls.oldPiper) return;
			const out = args[args.indexOf('--output_dir') + 1];
			// A quarter of a second of sound for each word, at 8000 samples a second.
			opts.input.trim().split('\n').forEach((line, i) => writeFileSync(path.join(out, `${1700000000000000000n + BigInt(i)}.wav`), wavFile({ rate: 8000, channels: 1 }, [Buffer.alloc(line.split(' ').length * 4000, 1)])));
			return;
		}
		// piper writes the wav named after --output_file; lame writes its last argument.
		writeFileSync(cmd === 'lame' ? args.at(-1) : args[args.indexOf('--output_file') + 1], Buffer.alloc(2048, cmd === 'lame' ? 7 : 1));
	};
	// A stand-in for the two download sites: one small voice and one small Bible.
	const voiceModel = Buffer.from('not really a neural network');
	const voiceJson = Buffer.from('{"audio":{"sample_rate":22050}}');
	const bibleSource = Buffer.from(JSON.stringify({ translation: 'T', books: [{ name: 'I Book', chapters: [{ chapter: 2, verses: [{ verse: 1, text: 'c2v1' }] }, { chapter: 1, verses: [{ verse: 1, text: ' In the  beginning ' }, { verse: 3, text: 'third' }] }] }] }));
	const sha = (b) => createHash('sha256').update(b).digest('hex');
	const voiceCatalog = [
		{ id: 'builtin', name: 'Built in', description: 'ships with the server', dir: 'x', sha256: 'n/a', jsonSha256: 'n/a' },
		{ id: 'amy', name: 'Amy', description: 'female', dir: 'en/amy', sha256: sha(voiceModel), jsonSha256: sha(voiceJson) },
		{ id: 'bad', name: 'Bad', description: 'wrong checksum', dir: 'en/bad', sha256: '0'.repeat(64), jsonSha256: sha(voiceJson) },
	];
	const bibleCatalog = [{ id: 'tst', name: 'Test Version', language: 'xx', file: 'TST', sha256: sha(bibleSource) }, { id: 'bad', name: 'Bad', language: 'en', file: 'BAD', sha256: '0'.repeat(64) }];
	const webFetch = async (url) => {
		calls.web.push(String(url));
		if (String(url).endsWith('.onnx.json')) return new Response(voiceJson);
		if (String(url).endsWith('.onnx')) return new Response(voiceModel);
		return new Response(bibleSource);
	};
	const voices = new Voices({ settings, dataDir: dir, fetchImpl: webFetch, catalog: voiceCatalog });
	const bibles = new Bibles({ db, dataDir: dir, fetchImpl: webFetch, catalog: bibleCatalog });
	const speechFetch = async (url, init) => {
		calls.speech.push({ url: String(url), auth: init.headers.authorization, body: JSON.parse(init.body) });
		if (calls.failSpeech) return new Response(calls.failSpeech, { status: 500 });
		// One second of sound per request, when this stand-in is told to honour the WAV format.
		if (calls.speechWav && JSON.parse(init.body).response_format === 'wav') return new Response(wavFile({ rate: 8000, channels: 1 }, [Buffer.alloc(16000, 2)]), { status: 200 });
		return new Response(Buffer.alloc(4096, 9), { status: 200 });
	};
	const tts = new Tts({ settings, audioDir, voices, runImpl, fetchImpl: speechFetch, planImpl: (provider) => plan({ cores: 1, memoryMb: 1024, usableMb: 819 }, provider) });
	// A stand-in for smbclient: remembers what was "put" and lists it back.
	const share = { files: new Map(), fail: null };
	const smbRun = async (cmd, args) => {
		calls.smb.push(args);
		assert.equal(cmd, 'smbclient');
		assert.ok(!args.join(' ').includes(settings.get('smb_password') || '\u0000'), 'the password must not be on the command line');
		if (share.fail) return { code: 1, out: share.fail };
		let out = '';
		for (const command of args[args.indexOf('-c') + 1].split('; ')) {
			const put = /^put "(.+)" "(.+)"$/.exec(command);
			const ls = /^ls "(.+)"$/.exec(command);
			if (put) share.files.set(put[2], readFileSync(put[1]));
			else if (ls && ls[1].endsWith('*')) {
				for (const [name, data] of share.files) if (name.startsWith(ls[1].slice(0, -1))) out += `  ${name.split('\\').pop()}      A   ${share.truncate ? 1 : data.length}  Sun Nov 15 12:00:00 2026\n`;
			} else if (ls) out += `  ${ls[1].split('\\').pop()}                D        0  Sun Nov 15 12:00:00 2026\n\n\t\t151361008 blocks of size 1024. 94880016 blocks available\n`; // as the real program lists a folder
		}
		return { code: 0, out };
	};
	const archiver = new Archiver({ db, settings, audioDir, runImpl: smbRun, log: quiet });
	// A stand-in for Wallhaven: its search, single-wallpaper and image hosts.
	const jpeg = (n) => new Response(Buffer.alloc(n, 9), { headers: { 'content-type': 'image/jpeg' } });
	const whItem = (id, extra = {}) => ({ id, url: `https://wallhaven.cc/w/${id}`, purity: 'sfw', category: 'general', dimension_x: 1080, dimension_y: 1920, resolution: '1080x1920', colors: ['#112233'], file_size: 5000, path: `https://w.wallhaven.cc/full/${id.slice(0, 2)}/wallhaven-${id}.jpg`, thumbs: { large: `https://th.wallhaven.cc/lg/${id.slice(0, 2)}/${id}.jpg`, small: `https://th.wallhaven.cc/small/${id.slice(0, 2)}/${id}.jpg` }, ...extra });
	const wallhaven = { items: { aaa111: whItem('aaa111'), bbb222: whItem('bbb222'), evil00: whItem('evil00', { path: 'https://evil.example/x.jpg' }), nsfw01: whItem('nsfw01', { purity: 'nsfw' }) } };
	const whFetch = async (url) => {
		const u = new URL(String(url));
		calls.web.push(u.href);
		if (wallhaven.busy) return new Response('{}', { status: 429 });
		if (u.hostname === 'wallhaven.cc' && u.pathname === '/api/v1/search') {
			return json({ data: [whItem('aaa111'), whItem('nsfw01', { purity: 'nsfw' }), { id: 'nothumb', purity: 'sfw' }, whItem('bbb222')], meta: { current_page: 1, last_page: 3, per_page: 24, total: 70, seed: null } });
		}
		const one = /^\/api\/v1\/w\/(\w+)$/.exec(u.pathname);
		if (u.hostname === 'wallhaven.cc' && one) return wallhaven.items[one[1]] ? json({ data: wallhaven.items[one[1]] }) : json({ error: 'Nothing here' }, 404);
		if (u.hostname === 'w.wallhaven.cc') return jpeg(5000);
		if (u.hostname === 'th.wallhaven.cc') return jpeg(300);
		throw new Error('the server must not fetch ' + u.href);
	};
	const wallpapers = new Wallpapers({ db, dataDir: dir, fetchImpl: whFetch, log: quiet });
	const pipeline = new Pipeline({ db, settings, gmail, extractor, tts, audioDir, archiver, log: quiet });
	const updater = new Updater({ config, settings, fetchImpl: async (url, init) => { calls.github.push({ url: String(url), headers: init.headers }); return github(); } });
	const time = { ahead: 0 }; // tests move the server's clock forward with this
	const clock = () => Date.now() + time.ahead;
	const bibleAudio = new BibleAudio({ bibles, tts, settings, dataDir: dir, log: quiet, appVersion: 'tst', clock });
	const server = createServer({ config, db, settings, gmail, pipeline, tts, updater, voices, bibles, archiver, wallpapers, bibleAudio, audioDir, log: quiet, loginDelayMs: 0, clock });
	await new Promise((r) => server.listen(0, '127.0.0.1', r));
	const base = `http://127.0.0.1:${server.address().port}`;
	t.after(() => { server.close(); server.closeAllConnections(); db.close(); rmSync(dir, { recursive: true, force: true }); });

	let cookie = '';
	const call = async (p, { body, method, headers: extra } = {}) => {
		const headers = { ...extra };
		if (cookie) headers.cookie = cookie;
		if (body !== undefined) headers['content-type'] = 'application/json';
		const res = await fetch(base + p, { method: method || (body !== undefined ? 'POST' : 'GET'), headers, body: body !== undefined ? JSON.stringify(body) : undefined, redirect: 'manual' });
		const set = res.headers.get('set-cookie');
		if (set) cookie = set.split(';')[0];
		return res;
	};
	// Connecting starts a mail run in the background; this returns once it has finished.
	const connectGmail = async () => {
		const { url } = await (await call('/admin/api/oauth/start', { body: {} })).json();
		await call(`/oauth/callback?state=${new URL(url).searchParams.get('state')}&code=good-code`);
		while (pipeline.running) await new Promise((r) => setTimeout(r, 5));
	};
	return { call, calls, settings, db, pipeline, gmail, config, dir, audioDir, share, archiver, base, connectGmail, wallhaven, time, bibleAudio, dropCookie: () => (cookie = '') };
}

test('first visit sets the password; afterwards only that password logs in', async (t) => {
	const { call, dropCookie } = await setup(t);
	assert.deepEqual(await (await call('/admin/api/session')).json(), { version: '1.0.0', needs_setup: true, logged_in: false });
	assert.equal((await call('/admin/api/state')).status, 401);
	assert.equal((await call('/admin/api/setup', { body: { password: 'short' } })).status, 400);
	assert.equal((await call('/admin/api/setup', { body: { password: 'correct horse' } })).status, 200);
	assert.equal((await call('/admin/api/state')).status, 200);
	// A second setup is refused, even without a session.
	dropCookie();
	assert.equal((await call('/admin/api/setup', { body: { password: 'another one!' } })).status, 409);
	assert.equal((await call('/admin/api/login', { body: { password: 'wrong' } })).status, 401);
	assert.equal((await call('/admin/api/login', { body: { password: 'correct horse' } })).status, 200);
	assert.equal((await call('/admin/api/password', { body: { current: 'nope', password: 'new password' } })).status, 403);
	assert.equal((await call('/admin/api/password', { body: { current: 'correct horse', password: 'new password' } })).status, 200);
	dropCookie();
	assert.equal((await call('/admin/api/login', { body: { password: 'new password' } })).status, 200);
	// Form posts (no JSON content type) are refused: a cross-site form can't drive the admin API.
	const res = await call('/admin/api/run', { method: 'POST' });
	assert.equal(res.status, 415);
	assert.equal((await call('/admin')).status, 200);
	// The start page offers both ways in; the reader is a page of its own.
	const start = await call('/');
	assert.equal(start.status, 200);
	const startHtml = await start.text();
	assert.match(startHtml, /href="\/read"[^>]*>Continue to Devotion/);
	assert.match(startHtml, /href="\/admin"[^>]*>Sign in to the admin panel/);
	assert.match(start.headers.get('content-security-policy'), /default-src 'self'/);
	const read = await call('/read');
	const readHtml = await read.text();
	assert.match(readHtml, /<title>Devotion<\/title>/);
	// Both carry the script that chooses a visit's wallpaper, put in by the server.
	for (const html of [startHtml, readHtml]) {
		assert.match(html, /const WallPick = /);
		assert.ok(!html.includes('<!-- wallpick.js -->'));
	}
	// No page may be framed, load anything from elsewhere, or have its links and forms pointed elsewhere.
	for (const res of [start, read, await call('/admin')]) {
		const csp = res.headers.get('content-security-policy');
		for (const rule of ["default-src 'self'", "object-src 'none'", "base-uri 'none'", "form-action 'self'", "frame-ancestors 'none'"]) assert.ok(csp.includes(rule), rule);
		assert.equal(res.headers.get('x-frame-options'), 'DENY');
		assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
		assert.equal(res.headers.get('referrer-policy'), 'no-referrer');
	}
});

test('admin: wrong passwords from one address are counted, and after five it has to wait', async (t) => {
	const { call, time, dropCookie } = await setup(t);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	dropCookie();
	const login = (password) => call('/admin/api/login', { body: { password } });
	// Four wrong, then the right one: let in, and the count starts again.
	for (let i = 0; i < 4; i++) assert.equal((await login('guess ' + i)).status, 401);
	assert.equal((await login('correct horse')).status, 200);
	dropCookie();
	// Five wrong, all sent at once: counted all the same.
	assert.deepEqual((await Promise.all([0, 1, 2, 3, 4].map((i) => login('guess ' + i)))).map((r) => r.status), [401, 401, 401, 401, 401]);
	// Now not even the right password is looked at...
	const locked = await login('correct horse');
	assert.equal(locked.status, 429);
	assert.match((await locked.json()).error, /Too many wrong passwords.*30 seconds/);
	assert.equal(locked.headers.get('retry-after'), '30');
	assert.equal((await call('/admin/api/state')).status, 401);
	// ...until the wait is over. Another wrong one then waits twice as long.
	time.ahead += 31_000;
	assert.equal((await login('guess again')).status, 401);
	assert.match((await (await login('correct horse')).json()).error, /60 seconds/);
	time.ahead += 61_000;
	assert.equal((await login('correct horse')).status, 200);
	// The same count guards "current password" when changing it.
	for (let i = 0; i < 5; i++) assert.equal((await call('/admin/api/password', { body: { current: 'nope ' + i, password: 'new password' } })).status, 403);
	assert.equal((await call('/admin/api/password', { body: { current: 'correct horse', password: 'new password' } })).status, 429);
	// Behind an https address the session cookie is marked Secure; on plain http in the house it is not.
	time.ahead += 31_000;
	dropCookie();
	const viaProxy = await fetch((await call('/admin/api/session')).url.replace('/session', '/login'), { method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-proto': 'https' }, body: JSON.stringify({ password: 'correct horse' }) });
	assert.match(viaProxy.headers.get('set-cookie'), /^sid=[\w-]+; HttpOnly; SameSite=Strict; Path=\/; Secure; Max-Age=43200$/);
	assert.match((await login('correct horse')).headers.get('set-cookie'), /^sid=[\w-]+; HttpOnly; SameSite=Strict; Path=\/; Max-Age=43200$/);
});

test('settings: secrets are write-only and an empty secret keeps the saved one', async (t) => {
	const { call, settings } = await setup(t);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	let state = await (await call('/admin/api/settings', { body: { anthropic_api_key: 'sk-secret', gmail_query: ' from:x ', check_times: 'abc', tts_provider: 'bogus', piper_voice: 'hijack', google_refresh_token: 'hijack', smb_share: '-U root //nas/share', archive_days: '0', tts_rate: 'fast' } })).json();
	assert.equal(state.settings.anthropic_api_key_set, true);
	assert.equal(state.settings.gmail_query, 'from:x');
	assert.equal(state.settings.check_times, '06:00');
	assert.equal(state.settings.tts_provider, 'piper');
	assert.ok(!JSON.stringify(state).includes('sk-secret'));
	assert.equal(settings.get('piper_voice'), '');
	assert.equal(state.settings.smb_share, '');
	assert.equal(state.settings.archive_days, '30');
	assert.equal(state.settings.tts_rate, 'fast');
	state = await (await call('/admin/api/settings', { body: { smb_share: '\\\\nas.local\\Family Backups', smb_password: 'pw' } })).json();
	assert.equal(state.settings.smb_share, '//nas.local/Family Backups');
	assert.equal(state.settings.smb_password_set, true);
	assert.equal(settings.get('google_refresh_token'), '');
	state = await (await call('/admin/api/settings', { body: { anthropic_api_key: '' } })).json();
	assert.equal(settings.get('anthropic_api_key'), 'sk-secret');
	// Which program the server runs, and with which file, is never taken from the page.
	await call('/admin/api/settings', { body: { piper_bin: '/bin/sh', piper_model: '/tmp/x.onnx' } });
	assert.deepEqual([settings.get('piper_bin'), settings.get('piper_model')], ['piper', '']);
	// A speech API's address is an http(s) address or it is not saved; the daily limit is a number.
	state = await (await call('/admin/api/settings', { body: { tts_openai_base_url: 'file:///etc/passwd', bible_premium_daily: 'lots' } })).json();
	assert.deepEqual([state.settings.tts_openai_base_url, state.settings.bible_premium_daily], ['https://api.openai.com/v1', '100']);
	state = await (await call('/admin/api/settings', { body: { tts_openai_base_url: 'https://speech.example/v1', bible_premium_daily: '25' } })).json();
	assert.deepEqual([state.settings.tts_openai_base_url, state.settings.bible_premium_daily], ['https://speech.example/v1', '25']);
});

test('gmail oauth, mail to entries to audio, and the app API', async (t) => {
	const { call, calls, settings, pipeline, audioDir, dropCookie } = await setup(t);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	assert.equal((await call('/admin/api/oauth/start', { body: {} })).status, 400); // no client id yet
	await call('/admin/api/settings', { body: { google_client_id: 'cid', google_client_secret: 'cs', gmail_keywords: 'Daily\nreceipt', gmail_query: 'from:daily@example.org', anthropic_api_key: 'sk' } });
	settings.set('piper_model', '/voice.onnx'); // as an installed server has it: from its environment, not from the page

	// OAuth: the consent URL, a forged state, a bad code, then the real callback.
	const { url } = await (await call('/admin/api/oauth/start', { body: {} })).json();
	const auth = new URL(url);
	assert.equal(auth.searchParams.get('redirect_uri'), 'http://localhost:8787/oauth/callback');
	assert.equal(auth.searchParams.get('scope'), 'https://www.googleapis.com/auth/gmail.readonly');
	assert.equal(auth.searchParams.get('access_type'), 'offline');
	const state = auth.searchParams.get('state');
	assert.equal((await call('/oauth/callback?state=forged&code=good-code')).status, 400);
	assert.equal(settings.get('google_refresh_token'), '');
	// The paste-back route, as used when Google redirected the browser to localhost.
	const done = await call('/admin/api/oauth/complete', { body: { url: `http://localhost:8787/oauth/callback?state=${state}&code=good-code` } });
	assert.deepEqual(await done.json(), { ok: true, email: 'me@example.org' });
	assert.equal(settings.get('google_refresh_token'), 'rt');
	// A state value works once.
	assert.equal((await call(`/oauth/callback?state=${state}&code=good-code`)).status, 400);

	const result = await pipeline.runOnce();
	assert.deepEqual(result, { found: 2, processed: 2, entries: 1, errors: 0, audio: 1 });
	assert.equal(new URL(calls.google.find((u) => u.includes('/messages?'))).searchParams.get('q'), 'from:daily@example.org {"Daily" "receipt"}');
	// The model saw text, not markup, and the request is shaped as intended.
	const sent = calls.claude[0];
	assert.equal(sent.model, 'claude-opus-5-5');
	assert.match(sent.messages[0].content, /Give us this day\.\n\nUnsubscribe/);
	assert.equal(sent.output_config.format.type, 'json_schema');
	assert.equal(sent.fallbacks, 'default');
	assert.deepEqual(calls.run.map((c) => c.cmd), ['piper', 'lame']);
	assert.equal(calls.run[0].input, 'Saturday, November 15.\nDaily Bread.\nMatthew 6:11.\nGive us this day.\n');
	assert.deepEqual(calls.run[0].args.slice(0, 4), ['--model', '/voice.onnx', '--length_scale', '1.0']);
	// A second run does nothing new.
	assert.equal((await pipeline.runOnce()).processed, 0);
	assert.equal(calls.claude.length, 2);

	// The admin page's data never carries markup as markup (it is rendered with textContent).
	const admin = await (await call('/admin/api/state')).json();
	assert.equal(admin.messages.find((m) => m.gmail_id === 'm1').subject, 'Daily <b>bread</b>');
	assert.equal(admin.messages.find((m) => m.gmail_id === 'm2').status, 'skipped');
	assert.equal(admin.app_token, undefined);

	// The app's API needs no token and no admin session.
	dropCookie();
	assert.deepEqual(await (await call('/api/health')).json(), { ok: true, version: '1.0.0' });
	const list = await (await call('/api/entries')).json();
	assert.equal(list.entries.length, 1);
	const entry = list.entries[0];
	assert.deepEqual({ ...entry, id: 0, created_at: '', updated_at: '', audio_sha256: '', audio: entry.audio.map((a) => ({ ...a, sha256: '' })) }, {
		id: 0, kind: 'daily_reading', lesson_number: null, title: 'Daily Bread', entry_date: '2025-11-15', scripture: 'Matthew 6:11', summary: 'On provision.', body: 'Give us this day.',
		references: [{ start: 8, end: 16, book: 'Matthew', book_index: 39, chapter: 6, verse_start: 11, verse_end: null }],
		preferred_voice: null, speech_offset: 'Saturday, November 15.\n\nDaily Bread.\n\nMatthew 6:11.\n\n'.length,
		has_audio: true, audio_bytes: 2048, audio_sha256: '', audio: [{ voice: 'voice', bytes: 2048, sha256: '' }], source_from: 'daily@example.org', source_subject: 'Daily <b>bread</b>', created_at: '', updated_at: '', archived: false, deleted: false,
	});
	const audio = await call(`/api/entries/${entry.id}/audio`);
	assert.equal(audio.headers.get('content-type'), 'audio/mpeg');
	const bytes = Buffer.from(await audio.arrayBuffer());
	assert.equal(createHash('sha256').update(bytes).digest('hex'), entry.audio_sha256);
	assert.equal(entry.audio[0].sha256, entry.audio_sha256);
	assert.equal((await call('/api/entries/999/audio')).status, 404);
	assert.equal((await call(`/api/entries/${entry.id}/audio?voice=voice`)).status, 200);
	assert.equal((await call(`/api/entries/${entry.id}/audio?voice=nobody`)).status, 404);
	assert.deepEqual(await (await call('/api/voices')).json(), { voices: [{ id: 'voice', name: 'voice', description: '' }], default: 'voice' });

	// Incremental sync: nothing new, then a deletion shows up as a tombstone.
	assert.equal((await (await call(`/api/entries?since=${encodeURIComponent(list.server_time)}`)).json()).entries.length, 0);
	assert.equal((await call('/api/entries?since=yesterday')).status, 400);
	await new Promise((r) => setTimeout(r, 5));
	await pipeline.deleteEntry(entry.id);
	const after = await (await call(`/api/entries?since=${encodeURIComponent(list.server_time)}`)).json();
	assert.deepEqual(after.entries.map((e) => [e.id, e.deleted, e.has_audio, e.body]), [[entry.id, true, false, '']]);
	assert.equal((await (await call('/api/entries')).json()).entries.length, 0);
	assert.equal((await call(`/api/entries/${entry.id}/audio`)).status, 404);
});

test('premium speech for lessons, daily readings, or both', async (t) => {
	const lesson = { title: 'Victory After Struggling', entry_date: '2026-11-08', scripture: null, summary: 'S.', body: 'Victory! What a sweet refrain.', references: [] };
	const reading = { title: 'Daily Bread', entry_date: '2026-11-15', scripture: null, summary: 'S.', body: 'Give us this day.', references: [] };
	const { call, calls, connectGmail, settings } = await setup(t, {
		m1: { subject: 'Lesson 11 – Victory After Struggling', html: '<p>Lesson 11</p><p>November 15, 2026</p><p>Give us this day.</p>' },
		extractResult: (p) => ({ entries: p.messages[0].content.includes('Receipt') ? [reading] : [lesson] }),
	});
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	await call('/admin/api/settings', { body: { google_client_id: 'cid', google_client_secret: 'cs', gmail_keywords: 'day, receipt, order', anthropic_api_key: 'sk' } });
	settings.set('piper_model', '/voice.onnx');
	await connectGmail();
	const entries = async () => (await (await call('/api/entries')).json()).entries;
	const wait = async (check) => { for (let i = 0; i < 100 && !check(await entries()); i++) await new Promise((r) => setTimeout(r, 10)); };
	// Without it, both are read by the server's voice.
	let list = await entries();
	assert.deepEqual(list.map((e) => [e.kind, e.audio.map((a) => a.voice), e.preferred_voice]).sort(), [['daily_reading', ['voice'], null], ['lesson', ['voice'], null]]);
	assert.equal(calls.speech.length, 0);
	// Turned on without a key: nothing changes.
	await call('/admin/api/settings', { body: { premium_lessons: 'on' } });
	assert.equal((await (await call('/api/voices')).json()).voices.length, 1);
	// With a key, the lesson (only) is also made by the speech API, and that is what listeners get.
	await call('/admin/api/settings', { body: { tts_openai_key: 'sk-speech', tts_openai_voice: 'Nova' } });
	await wait((l) => l.find((e) => e.kind === 'lesson').audio.length === 2);
	list = await entries();
	assert.deepEqual(list.map((e) => [e.kind, e.audio.map((a) => a.voice), e.preferred_voice]).sort(), [['daily_reading', ['voice'], null], ['lesson', ['api-nova', 'voice'], 'api-nova']]);
	assert.equal(calls.speech.length, 1);
	assert.deepEqual([calls.speech[0].auth, calls.speech[0].body.voice], ['Bearer sk-speech', 'Nova']);
	assert.match(calls.speech[0].body.input, /^Lesson 11\./);
	const les = list.find((e) => e.kind === 'lesson');
	assert.equal(les.audio_bytes, 4096);
	assert.equal(Number((await call(`/api/entries/${les.id}/audio`)).headers.get('content-length')), 4096);
	assert.equal(Number((await call(`/api/entries/${les.id}/audio?voice=voice`)).headers.get('content-length')), 2048);
	assert.deepEqual((await (await call('/api/voices')).json()).voices.map((v) => [v.id, v.lessons_only ?? false]), [['voice', false], ['api-nova', true]]);
	// "Redo audio" on the lesson uses the premium voice only; piper is not run again for it.
	const piperBefore = calls.run.filter((c) => c.cmd === 'piper').length;
	await call(`/admin/api/entries/${les.id}/audio`, { body: {} });
	await wait(() => calls.speech.length === 2);
	assert.equal(calls.run.filter((c) => c.cmd === 'piper').length, piperBefore);
	// Off again: the server's voice is the default once more.
	await call('/admin/api/settings', { body: { premium_lessons: 'off' } });
	assert.equal((await entries()).find((e) => e.kind === 'lesson').preferred_voice, null);
	assert.equal(settings.get('premium_lessons'), 'off');
	assert.equal((await (await call('/api/voices')).json()).voices.length, 1);
	// Daily readings only: the reading gets the premium voice, the lesson is back on the server's.
	const speechBefore = calls.speech.length;
	await call('/admin/api/settings', { body: { premium_readings: 'on' } });
	await wait((l) => l.find((e) => e.kind === 'daily_reading').audio.length === 2);
	list = await entries();
	assert.deepEqual(list.map((e) => [e.kind, e.preferred_voice]).sort(), [['daily_reading', 'api-nova'], ['lesson', null]]);
	assert.equal(calls.speech.length, speechBefore + 1);
	assert.match(calls.speech.at(-1).body.input, /Daily Bread/);
	// Both: nothing new to make (the lesson's premium audio is still there), and both prefer it.
	await call('/admin/api/settings', { body: { premium_lessons: 'on', premium_readings: 'yes please' } });
	list = await entries();
	assert.deepEqual(list.map((e) => [e.kind, e.preferred_voice]).sort(), [['daily_reading', 'api-nova'], ['lesson', 'api-nova']]);
	await new Promise((r) => setTimeout(r, 50));
	assert.equal(calls.speech.length, speechBefore + 1);

	// The choice per kind: lessons by a speech server on the owner's network, daily readings back on Piper.
	let state = await (await call('/admin/api/state')).json();
	assert.deepEqual(state.engines, { readings_engine: 'openai', lessons_engine: 'openai', bible_engine: 'piper' }); // the Bible stays with Piper
	await call('/admin/api/settings', { body: { lessons_engine: 'local', readings_engine: 'piper', tts_local_base_url: 'http://tts.lan:8880/v1', tts_local_model: 'kokoro', tts_local_voice: 'af_heart', bible_engine: 'bogus' } });
	await wait((l) => l.find((e) => e.kind === 'lesson').audio.some((a) => a.voice === 'local-af_heart'));
	list = await entries();
	assert.deepEqual(list.map((e) => [e.kind, e.preferred_voice]).sort(), [['daily_reading', null], ['lesson', 'local-af_heart']]);
	assert.deepEqual([calls.speech.at(-1).url, calls.speech.at(-1).auth, calls.speech.at(-1).body.model], ['http://tts.lan:8880/v1/audio/speech', undefined, 'kokoro']);
	assert.deepEqual((await (await call('/api/voices')).json()), { voices: [{ id: 'voice', name: 'voice', description: '' }, { id: 'local-af_heart', name: 'Local (af_heart)', description: 'Speech server on this network', lessons_only: true }], default: 'voice' });
	state = await (await call('/admin/api/state')).json();
	assert.deepEqual(state.engines, { readings_engine: 'piper', lessons_engine: 'local', bible_engine: 'piper' });
	// No audio for lessons at all.
	await call('/admin/api/settings', { body: { lessons_engine: 'none' } });
	assert.equal((await (await call('/api/voices')).json()).voices.length, 1);
});

test('a lesson is sorted from its subject, not by the model, and can be corrected by hand', async (t) => {
	// The model says nothing about lessons; it only cleans the text and summarizes.
	const lesson = { title: 'Victory After Struggling', entry_date: '2026-11-08', scripture: 'Genesis 32:24-31; 1 Samuel 17:45-49', summary: 'S.', body: 'Victory! What a sweet refrain.', references: [] };
	const { call, calls, connectGmail, pipeline, settings } = await setup(t, {
		m1: { subject: 'Lesson 11 – Victory After Struggling', html: '<p>COGICM Daily Bible Readings</p><p>Victory After Struggling</p><p>Lesson 11</p><p>November 15, 2026</p><p>Give us this day.</p>' },
		extractResult: (p) => (p.messages[0].content.includes('Receipt') ? { entries: [] } : { entries: [lesson] }),
	});
	assert.ok(!JSON.stringify(calls.claude).includes('lesson_number'));
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	await call('/admin/api/settings', { body: { google_client_id: 'cid', google_client_secret: 'cs', gmail_keywords: 'day, receipt', anthropic_api_key: 'sk' } });
	settings.set('piper_model', '/voice.onnx');
	await connectGmail();
	assert.ok(!('kind' in calls.claude[0].output_config.format.schema.properties.entries.items.properties));
	let [entry] = (await (await call('/api/entries')).json()).entries;
	assert.deepEqual([entry.kind, entry.lesson_number, entry.title, entry.entry_date], ['lesson', 11, 'Victory After Struggling', '2026-11-15']);
	assert.match(calls.run.find((c) => c.cmd === 'piper').input, /^Lesson 11\.\nVictory After Struggling\.\n/);

	// A correction by hand, which the rules then leave alone.
	for (const bad of [{ kind: 'sermon', title: 'x' }, { kind: 'lesson', lesson_number: 'eleven', title: 'x' }, { kind: 'lesson', entry_date: '2026-02-30x', title: 'x' }, { kind: 'lesson', title: ' ' }]) {
		assert.equal((await call(`/admin/api/entries/${entry.id}/label`, { body: bad })).status, 400);
	}
	const before = (await (await call('/api/entries')).json()).server_time;
	await new Promise((r) => setTimeout(r, 5));
	let state = await (await call(`/admin/api/entries/${entry.id}/label`, { body: { kind: 'lesson', lesson_number: '12', entry_date: '2026-11-22', title: ' Victory  After Struggling ' } })).json();
	assert.deepEqual(state.entries.map((e) => [e.kind, e.lesson_number, e.entry_date, e.title, e.label_manual]), [['lesson', 12, '2026-11-22', 'Victory After Struggling', 1]]);
	pipeline.reclassify();
	[entry] = (await (await call(`/api/entries?since=${encodeURIComponent(before)}`)).json()).entries;
	assert.deepEqual([entry.lesson_number, entry.entry_date], [12, '2026-11-22']); // and the app sees the change
	// Handing it back to the rules restores what the email says.
	state = await (await call(`/admin/api/entries/${entry.id}/label`, { body: { auto: true } })).json();
	await call(`/admin/api/entries/${entry.id}/label`, { body: { kind: 'daily_reading', lesson_number: null, entry_date: '2026-11-22', title: 'Wrong' } });
	state = await (await call(`/admin/api/entries/${entry.id}/label`, { body: { auto: true } })).json();
	assert.deepEqual(state.entries.map((e) => [e.kind, e.lesson_number, e.entry_date, e.title, e.label_manual]), [['lesson', 11, '2026-11-15', 'Victory After Struggling', 0]]);
	assert.equal((await call('/admin/api/entries/999/label', { body: { auto: true } })).status, 404);
});

test('a daily reading is sorted from its subject, with its day, its week\'s lesson and its passage', async (t) => {
	const reading = { title: 'The struggle is spiritual', entry_date: '2026-11-14', scripture: null, summary: 'S.', body: 'For the weapons of our warfare are not carnal.', references: [] };
	const { call, calls, connectGmail, settings } = await setup(t, {
		m1: { subject: 'November 15, 2026 – The struggle is spiritual', html: '<p>COGICM Daily Bible Readings</p><p><b>Lesson 11 – Victory After Struggling</b><br><b>Today’s Quote:</b> “A quote.”<br><b>November 15, 2026 – The struggle is spiritual</b><br><b>2 Cor. 10:4-6</b></p><p>KJV<br>4 Give us this day.</p>' },
		extractResult: (p) => (p.messages[0].content.includes('Receipt') ? { entries: [] } : { entries: [reading] }),
	});
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	await call('/admin/api/settings', { body: { google_client_id: 'cid', google_client_secret: 'cs', gmail_keywords: 'day, receipt', anthropic_api_key: 'sk' } });
	settings.set('piper_model', '/voice.onnx');
	await connectGmail();
	const [entry] = (await (await call('/api/entries')).json()).entries;
	assert.deepEqual([entry.kind, entry.lesson_number, entry.title, entry.entry_date, entry.scripture], ['daily_reading', 11, 'The struggle is spiritual', '2026-11-15', '2 Cor. 10:4-6']);
	assert.match(calls.run.find((c) => c.cmd === 'piper').input, /^Sunday, November 15\.\nThe struggle is spiritual\.\n2 Cor\. 10:4-6\.\n/);
});

test('entries made before sorting by subject are sorted at startup', async (t) => {
	const { db, pipeline } = await setup(t);
	const insert = db.prepare("INSERT INTO entries (kind, title, entry_date, body, source_subject, created_at, updated_at) VALUES (?, ?, ?, ?, ?, '2026-11-01T00:00:00.000Z', '2026-11-01T00:00:00.000Z')");
	insert.run('daily_reading', 'Victory After Struggling', '2026-11-08', 'Victory After Struggling\n\nLesson 11\n\nNovember 15, 2026\n\nIntroduction', 'Lesson 11 – Victory After Struggling');
	insert.run('daily_reading', 'Isaac’s Nonresistance', '2026-11-01', 'No date in this body.', 'Lesson 10 – Isaac’s Nonresistance');
	insert.run('lesson', 'The struggle is spiritual', '2026-11-14', 'Body', 'November 15, 2026 – The struggle is spiritual');
	insert.run('daily_reading', 'Other', '2026-11-02', 'Body', 'A newsletter');
	assert.equal(pipeline.reclassify(), 3);
	assert.deepEqual(db.prepare('SELECT kind, lesson_number, entry_date, updated_at > created_at AS touched FROM entries ORDER BY id').all().map((r) => ({ ...r })), [
		{ kind: 'lesson', lesson_number: 11, entry_date: '2026-11-15', touched: 1 },
		{ kind: 'lesson', lesson_number: 10, entry_date: '2026-11-01', touched: 1 },
		{ kind: 'daily_reading', lesson_number: null, entry_date: '2026-11-15', touched: 1 },
		{ kind: 'daily_reading', lesson_number: null, entry_date: '2026-11-02', touched: 0 },
	]);
	assert.equal(pipeline.reclassify(), 0); // nothing more to do the second time
});

test('wallpapers: found on Wallhaven by the admin, kept on the server, streamed to the app', async (t) => {
	const { call, calls, base, wallhaven, dropCookie } = await setup(t);
	assert.deepEqual(await (await call('/api/wallpapers')).json(), { topics: [], wallpapers: [] });
	assert.equal((await call('/admin/api/wallpapers/search', { body: {} })).status, 401);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	const catalog = await (await call('/admin/api/wallpapers')).json();
	assert.equal(catalog.catalog[0].id, 'nature');

	// Searching: always SFW, the selectors become Wallhaven's parameters, and non-SFW results are dropped.
	const found = await (await call('/admin/api/wallpapers/search', { body: { q: 'mountains', kinds: { general: true, anime: true }, order: 'top', portrait: true } })).json();
	const searched = new URL(calls.web.at(-1));
	assert.deepEqual(Object.fromEntries(searched.searchParams), { q: 'mountains', categories: '110', purity: '100', sorting: 'toplist', topRange: '1M', ratios: 'portrait', atleast: '1080x1920', page: '1' });
	assert.deepEqual(found.results.map((r) => [r.id, r.on_server]), [['aaa111', false], ['bbb222', false]]);
	assert.deepEqual([found.page, found.last_page], [1, 3]);
	await call('/admin/api/wallpapers/search', { body: { kinds: {}, order: 'random', seed: 'Ab12', portrait: false, page: 2 } });
	assert.deepEqual(Object.fromEntries(new URL(calls.web.at(-1)).searchParams), { q: '', categories: '100', purity: '100', sorting: 'random', seed: 'Ab12', page: '2' });

	// Adding: the server looks the id up itself and only downloads from Wallhaven's image hosts.
	let added = await call('/admin/api/wallpapers/add', { body: { id: 'aaa111', topic: { id: 'mountains' } } });
	assert.deepEqual((({ id, topic, width, height, bytes, source_url }) => ({ id, topic, width, height, bytes, source_url }))(await added.json()), { id: 'aaa111', topic: 'mountains', width: 1080, height: 1920, bytes: 5000, source_url: 'https://wallhaven.cc/w/aaa111' });
	assert.equal((await call('/admin/api/wallpapers/add', { body: { id: 'evil00', topic: 'nature' } })).status, 502);
	assert.ok(!calls.web.some((u) => u.includes('evil.example')));
	assert.equal((await call('/admin/api/wallpapers/add', { body: { id: 'nsfw01', topic: 'nature' } })).status, 400);
	assert.equal((await call('/admin/api/wallpapers/add', { body: { id: '../etc', topic: 'nature' } })).status, 400);
	assert.equal((await call('/admin/api/wallpapers/add', { body: { id: 'gone99', topic: 'nature' } })).status, 404);
	assert.equal((await call('/admin/api/wallpapers/add', { body: { id: 'bbb222', topic: { name: '  Lavender   fields ' } } })).status, 200);
	wallhaven.busy = true;
	const busy = await call('/admin/api/wallpapers/search', { body: {} });
	assert.equal(busy.status, 429);
	assert.match((await busy.json()).error, /too many requests/);
	wallhaven.busy = false;

	// The app: topics with counts in selector order, the list, thumbnails and full images. No admin login needed.
	dropCookie();
	const list = await (await call('/api/wallpapers')).json();
	assert.deepEqual(list.topics, [{ id: 'mountains', name: 'Mountains', count: 1 }, { id: 'lavender-fields', name: 'Lavender fields', count: 1 }]);
	assert.deepEqual(list.wallpapers.map((w) => [w.id, w.topic]).sort(), [['aaa111', 'mountains'], ['bbb222', 'lavender-fields']]);
	assert.equal(list.wallpapers[0].topic_name, undefined);
	const full = await fetch(`${base}/api/wallpapers/aaa111`);
	assert.equal(full.headers.get('content-type'), 'image/jpeg');
	const bytes = Buffer.from(await full.arrayBuffer());
	assert.equal(bytes.length, 5000);
	assert.equal(createHash('sha256').update(bytes).digest('hex'), list.wallpapers.find((w) => w.id === 'aaa111').sha256);
	assert.equal(Buffer.from(await (await fetch(`${base}/api/wallpapers/aaa111/thumb`)).arrayBuffer()).length, 300);
	assert.equal((await call('/api/wallpapers/zzz999')).status, 404);

	// "Add the top 12" skips what is already there; removing deletes the files.
	await call('/admin/api/setup', { body: { password: 'x' } }).catch(() => {});
	await call('/admin/api/login', { body: { password: 'correct horse' } });
	assert.equal((await call('/admin/api/wallpapers/add-top', { body: { q: 'nature', topic: 'nature' } })).status, 202);
	await new Promise((r) => setTimeout(r, 50));
	assert.equal((await (await call('/api/wallpapers')).json()).wallpapers.length, 2); // both search results were already here
	const after = await (await call('/admin/api/wallpapers/aaa111/delete', { body: {} })).json();
	assert.deepEqual(after.wallpapers.map((w) => w.id), ['bbb222']);
	assert.equal((await call('/api/wallpapers/aaa111')).status, 404);
	assert.equal((await call('/admin/api/wallpapers/aaa111/delete', { body: {} })).status, 404);
});

test('as many voice engines run at once as the machine has room for, and no more', async () => {
	const dir = mkdtempSync(path.join(tmpdir(), 'devotion-queue-'));
	try {
		const db = openDb(dir);
		const settings = new Settings(db, {});
		settings.set('piper_model', '/voice.onnx');
		let machine = { cores: 1, memoryMb: 512, usableMb: 410 };
		let running = 0, most = 0;
		const runImpl = async (cmd, args) => {
			if (cmd !== 'lame') { running++; most = Math.max(most, running); }
			await new Promise((r) => setTimeout(r, 10));
			writeFileSync(cmd === 'lame' ? args.at(-1) : args[args.indexOf('--output_file') + 1], Buffer.alloc(2048, 1));
			if (cmd === 'lame') running--;
		};
		const tts = new Tts({ settings, audioDir: path.join(dir, 'audio'), runImpl, planImpl: (p) => plan(machine, p) });
		const six = () => Promise.allSettled([1, 2, 3, 4, 5, 6].map((n) => tts.synthesize(`e${n}-${most}`, 'Hello.')));
		// A small container: one at a time.
		assert.deepEqual((await six()).map((r) => r.status), Array(6).fill('fulfilled'));
		assert.equal(most, 1);
		// The same server after the container was given 4 cores and 2 GB: three at a time, without a restart.
		machine = { cores: 4, memoryMb: 2048, usableMb: 1638 };
		most = 0;
		await six();
		assert.equal(most, 3);
		assert.equal(tts.waiting, 0);
		// Too little memory: nothing is started, and the reason is said.
		machine = { cores: 1, memoryMb: 256, usableMb: 205 };
		assert.match(tts.notReady(), /Not enough memory to make audio/);
		await assert.rejects(tts.synthesize('x', 'Hello.'), /Not enough memory/);
		db.close();
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
});

test('a failed extraction is recorded and retried, without duplicate entries', async (t) => {
	let fail = true;
	const { call, pipeline, db } = await setup(t, { extractResult: () => { if (fail) throw Object.assign(new Error('boom'), { retryable: true }); return { entries: [{ title: 'T', entry_date: '2026-01-02', scripture: null, summary: null, body: 'B' }] }; } });
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	await call('/admin/api/settings', { body: { google_client_id: 'cid', google_client_secret: 'cs', gmail_keywords: 'daily, receipt', anthropic_api_key: 'sk', tts_provider: 'none' } });
	const { url } = await (await call('/admin/api/oauth/start', { body: {} })).json();
	await call(`/oauth/callback?state=${new URL(url).searchParams.get('state')}&code=good-code`);
	await pipeline.runOnce().catch(() => {});
	const first = await pipeline.runOnce();
	assert.equal(db.prepare("SELECT COUNT(*) AS n FROM messages WHERE status = 'error'").get().n, 2);
	assert.equal(first.entries, 0);
	fail = false;
	const second = await pipeline.runOnce();
	assert.equal(second.errors, 0);
	assert.equal(second.entries, 2);
	assert.equal(second.audio, 0); // audio is off
	assert.equal(db.prepare('SELECT entry_date FROM entries LIMIT 1').get().entry_date, '2026-01-02');
});

test('only emails that contain a keyword are imported', async (t) => {
	const { call, calls, pipeline, db } = await setup(t);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	await call('/admin/api/settings', { body: { google_client_id: 'cid', google_client_secret: 'cs', anthropic_api_key: 'sk', tts_provider: 'none' } });
	const { url } = await (await call('/admin/api/oauth/start', { body: {} })).json();
	await call(`/oauth/callback?state=${new URL(url).searchParams.get('state')}&code=good-code`);
	// Without keywords nothing is read at all.
	assert.match((await pipeline.runOnce()).skipped, /No keywords/);
	assert.equal(calls.google.filter((u) => u.includes('/messages')).length, 0);
	// Gmail returns both emails, but only the one with "this day" in it is imported.
	await call('/admin/api/settings', { body: { gmail_keywords: 'THIS  day' } });
	const result = await pipeline.runOnce();
	assert.equal(result.entries, 1);
	assert.equal(calls.claude.length, 1);
	assert.equal(db.prepare("SELECT error FROM messages WHERE gmail_id = 'm2'").get().error, 'None of the keywords appear in this email.');
});

test('update check: newer release, rate limit, missing release, install request', async (t) => {
	let reply = () => json({ tag_name: 'v1.1.0', body: 'notes', html_url: 'https://github.com/o/r/releases/tag/v1.1.0' });
	const { call, calls, config, settings } = await setup(t, { github: () => reply() });
	assert.equal((await call('/admin/api/update/check')).status, 401);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	const found = await (await call('/admin/api/update/check')).json();
	assert.deepEqual({ current: found.current, latest: found.latest, newer: found.newer, tag: found.tag, can_install: found.can_install }, { current: '1.0.0', latest: '1.1.0', newer: true, tag: 'v1.1.0', can_install: true });
	assert.equal(calls.github[0].url, 'https://api.github.com/repos/o/r/releases/latest');
	assert.equal(calls.github[0].headers.authorization, undefined);

	reply = () => json({ tag_name: 'v1.0.0' });
	assert.equal((await (await call('/admin/api/update/check')).json()).newer, false);

	reply = () => json({ message: 'rate limit' }, 403, { 'x-ratelimit-remaining': '0', 'x-ratelimit-reset': '1763209800' });
	const limited = await call('/admin/api/update/check');
	assert.equal(limited.status, 429);
	assert.match((await limited.json()).error, /Try again after \d\d:\d\d UTC, or save a GitHub token/);

	reply = () => json({}, 404);
	assert.equal((await call('/admin/api/update/check')).status, 404);

	settings.set('github_token', 'ghp_abc123');
	reply = () => json({ tag_name: 'v1.1.0' });
	await call('/admin/api/update/check');
	assert.equal(calls.github.at(-1).headers.authorization, 'Bearer ghp_abc123');

	assert.equal((await call('/admin/api/update/install', { body: { tag: 'v1.1.0; reboot' } })).status, 400);
	assert.equal((await call('/admin/api/update/install', { body: { tag: 'v1.1.0' } })).status, 202);
	assert.equal(readFileSync(config.updateRequestFile, 'utf8'), 'v1.1.0\nghp_abc123\n');
});

test('profiles, and notes and highlights kept per profile', async (t) => {
	const { call } = await setup(t);
	assert.deepEqual(await (await call('/api/profiles')).json(), { profiles: [] });
	assert.equal((await call('/api/profiles', { body: { name: '   ' } })).status, 400);
	assert.equal((await call('/api/profiles', { body: { name: 'x'.repeat(61) } })).status, 400);
	const created = await call('/api/profiles', { body: { name: '  Maria   Lopez ' } });
	assert.equal(created.status, 201);
	const maria = await created.json();
	assert.deepEqual({ name: maria.name, existing: maria.existing }, { name: 'Maria Lopez', existing: false });
	// The same name again, in other capitals, is the same person.
	const again = await call('/api/profiles', { body: { name: 'maria lopez' } });
	assert.equal(again.status, 200);
	assert.deepEqual({ id: (await again.json()).id }, { id: maria.id });
	const sam = await (await call('/api/profiles', { body: { name: 'Sam' } })).json();
	assert.equal((await (await call('/api/profiles')).json()).profiles.length, 2);

	const note = { entry_id: 5, start: 10, end: 42, color: 'yellow', comment: 'first', updated_at: '2026-11-15T10:00:00.000Z', deleted: false };
	const id = '3f1c2a9e-7b1d-4c5e-9a10-aaaaaaaaaaaa';
	const put = (profile, body, noteId = id) => call(`/api/profiles/${profile}/annotations/${noteId}`, { body, method: 'PUT' });
	assert.equal((await put(999, note)).status, 404);
	for (const bad of [{ ...note, color: 'red' }, { ...note, start: 50 }, { ...note, entry_id: 0 }, { ...note, updated_at: 'yesterday' }, { ...note, comment: 5 }]) assert.equal((await put(maria.id, bad)).status, 400);
	assert.equal((await put(maria.id, note, 'short')).status, 400);
	assert.equal((await put(maria.id, note, 'has%20spaces%20and%20more')).status, 400);
	assert.deepEqual(await (await put(maria.id, note)).json(), { id, ...note, source: 'app' });

	// An older edit from another device does not undo a newer one.
	assert.equal((await (await put(maria.id, { ...note, comment: 'stale', updated_at: '2026-11-15T09:00:00.000Z' })).json()).comment, 'first');
	assert.equal((await (await put(maria.id, { ...note, comment: 'newer', color: 'none', updated_at: '2026-11-15T11:00:00+00:00' })).json()).comment, 'newer');

	const all = await (await call(`/api/profiles/${maria.id}/annotations`)).json();
	assert.deepEqual(all.annotations.map((a) => [a.id, a.comment, a.color]), [[id, 'newer', 'none']]);
	// Sam sees none of Maria's.
	assert.deepEqual((await (await call(`/api/profiles/${sam.id}/annotations`)).json()).annotations, []);

	// Incremental sync: nothing new, then a deletion arrives as a tombstone.
	const since = `/api/profiles/${maria.id}/annotations?since=${encodeURIComponent(all.server_time)}`;
	assert.equal((await (await call(since)).json()).annotations.length, 0);
	await new Promise((r) => setTimeout(r, 5));
	await put(maria.id, { ...note, updated_at: '2026-11-15T12:00:00.000Z', deleted: true });
	assert.deepEqual((await (await call(since)).json()).annotations.map((a) => a.deleted), [true]);
	assert.equal((await (await call(`/api/profiles/${maria.id}/annotations`)).json()).annotations.length, 0);

	// Highlights in the Bible: entry 0, a version, book and chapter; positions by verse.
	const verse = { entry_id: 0, bible_id: 'kjv', book: 42, chapter: 3, start: 15 * 10000 + 4, end: 16 * 10000 + 12, color: 'green', comment: 'so loved', updated_at: '2026-11-15T10:00:00.000Z' };
	const vid = 'b1b1b1b1-0000-4000-8000-000000000001';
	assert.deepEqual(await (await put(maria.id, verse, vid)).json(), { id: vid, ...verse, deleted: false, source: 'app' });
	for (const bad of [{ ...verse, bible_id: 'KJV!' }, { ...verse, book: -1 }, { ...verse, chapter: 0 }, { ...verse, entry_id: 5 }]) assert.equal((await put(maria.id, bad, vid)).status, 400);
	const listed = (await (await call(`/api/profiles/${maria.id}/annotations`)).json()).annotations;
	assert.deepEqual(listed.map((a) => [a.id, a.bible_id ?? null]), [[vid, 'kjv']]);

	// The app wins conflicts with the website.
	const wid = 'c2c2c2c2-0000-4000-8000-000000000002';
	const fromWeb = { entry_id: 5, start: 1, end: 4, color: 'blue', comment: 'web', source: 'web' };
	const webMade = await (await put(maria.id, fromWeb, wid)).json();
	assert.equal(webMade.source, 'web');
	assert.ok(Date.parse(webMade.updated_at) > Date.parse('2026-11-16T00:00:00Z') - 86400000 * 3650, 'timed by the server');
	// An app edit made earlier by the clock still replaces the website's.
	const appEdit = await (await put(maria.id, { entry_id: 5, start: 1, end: 4, color: 'pink', comment: 'phone', updated_at: '2020-01-01T00:00:00.000Z' }, wid)).json();
	assert.deepEqual([appEdit.comment, appEdit.source], ['phone', 'app']);
	// The website editing without having seen the app's version is refused and told what the app has.
	const stale = await put(maria.id, { ...fromWeb, comment: 'web again', base_updated_at: webMade.updated_at }, wid);
	assert.equal(stale.status, 409);
	assert.equal((await stale.json()).annotation.comment, 'phone');
	// Having seen it, the website may change it.
	const seen = await (await put(maria.id, { ...fromWeb, comment: 'web after seeing', base_updated_at: appEdit.updated_at }, wid)).json();
	assert.deepEqual([seen.comment, seen.source], ['web after seeing', 'web']);
	assert.ok(seen.updated_at > appEdit.updated_at);
	await put(maria.id, { ...verse, deleted: true, updated_at: '2026-11-15T13:00:00.000Z' }, vid);
	await put(maria.id, { ...fromWeb, deleted: true, base_updated_at: seen.updated_at }, wid);

	// Which Bible versions each person has in the app, for the website.
	assert.deepEqual(await (await call(`/api/profiles/${maria.id}/bibles`)).json(), { bibles: [] });
	assert.equal((await call(`/api/profiles/${maria.id}/bibles`, { body: { bibles: ['KJV!'] }, method: 'PUT' })).status, 400);
	assert.equal((await call(`/api/profiles/${maria.id}/bibles`, { body: { bibles: 'kjv' }, method: 'PUT' })).status, 400);
	assert.equal((await call('/api/profiles/999/bibles', { body: { bibles: [] }, method: 'PUT' })).status, 404);
	assert.deepEqual(await (await call(`/api/profiles/${maria.id}/bibles`, { body: { bibles: ['kjv', 'asv', 'kjv'] }, method: 'PUT' })).json(), { bibles: ['asv', 'kjv'] });
	await call(`/api/profiles/${maria.id}/bibles`, { body: { bibles: ['ylt'] }, method: 'PUT' }); // replaces the list
	assert.deepEqual(await (await call(`/api/profiles/${maria.id}/bibles`)).json(), { bibles: ['ylt'] });
	assert.deepEqual(await (await call(`/api/profiles/${sam.id}/bibles`)).json(), { bibles: [] });

	// The admin page lists people and can remove one with their notes.
	await put(sam.id, note);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	let state = await (await call('/admin/api/state')).json();
	assert.deepEqual(state.profiles.map((p) => [p.name, p.notes]), [['Maria Lopez', 0], ['Sam', 1]]);
	state = await (await call(`/admin/api/profiles/${sam.id}/delete`, { body: {} })).json();
	assert.deepEqual(state.profiles.map((p) => p.name), ['Maria Lopez']);
	assert.equal((await call(`/api/profiles/${sam.id}/annotations`)).status, 404);
});

test('bible versions: checked, converted, listed and served to the app', async (t) => {
	const { call, base, calls, settings, time } = await setup(t);
	assert.equal((await call('/admin/api/bibles/tst/install', { body: {} })).status, 401);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	assert.deepEqual(await (await call('/api/bibles')).json(), { bibles: [], audio: false });
	assert.equal((await call('/admin/api/bibles/nope/install', { body: {} })).status, 404);
	assert.equal((await call('/admin/api/bibles/tst/install', { body: {} })).status, 202);
	assert.equal((await call('/admin/api/bibles/bad/install', { body: {} })).status, 202);
	let state;
	for (let i = 0; i < 50; i++) {
		state = await (await call('/admin/api/state')).json();
		if (state.bibles.every((b) => !b.downloading) && state.bibles[0].installed) break;
		await new Promise((r) => setTimeout(r, 10));
	}
	// The one whose download does not match its checksum is not installed.
	assert.deepEqual(state.bibles.map((b) => [b.id, b.installed]), [['tst', true], ['bad', false]]);
	const list = (await (await call('/api/bibles')).json()).bibles;
	assert.deepEqual(list.map((b) => [b.id, b.name, b.language]), [['tst', 'Test Version', 'xx']]);
	// Compressed for clients that accept it, and identical either way.
	const gz = await fetch(`${base}/api/bibles/tst`, { headers: { 'accept-encoding': 'gzip' } });
	assert.equal(gz.headers.get('content-encoding'), 'gzip');
	const plain = await fetch(`${base}/api/bibles/tst`, { headers: { 'accept-encoding': 'identity' } });
	const bytes = Buffer.from(await plain.arrayBuffer());
	assert.equal(bytes.length, list[0].bytes);
	assert.equal(createHash('sha256').update(bytes).digest('hex'), list[0].sha256);
	// Chapters in order, verses by number (a missing verse keeps its place), whitespace tidied.
	assert.deepEqual(await gz.json(), { id: 'tst', name: 'Test Version', language: 'xx', books: [{ name: 'I Book', chapters: [['In the beginning', '', 'third'], ['c2v1']] }] });
	assert.equal((await call('/api/bibles/nope')).status, 404);

	// Listening: Piper reads the Bible once it has a voice (nothing to choose); a chapter is made when first asked for, and kept.
	assert.equal((await call('/api/bibles/tst/audio/0/1')).status, 404);
	assert.equal((await call('/api/bibles/tst/audio/0/1?voice=premium')).status, 404);
	assert.deepEqual(chapterParts('John', 1, ['a', '', 'c']), [{ verse: 1, text: 'John, chapter 1, verse 1. a' }, { verse: 3, text: 'Verse 3. c' }]);
	assert.deepEqual(chapterParts('John', 3, ['', 'b']), [{ verse: 2, text: 'Chapter 3, verse 2. b' }]);
	assert.deepEqual(chapterParts('Psalms', 23, ['x']).concat(chapterParts('Psalms', 1, ['x']), chapterParts('Jude', 1, ['x'])).map((p) => p.text), ['Psalm 23, verse 1. x', 'Psalms, Psalm 1, verse 1. x', 'Jude, verse 1. x']);
	await call('/admin/api/settings', { body: {} });
	settings.set('piper_model', '/voice.onnx');
	assert.deepEqual((({ audio, audio_premium }) => [audio, audio_premium])(await (await call('/api/bibles')).json()), [true, undefined]);
	// A speech API on the internet is not something the server chooses for the Bible.
	await call('/admin/api/settings', { body: { bible_engine: 'openai' } });
	assert.equal(settings.get('bible_engine'), '');
	assert.equal((await call('/api/bibles/tst/audio/0/9')).status, 404);
	assert.equal((await call('/api/bibles/nope/audio/0/1')).status, 404);
	const listen = async (url) => { let r; for (let i = 0; i < 100; i++) { r = await call(url); if (r.status !== 202) break; assert.deepEqual(await r.json(), { status: 'making' }); await new Promise((res) => setTimeout(res, 10)); } return r; };
	assert.equal((await call('/api/bibles/tst/audio/0/1')).status, 202);
	let heard = await listen('/api/bibles/tst/audio/0/1');
	assert.deepEqual([heard.status, heard.headers.get('content-type'), heard.headers.get('content-length')], [200, 'audio/mpeg', '2048']);
	// One line per verse, each announced; the times come from the sound piper made for each line.
	assert.equal(calls.run.filter((c) => c.cmd === 'piper').at(-1).input, 'I Book, chapter 1, verse 1. In the beginning\nVerse 3. third\n');
	assert.equal((await call('/api/bibles/tst/audio/0/9/verses')).status, 404);
	assert.deepEqual(await (await call('/api/bibles/tst/audio/0/1/verses')).json(), { duration: 3.25, exact: true, verses: [{ verse: 1, start: 0, end: 2.25 }, { verse: 3, start: 2.5, end: 3.25 }] });
	// A player jumping to a verse asks for part of the file.
	const part = await call('/api/bibles/tst/audio/0/1', { headers: { range: 'bytes=1000-1999' } });
	assert.deepEqual([part.status, part.headers.get('content-range'), (await part.arrayBuffer()).byteLength], [206, 'bytes 1000-1999/2048', 1000]);
	assert.equal((await call('/api/bibles/tst/audio/0/1', { headers: { range: 'bytes=5000-' } })).status, 416);
	const runs = calls.run.length;
	assert.equal((await call('/api/bibles/tst/audio/0/1')).status, 200); // kept: not made again
	assert.equal(calls.run.length, runs);
	state = await (await call('/admin/api/state')).json();
	assert.deepEqual([state.bible_audio, state.engines.bible_engine], [{ files: 1, bytes: 2048 }, 'piper']);
	// A piper too old to write a file per line: read as one text, times estimated.
	calls.oldPiper = true;
	await call('/admin/api/settings', { body: { tts_rate: 'slow' } });
	heard = await listen('/api/bibles/tst/audio/0/1');
	assert.equal(heard.status, 200);
	assert.equal(calls.run.filter((c) => c.cmd === 'piper').at(-1).input, 'I Book, chapter 1, verse 1. In the beginning\nVerse 3. third\n');
	assert.equal((await (await call('/api/bibles/tst/audio/0/1/verses')).json()).exact, false);
	calls.oldPiper = false;
	await call('/admin/api/settings', { body: { tts_rate: 'normal' } });
	// A local speech server (OpenAI's API on the owner's network, no key): its own voice, its own files.
	await call('/admin/api/settings', { body: { bible_engine: 'local' } });
	assert.equal((await (await call('/api/bibles')).json()).audio, true); // falls back to piper until an address is set
	await call('/admin/api/settings', { body: { tts_local_base_url: 'ftp://nope', tts_local_voice: 'Bella' } });
	assert.equal(settings.get('tts_local_base_url'), '');
	await call('/admin/api/settings', { body: { tts_local_base_url: 'http://192.168.1.50:8880/v1/' } });
	heard = await listen('/api/bibles/tst/audio/0/2');
	assert.deepEqual([heard.status, heard.headers.get('content-length')], [200, '4096']);
	// This stand-in answers MP3 whatever is asked, as some servers do: the chapter is then read as one text, with estimated times.
	assert.deepEqual(calls.speech.slice(-2).map((c) => [c.url, c.auth, c.body.voice, c.body.model, c.body.response_format, c.body.input]), [
		['http://192.168.1.50:8880/v1/audio/speech', undefined, 'Bella', 'kokoro', 'wav', 'Chapter 2, verse 1. c2v1'],
		['http://192.168.1.50:8880/v1/audio/speech', undefined, 'Bella', 'kokoro', 'mp3', 'Chapter 2, verse 1. c2v1']]);
	assert.deepEqual(await (await call('/api/bibles/tst/audio/0/2/verses')).json(), { duration: 0, exact: false, verses: [{ verse: 1, start: 0, end: 0 }] });
	// A speech server that does answer WAV: one request per verse, exact times, joined and encoded here.
	calls.speechWav = true;
	await call('/admin/api/settings', { body: { tts_local_voice: 'Nicole' } });
	heard = await listen('/api/bibles/tst/audio/0/1');
	assert.deepEqual([heard.status, heard.headers.get('content-length')], [200, '2048']);
	assert.deepEqual(calls.speech.slice(-2).map((c) => [c.body.response_format, c.body.input]), [['wav', 'I Book, chapter 1, verse 1. In the beginning'], ['wav', 'Verse 3. third']]);
	assert.deepEqual(await (await call('/api/bibles/tst/audio/0/1/verses')).json(), { duration: 2.25, exact: true, verses: [{ verse: 1, start: 0, end: 1 }, { verse: 3, start: 1.25, end: 2.25 }] });
	calls.speechWav = false;
	await call('/admin/api/settings', { body: { tts_local_voice: 'Bella' } });
	// A failure is reported once, and asking again tries again.
	calls.failSpeech = 'model not loaded';
	assert.equal((await call('/api/bibles/tst/audio/0/1')).status, 202);
	heard = await listen('/api/bibles/tst/audio/0/1');
	assert.equal(heard.status, 502);
	assert.match((await heard.json()).error, /local speech server answered 500: model not loaded/);
	calls.failSpeech = null;
	assert.equal((await listen('/api/bibles/tst/audio/0/1')).status, 200);
	// The speech API reads the Bible only for a person who asks for it (the app's "Bible voice"): its own files, next to Piper's.
	await call('/admin/api/settings', { body: { bible_engine: 'piper' } });
	assert.equal((await call('/api/bibles/tst/audio/0/2?voice=premium')).status, 404); // no key yet
	await call('/admin/api/settings', { body: { tts_openai_key: 'sk-test', tts_openai_voice: 'Nova' } });
	assert.deepEqual((await (await call('/api/bibles')).json()).audio_premium, { id: 'api-nova', name: 'Nova' });
	calls.speechWav = true;
	const speechBefore = calls.speech.length, piperBefore = calls.run.filter((c) => c.cmd === 'piper').length;
	heard = await listen('/api/bibles/tst/audio/0/2?voice=premium');
	assert.equal(heard.status, 200);
	assert.deepEqual(calls.speech.slice(speechBefore).map((c) => [c.url, c.auth, c.body.voice, c.body.input]), [['https://api.openai.com/v1/audio/speech', 'Bearer sk-test', 'Nova', 'Chapter 2, verse 1. c2v1']]);
	assert.deepEqual(await (await call('/api/bibles/tst/audio/0/2/verses?voice=premium')).json(), { duration: 1, exact: true, verses: [{ verse: 1, start: 0, end: 1 }] });
	// Without asking for it, the same chapter is Piper's, and the speech API hears nothing of it.
	heard = await listen('/api/bibles/tst/audio/0/2');
	assert.equal(heard.status, 200);
	assert.equal(calls.run.filter((c) => c.cmd === 'piper').length, piperBefore + 1);
	assert.equal(calls.speech.length, speechBefore + 1);
	calls.speechWav = false;
	// The owner can keep people from choosing it.
	await call('/admin/api/settings', { body: { bible_premium: 'off' } });
	assert.equal((await (await call('/api/bibles')).json()).audio_premium, undefined);
	assert.equal((await call('/api/bibles/tst/audio/0/2?voice=premium')).status, 404);
	await call('/admin/api/settings', { body: { bible_premium: 'on' } });
	// The speech API bills for every chapter and listening needs no login: only so many new chapters in a day.
	await call('/admin/api/settings', { body: { bible_premium_daily: '1' } });
	calls.speechWav = true;
	assert.equal((await call('/api/bibles/tst/audio/0/2?voice=premium')).status, 200); // one it has read already is not a new one
	const over = await call('/api/bibles/tst/audio/0/1?voice=premium');
	assert.equal(over.status, 429);
	assert.match((await over.json()).error, /as many new chapters as this server allows in a day/);
	assert.equal(calls.speech.length, speechBefore + 1);
	assert.equal((await listen('/api/bibles/tst/audio/0/1')).status, 200); // the server's own voice has no such limit
	time.ahead += 86_400_000 + 1000;
	assert.equal((await listen('/api/bibles/tst/audio/0/1?voice=premium')).status, 200);
	calls.speechWav = false;
	await call('/admin/api/settings', { body: { bible_premium_daily: '100' } });

	// "Read the whole Bible with Piper": every chapter of a version ahead of time, started by hand.
	await call('/admin/api/settings', { body: { bible_engine: 'local' } });
	assert.equal((await call('/admin/api/bible-audio/read-all', { body: { version: 'tst' } })).status, 400); // not read by Piper
	await call('/admin/api/settings', { body: { bible_engine: 'piper' } });
	assert.equal((await call('/admin/api/bible-audio/read-all', { body: { version: 'nope' } })).status, 404);
	assert.equal((await call('/admin/api/bible-audio/read-all', { body: {} })).status, 400);
	state = await (await call('/admin/api/bible-audio/clear', { body: {} })).json();
	const piperRuns = () => calls.run.filter((c) => c.cmd === 'piper').length;
	const before = piperRuns();
	const started = await call('/admin/api/bible-audio/read-all', { body: { version: 'tst' } });
	assert.equal(started.status, 202);
	state = await started.json();
	assert.deepEqual([state.bible_read_all.version, state.bible_read_all.running, settings.get('bible_read_all')], ['tst', true, 'tst']);
	assert.equal((await call('/admin/api/bible-audio/read-all', { body: { version: 'tst' } })).status, 409); // one at a time
	for (let i = 0; i < 200 && state.bible_read_all.running; i++) { await new Promise((r) => setTimeout(r, 10)); state = await (await call('/admin/api/state')).json(); }
	const total = state.bible_read_all.total;
	assert.ok(total >= 2);
	assert.deepEqual(state.bible_read_all, { version: 'tst', name: state.bible_read_all.name, done: total, total, failed: 0, running: false, note: 'Finished.' });
	assert.deepEqual([state.bible_audio.files, piperRuns() - before, settings.get('bible_read_all')], [total, total, '']);
	assert.equal((await call('/api/bibles/tst/audio/0/2')).status, 200); // there at once for a listener
	// Started again, the chapters already kept are not read again.
	state = await (await call('/admin/api/bible-audio/read-all', { body: { version: 'tst' } })).json();
	for (let i = 0; i < 200 && state.bible_read_all.running; i++) { await new Promise((r) => setTimeout(r, 10)); state = await (await call('/admin/api/state')).json(); }
	assert.deepEqual([state.bible_read_all.done, piperRuns() - before], [total, total]);
	// Stopping is remembered, so a restart does not take it up again.
	settings.set('bible_read_all', 'tst');
	state = await (await call('/admin/api/bible-audio/read-all/stop', { body: {} })).json();
	assert.equal(settings.get('bible_read_all'), '');

	state = await (await call('/admin/api/bible-audio/clear', { body: {} })).json();
	assert.deepEqual(state.bible_audio, { files: 0, bytes: 0 });
	await call('/admin/api/settings', { body: { bible_engine: 'none' } });
	assert.deepEqual((({ audio, audio_premium }) => [audio, audio_premium])(await (await call('/api/bibles')).json()), [false, undefined]);
	assert.equal((await call('/api/bibles/tst/audio/0/2?voice=premium')).status, 404);

	await call('/admin/api/bibles/tst/delete', { body: {} });
	assert.equal((await call('/api/bibles/tst')).status, 404);

	// The version the app carries with it ("tst" here): the server fetches it by itself when someone listens to it.
	await call('/admin/api/settings', { body: { bible_engine: 'piper' } });
	assert.equal((await call('/api/bibles/nope/audio/0/1')).status, 404);
	assert.equal((await call('/api/bibles/tst/audio/0/1')).status, 202);
	assert.equal((await listen('/api/bibles/tst/audio/0/1')).status, 200);
	assert.deepEqual((await (await call('/api/bibles')).json()).bibles.map((b) => b.id), ['tst']);
});

test('voices: every entry is made in every voice, and the app chooses', async (t) => {
	const { call, calls, settings, connectGmail, dir, pipeline, db } = await setup(t);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	await call('/admin/api/settings', { body: { google_client_id: 'cid', google_client_secret: 'cs', gmail_keywords: 'daily', anthropic_api_key: 'sk', tts_rate: 'slow' } });
	settings.set('piper_model', '/opt/voices/builtin.onnx');
	let state = await (await call('/admin/api/state')).json();
	assert.deepEqual(state.voices.map((v) => [v.id, v.installed, v.selected]), [['builtin', true, true], ['amy', false, false], ['bad', false, false]]);
	const errors = [];
	const settle = async () => {
		for (let i = 0; i < 100; i++) {
			state = await (await call('/admin/api/state')).json();
			if (state.background_error) errors.push(state.background_error);
			if (state.voices.every((v) => !v.downloading) && !pipeline.audioBusy && i > 1) return;
			await new Promise((r) => setTimeout(r, 10));
		}
	};

	// One voice: the entry is made in it.
	await connectGmail();
	await settle();
	const piperRuns = () => calls.run.filter((c) => c.cmd === 'piper').map((c) => [path.basename(c.args[1]), c.args[3]]);
	assert.deepEqual(piperRuns(), [['builtin.onnx', '1.18']]);
	const first = await (await call('/api/entries')).json();

	// A download that fails its checksum adds nothing.
	await call('/admin/api/voices/bad/install', { body: {} });
	await settle();
	assert.equal(state.voices.find((v) => v.id === 'bad').installed, false);
	assert.match(errors.join(' '), /checksum/);

	// Downloading a voice makes the existing entry in it too, without changing the default.
	await call('/admin/api/voices/amy/install', { body: {} });
	await settle();
	assert.deepEqual(state.voices.map((v) => [v.id, v.installed, v.selected]), [['builtin', true, true], ['amy', true, false], ['bad', false, false]]);
	assert.deepEqual(piperRuns(), [['builtin.onnx', '1.18'], ['amy.onnx', '1.18']]);
	assert.equal(piperRuns().length, 2);
	assert.deepEqual(calls.run.find((c) => c.cmd === 'piper' && c.args[1].endsWith('amy.onnx')).args[1], path.join(dir, 'voices', 'amy.onnx'));
	assert.deepEqual(state.entries.map((e) => [e.audio_count, state.voice_count]), [[2, 2]]);
	const voicesNow = await (await call('/api/voices')).json();
	assert.deepEqual(voicesNow, { voices: [{ id: 'builtin', name: 'Built in', description: 'ships with the server' }, { id: 'amy', name: 'Amy', description: 'female' }], default: 'builtin' });
	// The app sees the new voice in an ordinary incremental sync.
	const changed = (await (await call(`/api/entries?since=${encodeURIComponent(first.server_time)}`)).json()).entries;
	assert.deepEqual(changed.map((e) => e.audio.map((a) => a.voice)), [['amy', 'builtin']]);
	const [amy, builtin] = changed[0].audio;
	const fetchSha = async (q) => createHash('sha256').update(Buffer.from(await (await call(`/api/entries/${changed[0].id}/audio${q}`)).arrayBuffer())).digest('hex');
	assert.equal(await fetchSha('?voice=amy'), amy.sha256);
	assert.equal(await fetchSha('?voice=builtin'), builtin.sha256);
	assert.equal(await fetchSha(''), builtin.sha256); // the default voice
	assert.equal(changed[0].audio_sha256, builtin.sha256);

	// Making amy the default changes what older apps get, and tells them.
	const beforeDefault = (await (await call('/api/entries')).json()).server_time;
	await new Promise((r) => setTimeout(r, 5));
	state = await (await call('/admin/api/voices/amy/select', { body: {} })).json();
	assert.equal(settings.get('piper_voice'), 'amy');
	const afterDefault = (await (await call(`/api/entries?since=${encodeURIComponent(beforeDefault)}`)).json()).entries;
	assert.equal(afterDefault[0].audio_sha256, amy.sha256);
	assert.equal(await fetchSha(''), amy.sha256);
	assert.equal(piperRuns().length, 2); // choosing a default makes nothing new

	// "Redo audio" remakes every voice.
	await call(`/admin/api/entries/${changed[0].id}/audio`, { body: {} });
	await settle();
	assert.equal(piperRuns().length, 4);

	// The default can't be removed; another voice can, with its audio.
	assert.equal((await call('/admin/api/voices/builtin/delete', { body: {} })).status, 409);
	await call('/admin/api/voices/builtin/select', { body: {} });
	const beforeRemove = (await (await call('/api/entries')).json()).server_time;
	await new Promise((r) => setTimeout(r, 5));
	state = await (await call('/admin/api/voices/amy/delete', { body: {} })).json();
	assert.equal(state.voices.find((v) => v.id === 'amy').installed, false);
	const afterRemove = (await (await call(`/api/entries?since=${encodeURIComponent(beforeRemove)}`)).json()).entries;
	assert.deepEqual(afterRemove.map((e) => e.audio.map((a) => a.voice)), [['builtin']]);
	assert.equal(db.prepare("SELECT COUNT(*) AS n FROM entry_audio WHERE voice = 'amy'").get().n, 0);
	assert.deepEqual(readdirSync(path.join(dir, 'audio')).filter((f) => f.includes('-amy-')), []);
});

test('audio made before voices were tracked is kept and given its voice', async () => {
	const dir = mkdtempSync(path.join(tmpdir(), 'devotion-legacy-'));
	try {
		// A database as version 0.1.0 left it: one audio file per entry, in the entries table.
		const old = new DatabaseSync(path.join(dir, 'devotion.db'));
		old.exec(`CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, gmail_id TEXT, position INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL, entry_date TEXT, scripture TEXT, summary TEXT, body TEXT NOT NULL, source_from TEXT, source_subject TEXT, audio_file TEXT, audio_bytes INTEGER, audio_sha256 TEXT, audio_error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT)`);
		old.prepare("INSERT INTO entries (title, body, audio_file, audio_bytes, audio_sha256, created_at, updated_at) VALUES ('T', 'B', '1-x.mp3', 99, 'abc', '2026-11-01T00:00:00.000Z', '2026-11-01T00:00:00.000Z')").run();
		old.close();
		const db = openDb(dir);
		const settings = new Settings(db, {});
		settings.set('piper_model', '/opt/voices/lessac.onnx');
		const tts = new Tts({ settings, audioDir: path.join(dir, 'audio'), voices: new Voices({ settings, dataDir: dir, catalog: [] }) });
		const pipeline = new Pipeline({ db, settings, gmail: {}, extractor: {}, tts, audioDir: path.join(dir, 'audio'), log: quiet });
		pipeline.adoptLegacyAudio();
		assert.deepEqual(db.prepare('SELECT entry_id, voice, file, bytes, sha256 FROM entry_audio').all().map((r) => ({ ...r })), [{ entry_id: 1, voice: 'lessac', file: '1-x.mp3', bytes: 99, sha256: 'abc' }]);
		assert.equal(db.prepare('SELECT audio_file FROM entries').get().audio_file, null);
		db.close();
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
});

test('archive: old entries go to the share, and only then leave the server', async (t) => {
	const { call, calls, settings, pipeline, db, share, archiver, audioDir, connectGmail } = await setup(t);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	await call('/admin/api/settings', { body: { google_client_id: 'cid', google_client_secret: 'cs', gmail_keywords: 'daily', anthropic_api_key: 'sk' } });
	settings.set('piper_model', '/voice.onnx');
	// The test email is dated November 2025: a year old, so due at once. Without a share nothing moves.
	await connectGmail();
	assert.equal(calls.smb.length, 0);
	let entry = (await (await call('/api/entries')).json()).entries[0];
	assert.deepEqual([entry.archived, entry.has_audio, entry.body], [false, true, 'Give us this day.']);
	const audioFile = db.prepare('SELECT file FROM entry_audio').get().file;
	const syncedAt = (await (await call('/api/entries')).json()).server_time;

	assert.equal((await call('/admin/api/archive/test', { body: {} })).status, 400);
	await call('/admin/api/settings', { body: { smb_share: '//nas/backups', smb_folder: 'Devotion/Old; rm', smb_username: 'me', smb_password: 'hunter2' } });
	share.fail = 'session setup failed: NT_STATUS_LOGON_FAILURE';
	const refused = await call('/admin/api/archive/test', { body: {} });
	assert.equal(refused.status, 502);
	assert.match((await refused.json()).error, /user name or password was not accepted/);
	share.fail = null;
	assert.deepEqual(await (await call('/admin/api/archive/test', { body: {} })).json(), { ok: true, folder: 'Devotion/Old rm' });

	// A share that takes the file short: the entry stays whole on the server.
	share.truncate = true;
	await assert.rejects(archiver.runOnce(), /Could not archive "Daily Bread"/);
	entry = (await (await call('/api/entries')).json()).entries[0];
	assert.deepEqual([entry.archived, entry.has_audio, entry.body], [false, true, 'Give us this day.']);
	assert.ok(readFileSync(path.join(audioDir, audioFile)).length > 0);
	assert.match((await (await call('/admin/api/state')).json()).archive.last_error, /Could not archive/);

	share.truncate = false;
	share.files.clear();
	assert.equal(await archiver.runOnce(), 1);
	const names = [...share.files.keys()];
	assert.deepEqual(names, [`Devotion\\Old rm\\2025\\11\\2025-11-15_${entry.id}_Daily-Bread.txt`, `Devotion\\Old rm\\2025\\11\\2025-11-15_${entry.id}_Daily-Bread_voice.mp3`]);
	assert.match(share.files.get(names[0]).toString(), /^Daily Bread\n2025-11-15\nMatthew 6:11\n\nSummary: On provision\.\n\nGive us this day\.\n\nEmail: Daily <b>bread<\/b>/);
	assert.equal(share.files.get(names[1]).length, 2048);
	assert.ok(calls.smb.at(-1)[calls.smb.at(-1).indexOf('-c') + 1].startsWith('mkdir "Devotion"; mkdir "Devotion\\Old rm"; mkdir "Devotion\\Old rm\\2025"; mkdir "Devotion\\Old rm\\2025\\11"; put '));

	// On the server it is now a stub: title, date, scripture and summary, no text or audio.
	const changed = (await (await call(`/api/entries?since=${encodeURIComponent(syncedAt)}`)).json()).entries;
	assert.deepEqual(changed.map((e) => [e.title, e.archived, e.deleted, e.body, e.has_audio, e.summary, e.scripture, e.references.length]), [['Daily Bread', true, false, '', false, 'On provision.', 'Matthew 6:11', 1]]);
	assert.equal((await (await call('/api/entries')).json()).entries[0].archived, true);
	assert.equal((await call(`/api/entries/${entry.id}/audio`)).status, 404);
	assert.throws(() => readFileSync(path.join(audioDir, audioFile)));
	const state = await (await call('/admin/api/state')).json();
	assert.deepEqual([state.archive.archived_total, state.archive.waiting, state.archive.last_error, state.entries.length], [1, 0, null, 0]);
	assert.deepEqual(state.resources, { cores: 1, memory_mb: 1024, usable_mb: 819, audio_jobs: 1, note: 'Making audio one entry at a time.' });
	assert.equal(state.pipeline.audio_progress, null);
	// Nothing left to do, and audio is not made again for an archived entry.
	assert.equal(await archiver.runOnce(), 0);
	assert.equal((await pipeline.runOnce()).audio, 0);
});
