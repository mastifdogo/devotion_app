// The loop that does the work: find new emails, extract entries, make audio.
import { rm } from 'node:fs/promises';
import path from 'node:path';
import { now } from './db.js';
import { speechText } from './tts.js';

const MAX_EMAIL_CHARS = 600_000; // far beyond any real devotion; refuse rather than truncate
const MAX_ATTEMPTS = 5;

/** The admin's keywords: one per line (commas also separate), without duplicates. */
export function parseKeywords(raw) {
	const seen = new Set();
	const out = [];
	for (const piece of String(raw || '').split(/[\n,]+/)) {
		// Quotes and braces would change the meaning of the Gmail search.
		const word = piece.replace(/["{}()]/g, ' ').replace(/\s+/g, ' ').trim();
		if (!word || seen.has(word.toLowerCase())) continue;
		seen.add(word.toLowerCase());
		out.push(word);
	}
	return out;
}

/** A Gmail search for mail holding any of the keywords, narrowed by the optional extra filter. */
export function buildQuery(keywords, filter) {
	const any = `{${keywords.map((k) => `"${k}"`).join(' ')}}`;
	return [String(filter || '').trim(), any].filter(Boolean).join(' ');
}

/** The first keyword that really appears in the email (subject, sender or text), or null. */
export function matchKeyword(message, keywords) {
	const hay = `${message.subject || ''}\n${message.from || ''}\n${message.text || ''}`.toLowerCase().replace(/\s+/g, ' ');
	return keywords.find((k) => hay.includes(k.toLowerCase())) ?? null;
}

export class Pipeline {
	constructor({ db, settings, gmail, extractor, tts, audioDir, archiver = null, log = console }) {
		Object.assign(this, { db, settings, gmail, extractor, tts, audioDir, archiver, log });
		this.running = false;
		this.timer = null;
		this.status = { last_run: null, last_error: null, last_result: null, running: false };
	}

	start() {
		const tick = async () => {
			await this.runOnce().catch(() => {});
			const minutes = Math.max(1, Number(this.settings.get('poll_minutes')) || 15);
			this.timer = setTimeout(tick, minutes * 60 * 1000);
			this.timer.unref?.();
		};
		this.timer = setTimeout(tick, 5000);
		this.timer.unref?.();
	}

	stop() {
		clearTimeout(this.timer);
	}

	/** Why a run can't do anything yet, or null. Shown in the admin page. */
	notReady() {
		if (!this.gmail.connected()) return 'Gmail is not connected.';
		if (!parseKeywords(this.settings.get('gmail_keywords')).length) return 'No keywords are set, so the server does not know which emails to import.';
		if (!this.extractor.configured()) return 'No Anthropic API key is set.';
		return null;
	}

	async runOnce() {
		if (this.running) return { skipped: 'already running' };
		const blocked = this.notReady();
		if (blocked) {
			this.status = { ...this.status, last_run: now(), last_error: blocked, running: false };
			return { skipped: blocked };
		}
		this.running = true;
		this.status.running = true;
		const result = { found: 0, processed: 0, entries: 0, errors: 0, audio: 0 };
		try {
			const keywords = parseKeywords(this.settings.get('gmail_keywords'));
			const ids = await this.gmail.listMessageIds(buildQuery(keywords, this.settings.get('gmail_query')));
			result.found = ids.length;
			const seen = this.db.prepare('SELECT status, attempts FROM messages WHERE gmail_id = ?');
			// Oldest first, so entries are created in the order the emails arrived.
			for (const id of ids.reverse()) {
				const row = seen.get(id);
				if (row && (row.status !== 'error' || row.attempts >= MAX_ATTEMPTS)) continue;
				const ok = await this.processMessage(id, row?.attempts || 0, keywords);
				result.processed++;
				if (!ok) result.errors++;
			}
			result.audio = await this.fillAudio();
			// Last, and never a reason for the mail run to fail: an unreachable
			// share is reported in the archive's own status.
			if (this.archiver) await this.archiver.runOnce().catch(() => {});
			result.entries = this.db.prepare('SELECT COUNT(*) AS n FROM entries WHERE deleted_at IS NULL').get().n;
			this.status = { last_run: now(), last_error: null, last_result: result, running: false };
			return result;
		} catch (err) {
			this.log.error('run failed:', err.message);
			this.status = { last_run: now(), last_error: err.message, last_result: result, running: false };
			throw err;
		} finally {
			this.running = false;
			this.status.running = false;
		}
	}

	async processMessage(id, attempts, keywords) {
		const record = this.db.prepare(`INSERT INTO messages (gmail_id, subject, sender, received_at, status, error, attempts, processed_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(gmail_id) DO UPDATE SET subject = excluded.subject, sender = excluded.sender, received_at = excluded.received_at,
				status = excluded.status, error = excluded.error, attempts = excluded.attempts, processed_at = excluded.processed_at`);
		let message = { subject: null, from: null, date: null };
		try {
			message = await this.gmail.getMessage(id);
			if (!message.text) {
				record.run(id, message.subject, message.from, message.date, 'skipped', 'The email has no text.', attempts + 1, now());
				return true;
			}
			// Gmail's search is loose (it matches word variants and attachments), so
			// the keyword is checked again here before the email is imported.
			if (!matchKeyword(message, keywords)) {
				record.run(id, message.subject, message.from, message.date, 'skipped', 'None of the keywords appear in this email.', attempts + 1, now());
				return true;
			}
			if (message.text.length > MAX_EMAIL_CHARS) throw Object.assign(new Error(`The email is too long (${message.text.length} characters).`), { retryable: false });
			const entries = await this.extractor.extract(message);
			const insert = this.db.prepare(`INSERT INTO entries (gmail_id, position, title, entry_date, scripture, summary, body, source_from, source_subject, received_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
			this.db.exec('BEGIN');
			try {
				// A retry after a half-finished attempt must not leave duplicates.
				this.db.prepare('UPDATE entries SET deleted_at = ?, updated_at = ? WHERE gmail_id = ? AND deleted_at IS NULL').run(now(), now(), id);
				entries.forEach((e, i) => {
					const t = now();
					insert.run(id, i, e.title, e.entry_date || (message.date ? message.date.slice(0, 10) : null), e.scripture, e.summary, e.body, message.from, message.subject, message.date, t, t);
				});
				record.run(id, message.subject, message.from, message.date, entries.length ? 'done' : 'skipped', entries.length ? null : 'No devotion found in this email.', attempts + 1, now());
				this.db.exec('COMMIT');
			} catch (err) {
				this.db.exec('ROLLBACK');
				throw err;
			}
			this.log.info(`email ${id}: ${entries.length} entr${entries.length === 1 ? 'y' : 'ies'}`);
			return true;
		} catch (err) {
			if (err.needsReconnect) throw err; // nothing else will work either; stop the run
			// A failure that retrying can't fix uses up the attempts at once.
			const nextAttempts = err.retryable === false ? MAX_ATTEMPTS : attempts + 1;
			record.run(id, message.subject, message.from, message.date, 'error', err.message, nextAttempts, now());
			this.log.error(`email ${id}: ${err.message}`);
			return false;
		}
	}

	/** Makes audio for entries that have none yet. Returns how many were made. */
	async fillAudio({ retryFailed = false } = {}) {
		if (this.tts.notReady()) return 0;
		const rows = this.db.prepare(`SELECT * FROM entries WHERE deleted_at IS NULL AND archived_at IS NULL AND audio_file IS NULL ${retryFailed ? '' : 'AND audio_error IS NULL'} ORDER BY id`).all();
		let made = 0;
		for (const entry of rows) if (await this.makeAudio(entry)) made++;
		return made;
	}

	async makeAudio(entry) {
		try {
			// A new file name each time, so a cached copy is never mistaken for the new audio.
			const audio = await this.tts.synthesize(`${entry.id}-${Date.now().toString(36)}`, speechText(entry));
			if (entry.audio_file) await rm(path.join(this.audioDir, entry.audio_file), { force: true });
			this.db.prepare('UPDATE entries SET audio_file = ?, audio_bytes = ?, audio_sha256 = ?, audio_error = NULL, updated_at = ? WHERE id = ?').run(audio.file, audio.bytes, audio.sha256, now(), entry.id);
			return true;
		} catch (err) {
			this.log.error(`audio for entry ${entry.id}: ${err.message}`);
			this.db.prepare('UPDATE entries SET audio_error = ?, updated_at = ? WHERE id = ?').run(err.message, now(), entry.id);
			return false;
		}
	}

	async deleteEntry(id) {
		const entry = this.db.prepare('SELECT * FROM entries WHERE id = ? AND deleted_at IS NULL').get(id);
		if (!entry) return false;
		if (entry.audio_file) await rm(path.join(this.audioDir, entry.audio_file), { force: true });
		// Kept as a tombstone so the app removes its cached copy on the next sync.
		this.db.prepare("UPDATE entries SET deleted_at = ?, updated_at = ?, audio_file = NULL, audio_bytes = NULL, audio_sha256 = NULL, body = '' WHERE id = ?").run(now(), now(), id);
		return true;
	}

	/** Forgets an email so the next run reads it again. */
	retryMessage(gmailId) {
		return this.db.prepare('DELETE FROM messages WHERE gmail_id = ?').run(gmailId).changes > 0;
	}
}
