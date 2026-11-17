// The Bible read aloud: one MP3 per chapter, version and voice, made the first
// time someone asks to listen and kept on the server after that. A whole
// Bible is over a thousand chapters, so nothing is made ahead of time.
import { createHash } from 'node:crypto';
import { readFile, readdir, rm, stat } from 'node:fs/promises';
import path from 'node:path';

export class BibleAudioError extends Error {
	constructor(message, status = 400) {
		super(message);
		this.name = 'BibleAudioError';
		this.status = status;
	}
}

/** What the voice reads for a chapter: "John, chapter 3." and then the verses. */
export function chapterText(bookName, chapter, verses) {
	const single = /^(Obadiah|Philemon|2 John|3 John|Jude)$/.test(bookName);
	const head = single ? `${bookName}.` : /^Psalms?$/.test(bookName) ? `Psalm ${chapter}.` : `${bookName}, chapter ${chapter}.`;
	return [head, verses.filter(Boolean).join('\n')].join('\n\n');
}

export class BibleAudio {
	constructor({ bibles, tts, settings, dataDir, log = console }) {
		Object.assign(this, { bibles, tts, settings, log });
		this.dir = path.join(dataDir, 'bible-audio');
		this.making = new Map(); // file name -> promise
		this.failed = new Map(); // file name -> message, until asked again
		this.loaded = null; // the version read last, kept in memory: chapters are asked for one after another
	}

	enabled() {
		return this.tts.bibleVoiceId() !== null;
	}

	async version(id) {
		if (this.loaded?.id === id && this.loaded.sha256 === this.bibles.get(id)?.sha256) return this.loaded.data;
		const row = this.bibles.get(id);
		if (!row) throw new BibleAudioError('This server does not have that Bible version.', 404);
		const data = JSON.parse(await readFile(this.bibles.file(id), 'utf8'));
		this.loaded = { id, sha256: row.sha256, data };
		return data;
	}

	/**
	 * The chapter's audio: { state: 'ready', file, bytes, etag }, or
	 * { state: 'making' } after starting it. Throws when it is off, when there
	 * is no such chapter, or with the reason the last attempt failed (once).
	 */
	async get(versionId, book, chapter) {
		const voice = this.tts.bibleVoiceId();
		if (!voice) throw new BibleAudioError('Listening to the Bible is not switched on for this server.', 404);
		const bible = await this.version(versionId);
		const verses = bible.books[book]?.chapters[chapter - 1];
		if (!verses) throw new BibleAudioError('No such chapter.', 404);
		const text = chapterText(bible.books[book].name, chapter, verses);
		// The name changes with the text, the voice and the pace, so a change of any makes new audio.
		const tag = createHash('sha256').update(`${voice}\n${this.settings.get('tts_rate')}\n${text}`).digest('hex').slice(0, 12);
		const name = `${versionId}-${book}-${chapter}-${voice.replace(/[^A-Za-z0-9_-]/g, '_')}-${tag}`;
		const file = path.join(this.dir, name + '.mp3');
		const info = await stat(file).catch(() => null);
		if (info) return { state: 'ready', file, bytes: info.size, etag: tag };
		const error = this.failed.get(name);
		if (error) {
			this.failed.delete(name);
			throw new BibleAudioError(error, 502);
		}
		if (!this.making.has(name)) {
			const job = this.tts.synthesize(name, text, voice, this.dir)
				.then(() => this.log.info?.(`bible audio: ${name}`))
				.catch((err) => {
					this.log.error(`bible audio ${name}: ${err.message}`);
					this.failed.set(name, err.message);
				})
				.finally(() => this.making.delete(name));
			this.making.set(name, job);
		}
		return { state: 'making' };
	}

	/** What is kept: { files, bytes }. */
	async usage() {
		let files = 0, bytes = 0;
		for (const name of await readdir(this.dir).catch(() => [])) {
			if (!name.endsWith('.mp3')) continue;
			const info = await stat(path.join(this.dir, name)).catch(() => null);
			if (info) { files++; bytes += info.size; }
		}
		return { files, bytes };
	}

	/** Removes all kept audio (it is made again when someone listens). Chapters being made now are left. */
	async clear() {
		let removed = 0;
		for (const name of await readdir(this.dir).catch(() => [])) {
			if (!name.endsWith('.mp3') || this.making.has(name.slice(0, -4))) continue;
			await rm(path.join(this.dir, name), { force: true });
			removed++;
		}
		return removed;
	}

	/** Drops one version's audio, when the version is removed from the server. */
	async removeVersion(id) {
		for (const name of await readdir(this.dir).catch(() => [])) {
			if (name.startsWith(`${id}-`) && name.endsWith('.mp3')) await rm(path.join(this.dir, name), { force: true });
		}
	}
}
