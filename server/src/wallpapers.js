// Wallpapers kept on this server for the app. The admin finds them on
// Wallhaven (wallhaven.cc) from the admin page; the server downloads the
// chosen ones and the phone only ever gets them from here.
//
// Only safe-for-work images are ever requested (purity 100), and no
// Wallhaven account is used.
import { createHash } from 'node:crypto';
import { mkdir, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { now } from './db.js';

const API = 'https://wallhaven.cc/api/v1';
// Where Wallhaven serves images from. Nothing is downloaded from anywhere else.
const IMAGE_HOSTS = new Set(['w.wallhaven.cc', 'th.wallhaven.cc']);
const IMAGE_TYPES = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' };
const MAX_IMAGE = 30 * 1024 * 1024;

/** The category selectors: a topic is a name and the Wallhaven search words for it. */
export const TOPICS = [
	{ id: 'nature', name: 'Nature', q: 'nature' },
	{ id: 'mountains', name: 'Mountains', q: 'mountains' },
	{ id: 'ocean', name: 'Ocean', q: 'ocean' },
	{ id: 'forest', name: 'Forest', q: 'forest' },
	{ id: 'sky', name: 'Sky', q: 'sky' },
	{ id: 'sunset', name: 'Sunset', q: 'sunset' },
	{ id: 'flowers', name: 'Flowers', q: 'flowers' },
	{ id: 'rain', name: 'Rain', q: 'rain' },
	{ id: 'winter', name: 'Winter', q: 'winter' },
	{ id: 'minimalist', name: 'Minimalist', q: 'minimalism' },
	{ id: 'space', name: 'Space', q: 'space' },
	{ id: 'architecture', name: 'Architecture', q: 'architecture' },
	{ id: 'abstract', name: 'Abstract', q: 'abstract' },
	{ id: 'light', name: 'Light', q: 'light' },
];

export class WallpaperError extends Error {
	constructor(message, status = 502) {
		super(message);
		this.name = 'WallpaperError';
		this.status = status;
	}
}

/** A topic id for a custom search: its words, lower case, joined by dashes. */
export function topicId(name) {
	return String(name || '').toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
}

/**
 * The Wallhaven search URL for the admin page's selectors. Always SFW.
 *   kinds: { general, anime, people } (at least one)   order: top | latest | random
 */
export function searchUrl({ q = '', kinds = { general: true }, order = 'top', portrait = true, page = 1, seed = '' } = {}) {
	const bits = [kinds.general, kinds.anime, kinds.people].map((k) => (k ? '1' : '0')).join('');
	const params = new URLSearchParams({ q: String(q).trim().slice(0, 100), categories: bits === '000' ? '100' : bits, purity: '100' });
	if (order === 'latest') params.set('sorting', 'date_added');
	else if (order === 'random') {
		params.set('sorting', 'random');
		if (/^[A-Za-z0-9]{1,16}$/.test(seed)) params.set('seed', seed);
	} else {
		params.set('sorting', 'toplist');
		params.set('topRange', '1M');
	}
	if (portrait) {
		params.set('ratios', 'portrait');
		params.set('atleast', '1080x1920');
	}
	params.set('page', String(Math.max(1, Math.min(1000, Number(page) || 1))));
	return `${API}/search?${params}`;
}

/** Wallhaven's answer to what the admin page shows; anything not safe for work is dropped. */
export function parseSearch(json) {
	const list = Array.isArray(json?.data) ? json.data : [];
	const meta = json?.meta || {};
	return {
		results: list.filter((w) => w && typeof w.id === 'string' && w.purity === 'sfw' && w.thumbs?.large).map((w) => ({
			id: w.id,
			url: w.url,
			thumb: w.thumbs.large,
			width: w.dimension_x ?? null,
			height: w.dimension_y ?? null,
			resolution: w.resolution ?? null,
			category: w.category ?? null,
			colors: Array.isArray(w.colors) ? w.colors.slice(0, 5) : [],
			bytes: w.file_size ?? null,
		})),
		page: meta.current_page ?? 1,
		last_page: meta.last_page ?? 1,
		seed: meta.seed ?? null,
	};
}

function imageUrl(raw) {
	let url;
	try {
		url = new URL(raw);
	} catch {
		return null;
	}
	return url.protocol === 'https:' && IMAGE_HOSTS.has(url.hostname) ? url.href : null;
}

export class Wallpapers {
	constructor({ db, dataDir, fetchImpl = fetch, log = console }) {
		this.db = db;
		this.dir = path.join(dataDir, 'wallpapers');
		this.fetch = fetchImpl;
		this.log = log;
	}

	async getJson(url) {
		let res;
		try {
			res = await this.fetch(url, { headers: { 'user-agent': 'devotion-server' } });
		} catch (err) {
			throw new WallpaperError(`Could not reach Wallhaven: ${err.cause?.code || err.message}`);
		}
		if (res.status === 429) throw new WallpaperError('Wallhaven is busy (too many requests). Try again in a minute.', 429);
		if (res.status === 404) throw new WallpaperError('Wallhaven has no such wallpaper.', 404);
		if (!res.ok) throw new WallpaperError(`Wallhaven answered ${res.status}.`);
		return res.json().catch(() => {
			throw new WallpaperError('Wallhaven sent something that is not JSON.');
		});
	}

	async search(options) {
		const found = parseSearch(await this.getJson(searchUrl(options)));
		const have = new Set(this.db.prepare('SELECT id FROM wallpapers').all().map((r) => r.id));
		found.results = found.results.map((r) => ({ ...r, on_server: have.has(r.id) }));
		return found;
	}

	/** Downloads one image from Wallhaven's image hosts, checking its type and size. */
	async download(url) {
		let res;
		try {
			res = await this.fetch(url, { headers: { 'user-agent': 'devotion-server' } });
		} catch (err) {
			throw new WallpaperError(`Could not download the image: ${err.cause?.code || err.message}`);
		}
		if (!res.ok) throw new WallpaperError(`The image download answered ${res.status}.`);
		const type = String(res.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
		if (!IMAGE_TYPES[type]) throw new WallpaperError(`Not an image (${type || 'no type'}).`);
		const data = Buffer.from(await res.arrayBuffer());
		if (data.length < 100 || data.length > MAX_IMAGE) throw new WallpaperError('The image is empty or too large.');
		return { data, ext: IMAGE_TYPES[type], type };
	}

	/**
	 * Adds a wallpaper by its Wallhaven id. The server looks it up itself, so
	 * only an id comes from the browser, never a URL to fetch.
	 */
	async add(id, topic) {
		if (!/^[A-Za-z0-9]{1,16}$/.test(String(id))) throw new WallpaperError('Not a Wallhaven id.', 400);
		const t = this.resolveTopic(topic);
		if (this.db.prepare('SELECT 1 FROM wallpapers WHERE id = ?').get(id)) {
			this.db.prepare('UPDATE wallpapers SET topic = ?, topic_name = ? WHERE id = ?').run(t.id, t.name, id);
			return this.get(id);
		}
		const w = (await this.getJson(`${API}/w/${id}`))?.data;
		if (!w || w.purity !== 'sfw') throw new WallpaperError('Only safe-for-work wallpapers can be added.', 400);
		const full = imageUrl(w.path);
		const thumbUrl = imageUrl(w.thumbs?.large) || imageUrl(w.thumbs?.small);
		if (!full || !thumbUrl) throw new WallpaperError('Wallhaven gave no image for this wallpaper.');
		const image = await this.download(full);
		const thumb = await this.download(thumbUrl);
		await mkdir(this.dir, { recursive: true });
		const file = `${id}.${image.ext}`;
		const thumbFile = `${id}-thumb.${thumb.ext}`;
		for (const [name, data] of [[file, image.data], [thumbFile, thumb.data]]) {
			await writeFile(path.join(this.dir, name + '.part'), data);
			await rename(path.join(this.dir, name + '.part'), path.join(this.dir, name));
		}
		this.db.prepare(`INSERT INTO wallpapers (id, topic, topic_name, width, height, colors, file, type, bytes, sha256, thumb_file, thumb_type, source_url, added_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(
			id, t.id, t.name, Number(w.dimension_x) || null, Number(w.dimension_y) || null, JSON.stringify(Array.isArray(w.colors) ? w.colors.slice(0, 5) : []),
			file, image.type, image.data.length, createHash('sha256').update(image.data).digest('hex'), thumbFile, thumb.type, `https://wallhaven.cc/w/${id}`, now(),
		);
		return this.get(id);
	}

	/** Adds the top results of a search in one go (skipping ones already here). */
	async addTop(options, topic, count = 12) {
		const { results } = await this.search(options);
		let added = 0;
		const errors = [];
		for (const r of results.slice(0, Math.max(1, Math.min(48, count)))) {
			if (r.on_server) continue;
			try {
				await this.add(r.id, topic);
				added++;
			} catch (err) {
				errors.push(`${r.id}: ${err.message}`);
				if (err.status === 429) break; // Wallhaven asked us to slow down
			}
		}
		return { added, errors };
	}

	resolveTopic(topic) {
		const known = TOPICS.find((t) => t.id === topic?.id || t.id === topic);
		if (known) return known;
		const name = String(topic?.name || topic || '').replace(/\s+/g, ' ').trim().slice(0, 40);
		const id = topicId(name);
		if (!id) throw new WallpaperError('Choose a topic for the wallpaper.', 400);
		return { id, name };
	}

	get(id) {
		const row = this.db.prepare('SELECT * FROM wallpapers WHERE id = ?').get(id);
		return row ? wallpaperJson(row) : null;
	}

	row(id) {
		return this.db.prepare('SELECT * FROM wallpapers WHERE id = ?').get(id);
	}

	file(name) {
		return path.join(this.dir, path.basename(name));
	}

	/** The app's view: topics that have wallpapers (in the selector order), and the wallpapers. */
	list() {
		const rows = this.db.prepare('SELECT * FROM wallpapers ORDER BY added_at DESC, id').all();
		const counts = new Map();
		for (const r of rows) counts.set(r.topic, { id: r.topic, name: r.topic_name, count: (counts.get(r.topic)?.count || 0) + 1 });
		const order = (id) => {
			const i = TOPICS.findIndex((t) => t.id === id);
			return i === -1 ? TOPICS.length : i;
		};
		const topics = [...counts.values()].sort((a, b) => order(a.id) - order(b.id) || a.name.localeCompare(b.name));
		return { topics, wallpapers: rows.map(wallpaperJson) };
	}

	async remove(id) {
		const row = this.row(id);
		if (!row) return false;
		await rm(this.file(row.file), { force: true });
		await rm(this.file(row.thumb_file), { force: true });
		this.db.prepare('DELETE FROM wallpapers WHERE id = ?').run(id);
		return true;
	}
}

function wallpaperJson(r) {
	let colors = [];
	try {
		colors = JSON.parse(r.colors || '[]');
	} catch {}
	return { id: r.id, topic: r.topic, topic_name: r.topic_name, width: r.width, height: r.height, colors, bytes: r.bytes, sha256: r.sha256, source_url: r.source_url, added_at: r.added_at };
}
