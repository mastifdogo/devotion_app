// Sorting by the email itself. The Daily Bible Readings emails name what they
// are in the subject:
//   "Lesson 11 – Victory After Struggling"              a weekly lesson
//   "November 15, 2026 – The struggle is spiritual"     a daily reading
// and a lesson's text carries its own date ("Lesson 11" then "November 15,
// 2026"), which is a later Sunday than the day the email was sent. These rules
// are certain where the model can only be likely, so they win when they match.

const MONTHS = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];
const DASH = '[\\u2013\\u2014-]';
const DATE = '(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(\\d{1,2}),?\\s+(\\d{4})';

/** "November 15, 2026" → "2026-11-15", or null. */
export function isoDate(month, day, year) {
	const m = MONTHS.indexOf(String(month).toLowerCase()) + 1;
	const d = Number(day);
	const y = Number(year);
	if (!m || d < 1 || d > 31 || y < 1900 || y > 2200) return null;
	const date = new Date(Date.UTC(y, m - 1, d));
	if (date.getUTCDate() !== d) return null; // e.g. February 30
	return date.toISOString().slice(0, 10);
}

/** What the subject says this email is, or null when it says nothing certain. */
export function classifySubject(subject) {
	const s = String(subject || '').replace(/\s+/g, ' ').trim();
	let m = new RegExp(`^Lesson\\s+(\\d{1,4})\\s*${DASH}\\s*(.+)$`, 'i').exec(s);
	if (m) return { kind: 'lesson', lesson_number: Number(m[1]), title: m[2].trim() };
	m = new RegExp(`^${DATE}\\s*${DASH}\\s*(.+)$`, 'i').exec(s);
	if (m) {
		const date = isoDate(m[1], m[2], m[3]);
		if (date) return { kind: 'daily_reading', entry_date: date, title: m[4].trim() };
	}
	return null;
}

/**
 * The passage a daily reading prints on the line under its dated heading
 * ("October 23, 2026 – Exhort one another daily" then "Heb. 3:7-13"), or null.
 */
export function readingPassage(text) {
	const m = new RegExp(`${DATE}\\s*${DASH}[^\\n]*\\n\\s*([^\\n]{3,60})`, 'i').exec(String(text || ''));
	const line = m?.[4]?.trim();
	// A reference: an optional 1–3, a book name (maybe abbreviated), a chapter, maybe verses.
	return line && /^(?:[1-3]\s*)?[A-Za-z][A-Za-z.' ]{1,25}\s\d{1,3}(?::\d{1,3}(?:\s*[-\u2013]\s*\d{1,3})?)?(?:\s*[;,]\s*[\w.: \u2013-]+)?$/.test(line) ? line : null;
}

/** The date printed after "Lesson N" in a lesson's text, or null. */
export function lessonDate(text, number) {
	// "Lesson 11" then, close after it (next line, or the same one), the date.
	// "Lesson 11 – Title" is a heading in a daily reading, not this.
	const re = new RegExp(`Lesson\\s+${number}\\b(?!\\s*${DASH})[^A-Za-z0-9]{0,40}${DATE}`, 'i');
	const m = re.exec(String(text || ''));
	return m ? isoDate(m[1], m[2], m[3]) : null;
}

/**
 * Applies what the email itself says to the model's entries. An email whose
 * subject names one lesson or one day's reading is one entry: if the model
 * split it up, the parts are joined again in order.
 */
export function applySubject(entries, message) {
	const known = classifySubject(message.subject);
	if (!known || entries.length === 0) return entries;
	let entry = entries[0];
	if (entries.length > 1) {
		let body = '';
		const references = [];
		for (const e of entries) {
			const offset = body ? body.length + 2 : 0;
			for (const r of e.references || []) references.push({ ...r, start: r.start + offset, end: r.end + offset });
			body = body ? `${body}\n\n${e.body}` : e.body;
		}
		entry = {
			...entries[0],
			body,
			references,
			summary: entries.find((e) => e.summary)?.summary || '',
			scripture: entries.find((e) => e.scripture)?.scripture || null,
		};
	}
	const out = { ...entry, kind: known.kind, title: known.title || entry.title };
	if (known.kind === 'lesson') {
		out.lesson_number = known.lesson_number;
		out.entry_date = lessonDate(message.text, known.lesson_number) || entry.entry_date;
	} else {
		out.entry_date = known.entry_date;
		out.scripture = readingPassage(message.text) || entry.scripture;
		// The reading names its week's lesson in its heading ("Lesson 11 – …").
		const lesson = new RegExp(`(?:^|\\n)\\s*Lesson\\s+(\\d{1,4})\\s*${DASH}`, 'i').exec(String(message.text || ''));
		out.lesson_number = lesson ? Number(lesson[1]) : entry.lesson_number ?? null;
	}
	return [out];
}
