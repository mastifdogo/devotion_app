// End to end with the outside world faked: Google, the Anthropic client, the
// speech engine and GitHub. Everything in between is the real code.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { openDb } from '../src/db.js';
import { Settings } from '../src/settings.js';
import { Gmail } from '../src/gmail.js';
import { Extractor } from '../src/extract.js';
import { Tts } from '../src/tts.js';
import { Pipeline } from '../src/pipeline.js';
import { Updater } from '../src/update.js';
import { createServer } from '../src/server.js';
import { Voices } from '../src/voices.js';
import { Bibles } from '../src/bibles.js';
import { Archiver } from '../src/archive.js';

const b64 = (s) => Buffer.from(s).toString('base64url');
const json = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });
const quiet = { info() {}, error() {} };

async function setup(t, { extractResult, github } = {}) {
	const dir = mkdtempSync(path.join(tmpdir(), 'devotion-test-'));
	const audioDir = path.join(dir, 'audio');
	const config = { version: '1.0.0', port: 0, dataDir: dir, adminPassword: '', githubRepo: 'o/r', updateRequestFile: path.join(dir, 'update-request'), env: {} };
	const db = openDb(dir);
	const settings = new Settings(db, {});
	const calls = { google: [], claude: [], run: [], github: [], smb: [], web: [] };
	const mails = {
		m1: { id: 'm1', internalDate: '1763200000000', payload: { mimeType: 'text/html', headers: [{ name: 'Subject', value: 'Daily <b>bread</b>' }, { name: 'From', value: 'daily@example.org' }], body: { data: b64('<p>Give us this day.</p><p>Unsubscribe</p>') } } },
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
	const tts = new Tts({ settings, audioDir, voices, runImpl });
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
			else if (ls && ls[1].endsWith('.*')) {
				for (const [name, data] of share.files) if (name.startsWith(ls[1].slice(0, -1))) out += `  ${name.split('\\').pop()}      A   ${share.truncate ? 1 : data.length}  Sun Nov 15 12:00:00 2026\n`;
			} else if (ls) out += `  ${ls[1].split('\\').pop()}                D        0  Sun Nov 15 12:00:00 2026\n\n\t\t151361008 blocks of size 1024. 94880016 blocks available\n`; // as the real program lists a folder
		}
		return { code: 0, out };
	};
	const archiver = new Archiver({ db, settings, audioDir, runImpl: smbRun, log: quiet });
	const pipeline = new Pipeline({ db, settings, gmail, extractor, tts, audioDir, archiver, log: quiet });
	const updater = new Updater({ config, settings, fetchImpl: async (url, init) => { calls.github.push({ url: String(url), headers: init.headers }); return github(); } });
	const server = createServer({ config, db, settings, gmail, pipeline, tts, updater, voices, bibles, archiver, audioDir, log: quiet });
	await new Promise((r) => server.listen(0, '127.0.0.1', r));
	const base = `http://127.0.0.1:${server.address().port}`;
	t.after(() => { server.close(); server.closeAllConnections(); db.close(); rmSync(dir, { recursive: true, force: true }); });

	let cookie = '';
	const call = async (p, { body, method } = {}) => {
		const headers = {};
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
	return { call, calls, settings, db, pipeline, gmail, config, dir, audioDir, share, archiver, base, connectGmail, dropCookie: () => (cookie = '') };
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
});

test('settings: secrets are write-only and an empty secret keeps the saved one', async (t) => {
	const { call, settings } = await setup(t);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	let state = await (await call('/admin/api/settings', { body: { anthropic_api_key: 'sk-secret', gmail_query: ' from:x ', poll_minutes: 'abc', tts_provider: 'bogus', piper_voice: 'hijack', google_refresh_token: 'hijack', smb_share: '-U root //nas/share', archive_days: '0', tts_rate: 'fast' } })).json();
	assert.equal(state.settings.anthropic_api_key_set, true);
	assert.equal(state.settings.gmail_query, 'from:x');
	assert.equal(state.settings.poll_minutes, '15');
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
});

