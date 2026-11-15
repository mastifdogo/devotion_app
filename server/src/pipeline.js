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
			const insert = this.db.prepare(`INSERT INTO entries (gmail_id, position, kind, lesson_number, title, entry_date, scripture, summary, body, refs, source_from, source_subject, received_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
			this.db.exec('BEGIN');
			try {
				// A retry after a half-finished attempt must not leave duplicates.
				this.db.prepare('UPDATE entries SET deleted_at = ?, updated_at = ? WHERE gmail_id = ? AND deleted_at IS NULL').run(now(), now(), id);
				entries.forEach((e, i) => {
					const t = now();
					insert.run(id, i, e.kind || 'daily_reading', e.lesson_number ?? null, e.title, e.entry_date || (message.date ? message.date.slice(0, 10) : null), e.scripture, e.summary, e.body, JSON.stringify(e.references || []), message.from, message.subject, message.date, t, t);
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

	/** Gives audio from before voices were tracked the voice it was made in. */
	adoptLegacyAudio() {
		const voice = this.tts.defaultVoiceId();
		if (!voice) return;
		this.db.prepare("UPDATE OR IGNORE entry_audio SET voice = ? WHERE voice = ''").run(voice);
	}

	/**
	 * Makes the audio each live entry is missing, in every voice on the server.
	 * Only one of these runs at a time; a call while one runs makes it go round
	 * again, so a voice added meanwhile is not missed. Returns how many files were made.
	 */
	async fillAudio({ retryFailed = false } = {}) {
		if (this.audioBusy) {
			this.audioAgain = true;
			this.audioRetry ||= retryFailed;
			return 0;
		}
		this.audioBusy = true;
		let made = 0;
		try {
			do {
				this.audioAgain = false;
				const retry = retryFailed || this.audioRetry;
				this.audioRetry = false;
				made += await this.fillAudioOnce(retry);
			} while (this.audioAgain);
		} finally {
			this.audioBusy = false;
		}
		return made;
	}

	async fillAudioOnce(retryFailed) {
		if (this.tts.notReady()) return 0;
		this.adoptLegacyAudio();
		const voices = this.tts.voiceList();
		const entries = this.db.prepare('SELECT * FROM entries WHERE deleted_at IS NULL AND archived_at IS NULL ORDER BY id').all();
		const have = this.db.prepare('SELECT file, error FROM entry_audio WHERE entry_id = ? AND voice = ?');
		let made = 0;
		// Voice by voice, so every entry is listenable in the default voice first.
		const order = [...voices].sort((x, y) => (y.id === this.tts.defaultVoiceId()) - (x.id === this.tts.defaultVoiceId()));
		for (const voice of order) {
			for (const entry of entries) {
				const row = have.get(entry.id, voice.id);
				if (row?.file || (row?.error && !retryFailed)) continue;
				if (await this.makeAudio(entry, voice.id)) made++;
			}
		}
		return made;
	}

	/** Makes (or remakes) one entry's audio in one voice. */
	async makeAudio(entry, voiceId = this.tts.defaultVoiceId()) {
		const old = this.db.prepare('SELECT file FROM entry_audio WHERE entry_id = ? AND voice = ?').get(entry.id, voiceId);
		try {
			// A new file name each time, so a cached copy is never mistaken for the new audio.
			const safeVoice = voiceId.replace(/[^A-Za-z0-9_-]/g, '_');
			const audio = await this.tts.synthesize(`${entry.id}-${safeVoice}-${Date.now().toString(36)}`, speechText(entry), voiceId);
			// The entry may have been archived or deleted while this ran.
			const live = this.db.prepare('SELECT 1 FROM entries WHERE id = ? AND deleted_at IS NULL AND archived_at IS NULL').get(entry.id);
			if (!live) {
				await rm(path.join(this.audioDir, audio.file), { force: true });
				return false;
			}
			this.db.prepare(`INSERT INTO entry_audio (entry_id, voice, file, bytes, sha256, error, created_at) VALUES (?, ?, ?, ?, ?, NULL, ?)
				ON CONFLICT(entry_id, voice) DO UPDATE SET file = excluded.file, bytes = excluded.bytes, sha256 = excluded.sha256, error = NULL, created_at = excluded.created_at`)
				.run(entry.id, voiceId, audio.file, audio.bytes, audio.sha256, now());
			if (old?.file) await rm(path.join(this.audioDir, old.file), { force: true });
			this.touch(entry.id);
			return true;
		} catch (err) {
			this.log.error(`audio for entry ${entry.id} (${voiceId}): ${err.message}`);
			this.db.prepare(`INSERT INTO entry_audio (entry_id, voice, file, bytes, sha256, error, created_at) VALUES (?, ?, NULL, NULL, NULL, ?, ?)
				ON CONFLICT(entry_id, voice) DO UPDATE SET error = excluded.error`).run(entry.id, voiceId, err.message, now());
			return false;
		}
	}

	/** Remakes one entry's audio in every voice (after a change of pace, say). */
	async remakeAudio(entry) {
		let made = 0;
		for (const voice of this.tts.voiceList()) if (await this.makeAudio(entry, voice.id)) made++;
		return made;
	}

	/** Drops a voice's audio from every entry, after the voice was removed. */
	async removeVoiceAudio(voiceId) {
		const rows = this.db.prepare('SELECT entry_id, file FROM entry_audio WHERE voice = ?').all(voiceId);
		for (const row of rows) if (row.file) await rm(path.join(this.audioDir, row.file), { force: true });
		this.db.prepare('DELETE FROM entry_audio WHERE voice = ?').run(voiceId);
		for (const row of rows) this.touch(row.entry_id);
		return rows.length;
	}

	/** Removes all of an entry's audio files and records. */
	async dropAudio(entryId) {
		for (const row of this.db.prepare('SELECT file FROM entry_audio WHERE entry_id = ?').all(entryId)) {
			if (row.file) await rm(path.join(this.audioDir, row.file), { force: true });
		}
		this.db.prepare('DELETE FROM entry_audio WHERE entry_id = ?').run(entryId);
	}

	/** Marks an entry changed, so the app's next sync picks it up. */
	touch(entryId) {
		this.db.prepare('UPDATE entries SET updated_at = ? WHERE id = ?').run(now(), entryId);
	}

	/** When the default voice changes, older apps (which only know one audio file) re-download. */
	touchAll() {
		this.db.prepare('UPDATE entries SET updated_at = ? WHERE deleted_at IS NULL').run(now());
	}

	async deleteEntry(id) {
		const entry = this.db.prepare('SELECT * FROM entries WHERE id = ? AND deleted_at IS NULL').get(id);
		if (!entry) return false;
		await this.dropAudio(id);
		// Kept as a tombstone so the app removes its cached copy on the next sync.
		this.db.prepare("UPDATE entries SET deleted_at = ?, updated_at = ?, body = '', refs = NULL WHERE id = ?").run(now(), now(), id);
		return true;
	}

	/** Forgets an email so the next run reads it again. */
	retryMessage(gmailId) {
		return this.db.prepare('DELETE FROM messages WHERE gmail_id = ?').run(gmailId).changes > 0;
	}
}
