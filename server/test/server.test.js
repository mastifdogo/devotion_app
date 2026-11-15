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

const b64 = (s) => Buffer.from(s).toString('base64url');
const json = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });
const quiet = { info() {}, error() {} };

async function setup(t, { extractResult, github } = {}) {
	const dir = mkdtempSync(path.join(tmpdir(), 'devotion-test-'));
	const audioDir = path.join(dir, 'audio');
	const config = { version: '1.0.0', port: 0, dataDir: dir, adminPassword: '', githubRepo: 'o/r', updateRequestFile: path.join(dir, 'update-request'), env: {} };
	const db = openDb(dir);
	const settings = new Settings(db, {});
	const calls = { google: [], claude: [], run: [], github: [] };
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
		const result = extractResult ? extractResult(params) : params.messages[0].content.includes('Receipt') ? { entries: [] } : { entries: [{ title: 'Daily Bread', entry_date: null, scripture: 'Matthew 6:11', summary: 'On provision.', body: 'Give us this day.' }] };
		return { stop_reason: 'end_turn', content: [{ type: 'text', text: JSON.stringify(result) }] };
	} }) } } };
	const extractor = new Extractor({ settings, clientFactory: () => fakeClient });
	const runImpl = async (cmd, args, opts) => {
		calls.run.push({ cmd, args, input: opts?.input });
		// piper writes the wav named after --output_file; lame writes its last argument.
		writeFileSync(cmd === 'lame' ? args.at(-1) : args[args.indexOf('--output_file') + 1], Buffer.alloc(2048, cmd === 'lame' ? 7 : 1));
	};
	const tts = new Tts({ settings, audioDir, runImpl });
	const pipeline = new Pipeline({ db, settings, gmail, extractor, tts, audioDir, log: quiet });
	const updater = new Updater({ config, settings, fetchImpl: async (url, init) => { calls.github.push({ url: String(url), headers: init.headers }); return github(); } });
	const server = createServer({ config, db, settings, gmail, pipeline, tts, updater, audioDir, log: quiet });
	await new Promise((r) => server.listen(0, '127.0.0.1', r));
	const base = `http://127.0.0.1:${server.address().port}`;
	t.after(() => { server.close(); server.closeAllConnections(); db.close(); rmSync(dir, { recursive: true, force: true }); });

	let cookie = '';
	const call = async (p, { body, token, method } = {}) => {
		const headers = {};
		if (cookie) headers.cookie = cookie;
		if (token) headers.authorization = `Bearer ${token}`;
		if (body !== undefined) headers['content-type'] = 'application/json';
		const res = await fetch(base + p, { method: method || (body !== undefined ? 'POST' : 'GET'), headers, body: body !== undefined ? JSON.stringify(body) : undefined, redirect: 'manual' });
		const set = res.headers.get('set-cookie');
		if (set) cookie = set.split(';')[0];
		return res;
	};
	return { call, calls, settings, db, pipeline, gmail, config, dir, audioDir, dropCookie: () => (cookie = '') };
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
	let state = await (await call('/admin/api/settings', { body: { anthropic_api_key: 'sk-secret', gmail_query: ' from:x ', poll_minutes: 'abc', tts_provider: 'bogus', app_token: 'hijack', google_refresh_token: 'hijack' } })).json();
	assert.equal(state.settings.anthropic_api_key_set, true);
	assert.equal(state.settings.gmail_query, 'from:x');
	assert.equal(state.settings.poll_minutes, '15');
	assert.equal(state.settings.tts_provider, 'piper');
	assert.ok(!JSON.stringify(state).includes('sk-secret'));
	assert.notEqual(state.app_token, 'hijack');
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
	// A second run does nothing new.
	assert.equal((await pipeline.runOnce()).processed, 0);
	assert.equal(calls.claude.length, 2);

	// The admin page's data never carries markup as markup (it is rendered with textContent).
	const admin = await (await call('/admin/api/state')).json();
	assert.equal(admin.messages.find((m) => m.gmail_id === 'm1').subject, 'Daily <b>bread</b>');
	assert.equal(admin.messages.find((m) => m.gmail_id === 'm2').status, 'skipped');
	const token = admin.app_token;

	// The app's API uses the token, not the admin session.
	dropCookie();
	assert.equal((await call('/api/entries')).status, 401);
	assert.equal((await call('/api/entries', { token: 'wrong' })).status, 401);
	assert.deepEqual(await (await call('/api/health')).json(), { ok: true, version: '1.0.0' });
	const list = await (await call('/api/entries', { token })).json();
	assert.equal(list.entries.length, 1);
	const entry = list.entries[0];
	assert.deepEqual({ ...entry, id: 0, created_at: '', updated_at: '', audio_sha256: '' }, {
		id: 0, title: 'Daily Bread', entry_date: '2025-11-15', scripture: 'Matthew 6:11', summary: 'On provision.', body: 'Give us this day.',
		has_audio: true, audio_bytes: 2048, audio_sha256: '', source_from: 'daily@example.org', source_subject: 'Daily <b>bread</b>', created_at: '', updated_at: '', deleted: false,
	});
	const audio = await call(`/api/entries/${entry.id}/audio`, { token });
	assert.equal(audio.headers.get('content-type'), 'audio/mpeg');
	const bytes = Buffer.from(await audio.arrayBuffer());
	assert.equal(createHash('sha256').update(bytes).digest('hex'), entry.audio_sha256);
	assert.equal((await call('/api/entries/999/audio', { token })).status, 404);

	// Incremental sync: nothing new, then a deletion shows up as a tombstone.
	assert.equal((await (await call(`/api/entries?since=${encodeURIComponent(list.server_time)}`, { token })).json()).entries.length, 0);
	assert.equal((await call('/api/entries?since=yesterday', { token })).status, 400);
	await new Promise((r) => setTimeout(r, 5));
	await pipeline.deleteEntry(entry.id);
	const after = await (await call(`/api/entries?since=${encodeURIComponent(list.server_time)}`, { token })).json();
	assert.deepEqual(after.entries.map((e) => [e.id, e.deleted, e.has_audio, e.body]), [[entry.id, true, false, '']]);
	assert.equal((await (await call('/api/entries', { token })).json()).entries.length, 0);
	assert.equal((await call(`/api/entries/${entry.id}/audio`, { token })).status, 404);
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