test('gmail oauth, mail to entries to audio, and the app API', async (t) => {
	const { call, calls, settings, pipeline, audioDir, dropCookie } = await setup(t);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	assert.equal((await call('/admin/api/oauth/start', { body: {} })).status, 400); // no client id yet
	await call('/admin/api/settings', { body: { google_client_id: 'cid', google_client_secret: 'cs', gmail_keywords: 'Daily\nreceipt', gmail_query: 'from:daily@example.org', anthropic_api_key: 'sk', piper_model: '/voice.onnx' } });

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
	assert.equal(calls.run[0].input, 'Daily Bread.\nMatthew 6:11.\nGive us this day.\n');
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
	assert.deepEqual({ ...entry, id: 0, created_at: '', updated_at: '', audio_sha256: '' }, {
		id: 0, title: 'Daily Bread', entry_date: '2025-11-15', scripture: 'Matthew 6:11', summary: 'On provision.', body: 'Give us this day.',
		references: [{ start: 8, end: 16, book: 'Matthew', book_index: 39, chapter: 6, verse_start: 11, verse_end: null }],
		has_audio: true, audio_bytes: 2048, audio_sha256: '', source_from: 'daily@example.org', source_subject: 'Daily <b>bread</b>', created_at: '', updated_at: '', archived: false, deleted: false,
	});
	const audio = await call(`/api/entries/${entry.id}/audio`);
	assert.equal(audio.headers.get('content-type'), 'audio/mpeg');
	const bytes = Buffer.from(await audio.arrayBuffer());
	assert.equal(createHash('sha256').update(bytes).digest('hex'), entry.audio_sha256);
	assert.equal((await call('/api/entries/999/audio')).status, 404);

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
	assert.deepEqual(await (await put(maria.id, note)).json(), { id, ...note });

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
	const { call, base } = await setup(t);
	assert.equal((await call('/admin/api/bibles/tst/install', { body: {} })).status, 401);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	assert.deepEqual(await (await call('/api/bibles')).json(), { bibles: [] });
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
	await call('/admin/api/bibles/tst/delete', { body: {} });
	assert.equal((await call('/api/bibles/tst')).status, 404);
});

test('voices: download on choosing, checksum enforced, used for new audio', async (t) => {
	const { call, calls, settings, connectGmail, dir } = await setup(t);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	await call('/admin/api/settings', { body: { google_client_id: 'cid', google_client_secret: 'cs', gmail_keywords: 'daily', anthropic_api_key: 'sk', piper_model: '/opt/voices/builtin.onnx', tts_rate: 'slow' } });
	let state = await (await call('/admin/api/state')).json();
	assert.deepEqual(state.voices.map((v) => [v.id, v.installed, v.selected]), [['builtin', true, true], ['amy', false, false], ['bad', false, false]]);
	const settle = async () => { for (let i = 0; i < 50; i++) { state = await (await call('/admin/api/state')).json(); if (state.voices.every((v) => !v.downloading) && i > 1) return; await new Promise((r) => setTimeout(r, 10)); } };
	await call('/admin/api/voices/bad/select', { body: {} });
	await settle();
	assert.equal(state.voices.find((v) => v.id === 'bad').installed, false);
	assert.equal(settings.get('piper_voice'), '');
	await call('/admin/api/voices/amy/select', { body: {} });
	await settle();
	assert.deepEqual(state.voices.map((v) => [v.id, v.installed, v.selected]), [['builtin', true, false], ['amy', true, true], ['bad', false, false]]);

	await connectGmail();
	const piper = calls.run.find((c) => c.cmd === 'piper');
	assert.deepEqual(piper.args.slice(0, 4), ['--model', path.join(dir, 'voices', 'amy.onnx'), '--length_scale', '1.18']);

	// Back to the built-in voice; the downloaded one can then be removed.
	await call('/admin/api/voices/builtin/select', { body: {} });
	assert.equal(settings.get('piper_voice'), '');
	state = await (await call('/admin/api/voices/amy/delete', { body: {} })).json();
	assert.equal(state.voices.find((v) => v.id === 'amy').installed, false);
});

test('archive: old entries go to the share, and only then leave the server', async (t) => {
	const { call, calls, settings, pipeline, db, share, archiver, audioDir, connectGmail } = await setup(t);
	await call('/admin/api/setup', { body: { password: 'correct horse' } });
	await call('/admin/api/settings', { body: { google_client_id: 'cid', google_client_secret: 'cs', gmail_keywords: 'daily', anthropic_api_key: 'sk', piper_model: '/voice.onnx' } });
	// The test email is dated November 2025: a year old, so due at once. Without a share nothing moves.
	await connectGmail();
	assert.equal(calls.smb.length, 0);
	let entry = (await (await call('/api/entries')).json()).entries[0];
	assert.deepEqual([entry.archived, entry.has_audio, entry.body], [false, true, 'Give us this day.']);
	const audioFile = db.prepare('SELECT audio_file FROM entries').get().audio_file;
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
	assert.deepEqual(names, [`Devotion\\Old rm\\2025\\11\\2025-11-15_${entry.id}_Daily-Bread.txt`, `Devotion\\Old rm\\2025\\11\\2025-11-15_${entry.id}_Daily-Bread.mp3`]);
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
	// Nothing left to do, and audio is not made again for an archived entry.
	assert.equal(await archiver.runOnce(), 0);
	assert.equal((await pipeline.runOnce()).audio, 0);
});
