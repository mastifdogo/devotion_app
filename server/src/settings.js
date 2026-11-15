// Runtime settings: a value saved in the admin page wins, then an environment
// variable of the same name in capitals, then the default.
export const DEFAULTS = {
	public_url: '',
	google_client_id: '',
	google_client_secret: '',
	google_refresh_token: '',
	google_email: '',
	gmail_keywords: '',
	gmail_query: '',
	poll_minutes: '15',
	anthropic_api_key: '',
	claude_model: 'claude-opus-5-5',
	extract_instructions: '',
	tts_provider: 'piper', // piper | openai | none
	tts_rate: 'normal', // slow | normal | fast
	piper_bin: 'piper',
	piper_model: '', // the voice installed with the server
	piper_voice: '', // a voice downloaded from the catalogue; empty = the installed one
	tts_openai_base_url: 'https://api.openai.com/v1',
	tts_openai_key: '',
	tts_openai_model: 'gpt-4o-mini-tts',
	tts_openai_voice: 'alloy',
	github_token: '',
	// Entries older than this many days are moved to the SMB share.
	archive_days: '30',
	smb_share: '', // //server/share
	smb_folder: 'Devotion',
	smb_username: '',
	smb_password: '',
	smb_domain: '',
};

// Never sent back to the browser; the admin page only learns whether they are set.
export const SECRET_KEYS = new Set(['google_client_secret', 'google_refresh_token', 'anthropic_api_key', 'tts_openai_key', 'github_token', 'smb_password']);
// Managed by the server itself, not by the settings form.
const INTERNAL_KEYS = new Set(['google_refresh_token', 'google_email', 'piper_voice']);

export class Settings {
	constructor(db, env = process.env) {
		this.db = db;
		this.env = env;
		this.getStmt = db.prepare('SELECT value FROM settings WHERE key = ?');
		this.setStmt = db.prepare('INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value');
		this.delStmt = db.prepare('DELETE FROM settings WHERE key = ?');
	}

	get(key) {
		const row = this.getStmt.get(key);
		if (row) return row.value;
		const fromEnv = this.env[key.toUpperCase()];
		if (fromEnv !== undefined && fromEnv !== '') return fromEnv;
		return DEFAULTS[key] ?? '';
	}

	set(key, value) {
		if (value === null || value === undefined || value === '') this.delStmt.run(key);
		else this.setStmt.run(key, String(value));
	}

	/** What the admin page shows: plain values, and only "is it set" for secrets. */
	publicView() {
		const out = {};
		for (const key of Object.keys(DEFAULTS)) {
			if (SECRET_KEYS.has(key)) out[key + '_set'] = this.get(key) !== '';
			else out[key] = this.get(key);
		}
		return out;
	}

	/** Applies a settings form. A secret left empty keeps its current value. */
	applyForm(form) {
		for (const [key, value] of Object.entries(form)) {
			if (!(key in DEFAULTS) || INTERNAL_KEYS.has(key)) continue;
			if (typeof value !== 'string') continue;
			const v = value.trim();
			if (SECRET_KEYS.has(key) && v === '') continue;
			if (key === 'poll_minutes' && !/^\d{1,4}$/.test(v)) continue;
			if (key === 'tts_provider' && !['piper', 'openai', 'none'].includes(v)) continue;
			if (key === 'tts_rate' && !['slow', 'normal', 'fast'].includes(v)) continue;
			if (key === 'archive_days' && !/^[1-9]\d{0,3}$/.test(v)) continue;
			// A share is //server/share; anything else would be handed to smbclient as options.
			if (key === 'smb_share' && v !== '' && !/^\/\/[A-Za-z0-9._-]+\/[^\/\\"';|&<>`$\n]+$/.test(v.replace(/\\/g, '/'))) continue;
			if (key === 'smb_share') { this.set(key, v.replace(/\\/g, '/')); continue; }
			this.set(key, v);
		}
	}
}
