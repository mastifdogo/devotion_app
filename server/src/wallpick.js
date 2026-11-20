// "A new wallpaper each time the website is opened": which one, and which part of it.
// The same rules as the app's (WallpaperLogic.kt): from the chosen topic, never the one
// shown last when there is another, dark ones in the evening when asked for, and a random
// horizontal crop of an image wider than the window.
//
// The server puts this file into the start page and the reader as it is (see server.js),
// so it is a plain script: no imports or exports.
const WallPick = (() => {
	/** Mean relative luminance below this counts as a dark wallpaper. */
	const DARK_LUMINANCE = 0.25;

	/** WCAG relative luminance (0 = black, 1 = white) of "#424153"; null when it is not a 6-digit hex colour. */
	function luminance(hex) {
		const m = /^#?([0-9a-f]{6})$/i.exec(String(hex ?? '').trim());
		if (!m) return null;
		const n = parseInt(m[1], 16);
		const linear = (channel) => {
			const c = channel / 255;
			return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
		};
		return 0.2126 * linear((n >> 16) & 255) + 0.7152 * linear((n >> 8) & 255) + 0.0722 * linear(n & 255);
	}

	/** Mean luminance of a wallpaper's colours, the first (most dominant) weighing most; null without colours. */
	function meanLuminance(colors) {
		const known = (Array.isArray(colors) ? colors : []).map(luminance).filter((l) => l !== null);
		if (!known.length) return null;
		let sum = 0, weights = 0;
		known.forEach((l, i) => { const weight = known.length - i; sum += l * weight; weights += weight; });
		return sum / weights;
	}

	/** True when the wallpaper's colours are dark on average; unknown colours are not dark. */
	function isDark(wallpaper) {
		const mean = meanLuminance(wallpaper?.colors);
		return mean !== null && mean < DARK_LUMINANCE;
	}

	/** "19:00" as minutes after midnight; null when it is not a time of day. */
	function minutes(text) {
		const m = /^(\d{1,2}):(\d{2})$/.exec(String(text ?? '').trim());
		if (!m || Number(m[1]) > 23 || Number(m[2]) > 59) return null;
		return Number(m[1]) * 60 + Number(m[2]);
	}

	/** True when now is in [from, until), all in minutes after midnight; a window may pass midnight, and from = until is empty. */
	function inWindow(now, from, until) {
		if (from === until) return false;
		return from < until ? now >= from && now < until : now >= from || now < until;
	}

	/**
	 * A random wallpaper from the topic ('all', or a topic the server no longer has, = all of them),
	 * never the current one when there are two or more to choose from. With darkOnly, only dark
	 * ones unless there are none. Null when the server has no wallpapers.
	 */
	function pick(library, { topic = 'all', current = null, darkOnly = false, random = Math.random } = {}) {
		const all = Array.isArray(library?.wallpapers) ? library.wallpapers.filter((w) => w && typeof w.id === 'string') : [];
		let pool = topic && topic !== 'all' ? all.filter((w) => w.topic === topic) : all;
		if (!pool.length) pool = all;
		const dark = darkOnly ? pool.filter(isDark) : [];
		const candidates = dark.length ? dark : pool;
		if (!candidates.length) return null;
		const choices = candidates.length >= 2 ? candidates.filter((w) => w.id !== current) : candidates;
		return choices[Math.min(choices.length - 1, Math.floor(random() * choices.length))];
	}

	const DEFAULT_FROM = '19:00', DEFAULT_UNTIL = '07:00';

	/**
	 * What this browser asked for, read with get(key) (localStorage):
	 * { topic, dark, from, until }; topic is null when the wallpaper is kept.
	 */
	function prefs(get) {
		const read = (key) => { try { return get(key); } catch { return null; } };
		return {
			topic: read('read.wallRandom') || null,
			dark: read('read.wallDark') === '1',
			from: minutes(read('read.wallDarkFrom')) ?? minutes(DEFAULT_FROM),
			until: minutes(read('read.wallDarkUntil')) ?? minutes(DEFAULT_UNTIL),
		};
	}

	/**
	 * The wallpaper for this visit: { id, bias } (bias 0 = its left edge, 1 = its right edge),
	 * or null when the wallpaper is kept or the server has none.
	 */
	function choose(library, get, current, { now = new Date(), random = Math.random } = {}) {
		const p = prefs(get);
		if (!p.topic) return null;
		const darkOnly = p.dark && inWindow(now.getHours() * 60 + now.getMinutes(), p.from, p.until);
		const chosen = pick(library, { topic: p.topic, current, darkOnly, random });
		return chosen ? { id: chosen.id, bias: Math.round(random() * 1000) / 1000 } : null;
	}

	return { DEFAULT_FROM, DEFAULT_UNTIL, luminance, meanLuminance, isDark, minutes, inWindow, pick, prefs, choose };
})();
