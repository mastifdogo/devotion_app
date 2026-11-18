// Bible versions kept on the server for the app to download. The catalogue
// lists public-domain translations only; each is fetched from a pinned commit
// of github.com/scrollmapper/bible_databases, checked against its SHA-256,
// and stored in the compact form the app reads.
import { createHash } from 'node:crypto';
import { mkdir, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { now } from './db.js';

const SOURCE = 'https://raw.githubusercontent.com/scrollmapper/bible_databases/59b18c5af5845728c275d5b7178ad0d024172b1f/formats/json';

export const CATALOG = [
	{ id: 'kjv', name: 'King James Version', language: 'en', file: 'KJV', sha256: 'f0b09dc49dfb97bb84f03aae1fbf026485048c3cab31a7a41017e2d86ac1d11c' },
	{ id: 'asv', name: 'American Standard Version (1901)', language: 'en', file: 'ASV', sha256: '602445e22c280a682ac4c489117ead179271f5ee50a78ee4531b249c71e7ce99' },
	{ id: 'akjv', name: 'American King James Version', language: 'en', file: 'AKJV', sha256: 'aaa744c56e7ddead9c7e48869e8339f8e604144f89b33605068220643281a3d7' },
	{ id: 'webster', name: 'Webster Bible (1833)', language: 'en', file: 'Webster', sha256: '21d32fca7a463385a5049aca0894318fd60396211be72fd8e8ee9ed76bd6355e' },
	{ id: 'ylt', name: "Young's Literal Translation (1898)", language: 'en', file: 'YLT', sha256: 'd55ef42dae715c4fc5d00049011b818a42a99e0ff419f5f08bc37cea0b4f12e3' },
	{ id: 'bbe', name: 'Bible in Basic English', language: 'en', file: 'BBE', sha256: '2396c027cfd1c47c9bb325c5c1ed1b1c125b95ef4c5382ddba4f8e50651f8068' },
	{ id: 'geneva', name: 'Geneva Bible (1599)', language: 'en', file: 'Geneva1599', sha256: '7a9bb411247d382e6737a95fcdca3087c29da396e55e126d3e36d18c53ab1e6d' },
	{ id: 'rv1909', name: 'Reina-Valera (1909)', language: 'es', file: 'SpaRV', sha256: '94675a8b71785e4468918227b2496f9c7d407be658190e47795bdd5daade953d' },
];

// The usual English names, so every English version names its books the same
// way ("1 Samuel", not "I Samuel"; "Revelation", not "Revelation of John").
export const BOOKS = ['Genesis', 'Exodus', 'Leviticus', 'Numbers', 'Deuteronomy', 'Joshua', 'Judges', 'Ruth', '1 Samuel', '2 Samuel', '1 Kings', '2 Kings', '1 Chronicles', '2 Chronicles', 'Ezra', 'Nehemiah', 'Esther', 'Job', 'Psalms', 'Proverbs', 'Ecclesiastes', 'Song of Solomon', 'Isaiah', 'Jeremiah', 'Lamentations', 'Ezekiel', 'Daniel', 'Hosea', 'Joel', 'Amos', 'Obadiah', 'Jonah', 'Micah', 'Nahum', 'Habakkuk', 'Zephaniah', 'Haggai', 'Zechariah', 'Malachi', 'Matthew', 'Mark', 'Luke', 'John', 'Acts', 'Romans', '1 Corinthians', '2 Corinthians', 'Galatians', 'Ephesians', 'Philippians', 'Colossians', '1 Thessalonians', '2 Thessalonians', '1 Timothy', '2 Timothy', 'Titus', 'Philemon', 'Hebrews', 'James', '1 Peter', '2 Peter', '1 John', '2 John', '3 John', 'Jude', 'Revelation'];

export class BibleError extends Error {
	constructor(message, status = 400) {
		super(message);
		this.name = 'BibleError';
		this.status = status;
	}
}

/** The source's layout to the app's: books → chapters → an array of verse texts. */
export function convert(entry, source) {
	if (!Array.isArray(source?.books) || source.books.length === 0) throw new BibleError('The downloaded Bible is not in the expected format.', 502);
	const english = entry.language === 'en' && source.books.length === BOOKS.length;
	const books = source.books.map((book, i) => ({
		name: english ? BOOKS[i] : String(book.name),
		chapters: [...book.chapters].sort((a, b) => a.chapter - b.chapter).map((chapter) => {
			const verses = [];
			// By verse number, so a gap in the source keeps later verses in place.
			for (const v of chapter.verses) verses[v.verse - 1] = String(v.text).replace(/\s+/g, ' ').trim();
			for (let n = 0; n < verses.length; n++) verses[n] ??= '';
			return verses;
		}),
	}));
	return { id: entry.id, name: entry.name, language: entry.language, books };
}

/** Downloads a catalogue version, checks it against its checksum and returns it in the app's form (JSON, as a Buffer). */
export async function download(entry, fetchImpl = fetch) {
	let res;
	try {
		res = await fetchImpl(`${SOURCE}/${entry.file}.json`);
	} catch (err) {
		throw new BibleError(`Could not reach the download site: ${err.cause?.code || err.message}`, 502);
	}
	if (!res.ok) throw new BibleError(`The download site answered ${res.status}.`, 502);
	const raw = Buffer.from(await res.arrayBuffer());
	if (createHash('sha256').update(raw).digest('hex') !== entry.sha256) throw new BibleError('The download does not match its checksum.', 502);
	return Buffer.from(JSON.stringify(convert(entry, JSON.parse(raw.toString('utf8')))));
}

export class Bibles {
	constructor({ db, dataDir, fetchImpl = fetch, catalog = CATALOG }) {
		this.db = db;
		this.catalog = catalog;
		this.dir = path.join(dataDir, 'bibles');
		this.fetch = fetchImpl;
		this.busy = new Set();
	}

	file(id) {
		return path.join(this.dir, `${id}.json`);
	}

	installed() {
		return this.db.prepare('SELECT id, name, language, bytes, sha256 FROM bibles ORDER BY name').all();
	}

	get(id) {
		return this.db.prepare('SELECT id, name, language, bytes, sha256 FROM bibles WHERE id = ?').get(id);
	}

	/** The catalogue with what is installed, for the admin page. */
	list() {
		const have = new Map(this.installed().map((b) => [b.id, b]));
		return this.catalog.map((c) => ({ id: c.id, name: c.name, language: c.language, installed: have.has(c.id), bytes: have.get(c.id)?.bytes ?? null, downloading: this.busy.has(c.id) }));
	}

	async install(id) {
		const entry = this.catalog.find((c) => c.id === id);
		if (!entry) throw new BibleError('No such Bible version.', 404);
		if (this.busy.has(id)) throw new BibleError('This version is already being downloaded.', 409);
		this.busy.add(id);
		try {
			const data = await download(entry, this.fetch);
			await mkdir(this.dir, { recursive: true });
			await writeFile(this.file(id) + '.part', data);
			await rename(this.file(id) + '.part', this.file(id));
			this.db.prepare(`INSERT INTO bibles (id, name, language, bytes, sha256, installed_at) VALUES (?, ?, ?, ?, ?, ?)
				ON CONFLICT(id) DO UPDATE SET name = excluded.name, language = excluded.language, bytes = excluded.bytes, sha256 = excluded.sha256, installed_at = excluded.installed_at`)
				.run(id, entry.name, entry.language, data.length, createHash('sha256').update(data).digest('hex'), now());
		} finally {
			this.busy.delete(id);
			await rm(this.file(id) + '.part', { force: true });
		}
	}

	async remove(id) {
		this.db.prepare('DELETE FROM bibles WHERE id = ?').run(id);
		if (this.catalog.some((c) => c.id === id)) await rm(this.file(id), { force: true });
	}
}
