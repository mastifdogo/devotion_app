// Gmail over plain HTTPS: the OAuth 2.0 authorization-code flow and the three
// read-only calls the server needs. Scope is gmail.readonly; nothing is ever
// modified in the mailbox.
import { randomBytes } from 'node:crypto';
import { htmlToText } from './html.js';

const AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const API = 'https://gmail.googleapis.com/gmail/v1/users/me';
export const SCOPE = 'https://www.googleapis.com/auth/gmail.readonly';

export class GmailError extends Error {
	constructor(message, { status, needsReconnect = false } = {}) {
		super(message);
		this.name = 'GmailError';
		this.status = status;
		this.needsReconnect = needsReconnect;
	}
}

/**
 * Google only redirects to https addresses or to localhost. With a public
 * https address the callback lands on the server directly; otherwise the
 * browser is sent to localhost, shows an error page, and the admin pastes
 * that address back into the admin page.
 */
export function redirectUri(publicUrl, port) {
	const base = String(publicUrl || '').trim().replace(/\/+$/, '');
	if (/^https:\/\//i.test(base) || /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/i.test(base)) return base + '/oauth/callback';
	return `http://localhost:${port}/oauth/callback`;
}

export class Gmail {
	constructor({ settings, port, fetchImpl = fetch }) {
		this.settings = settings;
		this.port = port;
		this.fetch = fetchImpl;
		this.states = new Map(); // state -> expiry (ms)
		this.access = null; // { token, expires }
	}

	redirectUri() {
		return redirectUri(this.settings.get('public_url'), this.port);
	}

	configured() {
		return this.settings.get('google_client_id') !== '' && this.settings.get('google_client_secret') !== '';
	}

	connected() {
		return this.settings.get('google_refresh_token') !== '';
	}

	startAuth() {
		if (!this.configured()) throw new GmailError('Save the Google client ID and client secret first.');
		const state = randomBytes(18).toString('base64url');
		const nowMs = Date.now();
		for (const [s, exp] of this.states) if (exp < nowMs) this.states.delete(s);
		this.states.set(state, nowMs + 10 * 60 * 1000);
		const q = new URLSearchParams({
			client_id: this.settings.get('google_client_id'),
			redirect_uri: this.redirectUri(),
			response_type: 'code',
			scope: SCOPE,
			access_type: 'offline', // a refresh token, so the server keeps working unattended
			prompt: 'consent', // Google only returns a refresh token when consent is shown
			state,
		});
		return `${AUTH_URL}?${q}`;
	}

	/** Completes the flow from the callback's query (or from a pasted callback address). */
	async finishAuth(params) {
		const state = params.get('state') || '';
		const expiry = this.states.get(state);
		this.states.delete(state);
		if (!expiry || expiry < Date.now()) throw new GmailError('This sign-in link has expired or was already used. Press "Connect Gmail" again.');
		if (params.get('error')) throw new GmailError(`Google did not grant access: ${params.get('error')}`);
		const code = params.get('code');
		if (!code) throw new GmailError('The address has no authorization code in it.');
		const tokens = await this.tokenRequest({ grant_type: 'authorization_code', code, redirect_uri: this.redirectUri() });
		if (!tokens.refresh_token) throw new GmailError('Google returned no refresh token. Remove this app under myaccount.google.com/permissions and connect again.');
		this.settings.set('google_refresh_token', tokens.refresh_token);
		this.access = { token: tokens.access_token, expires: Date.now() + (tokens.expires_in - 60) * 1000 };
		const profile = await this.api('/profile');
		this.settings.set('google_email', profile.emailAddress || '');
		return profile.emailAddress || '';
	}

	disconnect() {
		this.settings.set('google_refresh_token', '');
		this.settings.set('google_email', '');
		this.access = null;
	}

	async tokenRequest(fields) {
		const body = new URLSearchParams({
			client_id: this.settings.get('google_client_id'),
			client_secret: this.settings.get('google_client_secret'),
			...fields,
		});
		const res = await this.fetch(TOKEN_URL, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body });
		const json = await res.json().catch(() => ({}));
		if (!res.ok) {
			const detail = json.error_description || json.error || `HTTP ${res.status}`;
			// invalid_grant: the refresh token was revoked or expired (a Google
			// project left in "Testing" expires them after 7 days).
			const expired = json.error === 'invalid_grant' && fields.grant_type === 'refresh_token';
			throw new GmailError(expired ? 'The Gmail connection has expired or was revoked. Press "Reconnect Gmail".' : `Google sign-in failed: ${detail}`, { status: res.status, needsReconnect: json.error === 'invalid_grant' });
		}
		return json;
	}

	async accessToken() {
		if (this.access && this.access.expires > Date.now()) return this.access.token;
		const refresh = this.settings.get('google_refresh_token');
		if (!refresh) throw new GmailError('Gmail is not connected.', { needsReconnect: true });
		const tokens = await this.tokenRequest({ grant_type: 'refresh_token', refresh_token: refresh });
		this.access = { token: tokens.access_token, expires: Date.now() + (tokens.expires_in - 60) * 1000 };
		return this.access.token;
	}

	async api(pathAndQuery) {
		const token = await this.accessToken();
		const res = await this.fetch(API + pathAndQuery, { headers: { authorization: `Bearer ${token}` } });
		const json = await res.json().catch(() => ({}));
		if (!res.ok) throw new GmailError(`Gmail answered ${res.status}: ${json.error?.message || 'request failed'}`, { status: res.status, needsReconnect: res.status === 401 });
		return json;
	}

	/** Ids of messages matching the query, newest first (Gmail's order). */
	async listMessageIds(query, max = 50) {
		const q = new URLSearchParams({ q: query, maxResults: String(max) });
		const json = await this.api(`/messages?${q}`);
		return (json.messages || []).map((m) => m.id);
	}

	async getMessage(id) {
		return parseMessage(await this.api(`/messages/${encodeURIComponent(id)}?format=full`));
	}
}

const b64url = (data) => Buffer.from(String(data || ''), 'base64url').toString('utf8');

/** Reduces Gmail's nested MIME structure to what the extraction step needs. */
export function parseMessage(msg) {
	const headers = {};
	for (const h of msg.payload?.headers || []) headers[h.name.toLowerCase()] = h.value;
	let plain = '';
	let html = '';
	const walk = (part) => {
		if (!part) return;
		const type = (part.mimeType || '').toLowerCase();
		const isAttachment = Boolean(part.filename);
		if (!isAttachment && part.body?.data) {
			if (type === 'text/plain') plain += b64url(part.body.data) + '\n';
			else if (type === 'text/html') html += b64url(part.body.data) + '\n';
		}
		for (const child of part.parts || []) walk(child);
	};
	walk(msg.payload);
	const fromHtml = html ? htmlToText(html) : '';
	const fromPlain = plain.replace(/\r/g, '').trim();
	// Newsletters often ship a stub text part ("view this email in a browser");
	// take whichever version actually holds the content.
	const text = fromHtml.length > fromPlain.length * 1.2 ? fromHtml : fromPlain || fromHtml;
	const ms = Number(msg.internalDate);
	return {
		id: msg.id,
		subject: headers.subject || '',
		from: headers.from || '',
		date: Number.isFinite(ms) && ms > 0 ? new Date(ms).toISOString() : null,
		text,
	};
}
