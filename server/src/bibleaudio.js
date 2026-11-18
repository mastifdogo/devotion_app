// The Bible read aloud: one MP3 per chapter, version and voice, made the first
// time someone asks to listen and kept on the server after that. A whole
// Bible is over a thousand chapters, so nothing is made ahead of time.
import { createHash } from 'node:crypto';
import { readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';

export class BibleAudioError extends Error {
	constructor(message, status = 400) {
		super(message);
		this.name = 'BibleAudioError';
		this.status = status;
	}
}

/**
 * What the voice reads for a chapter, verse by verse: [{ verse, text }], empty
 * verses left out. Each verse is announced: "John, chapter 1, verse 1." at the
 * start of a book, "Chapter 3, verse 1." at the start of a chapter, and
 * "Verse 16." before the others.
 */
export function chapterParts(bookName, chapter, verses) {
	const psalm = /^Psalms?$/.test(bookName);
	const single = /^(Obadiah|Philemon|2 John|3 John|Jude)$/.test(bookName);
	const parts = [];
	let first = true;
	verses.forEach((text, i) => {
		if (!text) return;
		const n = i + 1;
		const where = psalm ? `Psalm ${chapter}` : `Chapter ${chapter}`;
		// The first verse read announces the chapter (and the book, in its first chapter), whatever its number.
		const intro = !first ? `Verse ${n}.` : single ? `${bookName}, verse ${n}.` : chapter === 1 ? `${bookName}, ${where.replace(/^C/, 'c')}, verse ${n}.` : `${where}, verse ${n}.`;
		first = false;
		parts.push({ verse: n, text: `${intro} ${text}` });
	});
	return parts;
}

export class BibleAudio {
	/**
	 * appVersion: the version the app carries with it. People read it there without this server
	 * having it, so the server fetches it by itself the first time someone listens to it.
	 */
	constructor({ bibles, tts, settings, dataDir, log = console, appVersion = 'kjv' }) {
		Object.assign(this, { bibles, tts, settings, log, appVersion });
		this.dir = path.join(dataDir, 'bible-audio');
		this.making = new Map(); // file name -> promise
		this.failed = new Map(); // file name -> message, until asked again
		this.loaded = null; // the version read last, kept in memory: chapters are asked for one after another
	}

	enabled() {
		return this.tts.bibleVoiceId() !== null || this.premium() !== null;
	}

	/** The speech API voice a person can ask for instead of the server's own: { id, name }, or null. */
	premium() {
		return this.tts.biblePremiumVoice?.() ?? null;
	}

	/** Starts downloading the version the app carries; the chapter is "being made" meanwhile. */
	fetchVersion(id) {
		const key = `version:${id}`;
		const error = this.failed.get(key);
		if (error) {
			this.failed.delete(key);
			throw new BibleAudioError(error, 502);
		}
		if (!this.making.has(key)) {
			const job = this.bibles.install(id)
				.then(() => this.log.info?.(`bible audio: added the ${id} version to read it aloud`))
				.catch((err) => {
					this.log.error(`bible audio: getting the ${id} version: ${err.message}`);
					this.failed.set(key, `The server could not get this Bible version to read it aloud: ${err.message}`);
				})
				.finally(() => this.making.delete(key));
			this.making.set(key, job);
		}
		return { state: 'making' };
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
	 * The chapter's audio: { state: 'ready', file, bytes, etag, info }, or
	 * { state: 'making' } after starting it. info says when each verse is heard:
	 * { duration, exact, verses: [{ verse, start, end }] } in seconds. Throws when
	 * it is off, when there is no such chapter, or with the reason the last attempt failed (once).
	 * premium: read by the speech API's voice, which only a person asking for it in the app gets.
	 */
	async get(versionId, book, chapter, { premium = false } = {}) {
		const voice = premium ? this.premium()?.id ?? null : this.tts.bibleVoiceId();
		if (!voice) throw new BibleAudioError(premium ? 'The speech service does not read the Bible on this server.' : 'Listening to the Bible is not switched on for this server.', 404);
		if (versionId === this.appVersion && !this.bibles.get(versionId) && this.bibles.catalog.some((c) => c.id === versionId)) return this.fetchVersion(versionId);
		const bible = await this.version(versionId);
		const verses = bible.books[book]?.chapters[chapter - 1];
		if (!verses) throw new BibleAudioError('No such chapter.', 404);
		const parts = chapterParts(bible.books[book].name, chapter, verses);
		if (!parts.length) throw new BibleAudioError('This chapter has no text.', 404);
		// The name changes with the text, the voice and the pace, so a change of any makes new audio.
		const tag = createHash('sha256').update(`v2\n${voice}\n${this.settings.get('tts_rate')}\n${parts.map((p) => p.text).join('\n')}`).digest('hex').slice(0, 12);
		const name = `${versionId}-${book}-${chapter}-${voice.replace(/[^A-Za-z0-9_-]/g, '_')}-${tag}`;
		const file = path.join(this.dir, name + '.mp3');
		const info = await stat(file).catch(() => null);
		const timing = info && (await readFile(path.join(this.dir, name + '.json'), 'utf8').then(JSON.parse).catch(() => null));
		if (info && timing) return { state: 'ready', file, bytes: info.size, etag: tag, info: timing };
		const error = this.failed.get(name);
		if (error) {
			this.failed.delete(name);
			throw new BibleAudioError(error, 502);
		}
		if (!this.making.has(name)) {
			const job = this.tts.synthesizeParts(name, parts.map((p) => p.text), voice, this.dir)
				.then(async (made) => {
					const round = (x) => Math.round(x * 1000) / 1000;
					const verses = parts.map((p, i) => ({ verse: p.verse, start: round(made.times[i].start), end: round(made.times[i].end) }));
					// Written last: the chapter is ready once this exists.
					await writeFile(path.join(this.dir, name + '.json'), JSON.stringify({ duration: round(made.duration), exact: made.exact, verses }));
					this.log.info?.(`bible audio: ${name}`);
				})
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
			if (name.endsWith('.json')) { await rm(path.join(this.dir, name), { force: true }); continue; }
			if (!name.endsWith('.mp3') || this.making.has(name.slice(0, -4))) continue;
			await rm(path.join(this.dir, name), { force: true });
			removed++;
		}
		return removed;
	}

	/** Drops one version's audio, when the version is removed from the server. */
	async removeVersion(id) {
		for (const name of await readdir(this.dir).catch(() => [])) {
			if (name.startsWith(`${id}-`) && /\.(mp3|json)$/.test(name)) await rm(path.join(this.dir, name), { force: true });
		}
	}
}
