// Email HTML to readable plain text. Not a browser: enough to hand the model
// clean paragraphs instead of markup, tracking pixels and style sheets.
const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '–', mdash: '—', hellip: '…', rsquo: '’', lsquo: '‘', rdquo: '”', ldquo: '“', copy: '©', reg: '®', trade: '™', bull: '•', middot: '·', zwnj: '', zwj: '', shy: '' };

export function decodeEntities(text) {
	return text.replace(/&(#x[0-9a-f]+|#\d+|[a-z][a-z0-9]*);/gi, (whole, body) => {
		if (body[0] === '#') {
			const code = body[1] === 'x' || body[1] === 'X' ? parseInt(body.slice(2), 16) : parseInt(body.slice(1), 10);
			if (!Number.isFinite(code) || code <= 0 || code > 0x10ffff) return whole;
			try {
				return String.fromCodePoint(code);
			} catch {
				return whole;
			}
		}
		const named = ENTITIES[body.toLowerCase()];
		return named === undefined ? whole : named;
	});
}

export function htmlToText(html) {
	let s = String(html);
	s = s.replace(/<!--[\s\S]*?-->/g, '');
	s = s.replace(/<(script|style|head|title|svg|noscript|template)\b[\s\S]*?<\/\1\s*>/gi, '');
	s = s.replace(/<br\s*\/?>/gi, '\n');
	s = s.replace(/<li\b[^>]*>/gi, '\n- ');
	s = s.replace(/<\/(p|div|h[1-6]|li|tr|table|blockquote|section|article|ul|ol|header|footer)\s*>/gi, '\n\n');
	s = s.replace(/<(p|div|h[1-6]|tr|table|blockquote|section|article|ul|ol|hr)\b[^>]*>/gi, '\n');
	s = s.replace(/<\/t[dh]\s*>/gi, ' ');
	s = s.replace(/<[^>]+>/g, '');
	s = decodeEntities(s);
	s = s.replace(/[​‌‍⁠﻿­͏]/g, '');
	s = s.replace(/\r/g, '').replace(/[ \t ]+/g, ' ');
	s = s.replace(/ *\n */g, '\n').replace(/\n{3,}/g, '\n\n');
	return s.trim();
}
