// When to check the mail: at set times of day ("06:00", or "06:00, 18:00"),
// in a chosen time zone (empty: the server's own).

/** "6:00, 18:30" → ["06:00", "18:30"]; null if any part isn't a time of day. */
export function parseTimes(text) {
	const parts = String(text || '').split(/[,\s;]+/).filter(Boolean);
	if (!parts.length) return null;
	const out = new Set();
	for (const p of parts) {
		const m = /^([01]?\d|2[0-3]):([0-5]\d)$/.exec(p);
		if (!m) return null;
		out.add(`${m[1].padStart(2, '0')}:${m[2]}`);
	}
	return [...out].sort();
}

export function validTimeZone(tz) {
	if (!tz) return true;
	try {
		new Intl.DateTimeFormat('en-US', { timeZone: tz });
		return true;
	} catch {
		return false;
	}
}

/** The wall-clock date and time at `date` in `tz`: { day: "2026-11-15", time: "06:00" }. */
export function wallClock(date, tz) {
	const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
		timeZone: tz || undefined, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
	}).formatToParts(date).map((p) => [p.type, p.value]));
	return { day: `${parts.year}-${parts.month}-${parts.day}`, time: `${parts.hour}:${parts.minute}` };
}

const prevDay = (day) => new Date(Date.parse(day + 'T12:00:00Z') - 86400000).toISOString().slice(0, 10);
const nextDay = (day) => new Date(Date.parse(day + 'T12:00:00Z') + 86400000).toISOString().slice(0, 10);

/**
 * The most recent scheduled moment at or before `now`, as "YYYY-MM-DD HH:MM"
 * wall-clock time in `tz`. The mail is checked when this differs from the
 * one last checked, which also catches up a check missed while the server
 * was off (at most one, not one per missed day).
 */
export function latestSlot(now, times, tz) {
	const { day, time } = wallClock(now, tz);
	const today = times.filter((t) => t <= time);
	return today.length ? `${day} ${today.at(-1)}` : `${prevDay(day)} ${times.at(-1)}`;
}

/** The next scheduled moment after `now`, for the admin page: "YYYY-MM-DD HH:MM". */
export function nextSlot(now, times, tz) {
	const { day, time } = wallClock(now, tz);
	const later = times.filter((t) => t > time);
	return later.length ? `${day} ${later[0]}` : `${nextDay(day)} ${times[0]}`;
}
