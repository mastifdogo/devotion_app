import test from 'node:test';
import assert from 'node:assert/strict';
import { latestSlot, nextSlot, parseTimes, validTimeZone, wallClock } from '../src/schedule.js';

test('check times are read leniently and kept in order', () => {
	assert.deepEqual(parseTimes('6:00'), ['06:00']);
	assert.deepEqual(parseTimes('18:30, 6:00 18:30'), ['06:00', '18:30']);
	assert.equal(parseTimes('6am'), null);
	assert.equal(parseTimes('24:00'), null);
	assert.equal(parseTimes(''), null);
	assert.ok(validTimeZone('America/Winnipeg'));
	assert.ok(validTimeZone(''));
	assert.ok(!validTimeZone('Mars/Olympus'));
});

test('the last scheduled time before now, and the next one, in a time zone', () => {
	const tz = 'Etc/GMT+6'; // always UTC−6 (the sign is reversed in these names)
	const at = (iso) => new Date(iso);
	assert.deepEqual(wallClock(at('2026-11-15T12:05:00Z'), tz), { day: '2026-11-15', time: '06:05' });
	// 05:59 local: today's 06:00 hasn't come, so the last one was yesterday's.
	assert.equal(latestSlot(at('2026-11-15T11:59:00Z'), ['06:00'], tz), '2026-11-14 06:00');
	assert.equal(latestSlot(at('2026-11-15T12:00:00Z'), ['06:00'], tz), '2026-11-15 06:00');
	assert.equal(latestSlot(at('2026-11-16T03:00:00Z'), ['06:00', '18:00'], tz), '2026-11-15 18:00');
	assert.equal(nextSlot(at('2026-11-15T12:05:00Z'), ['06:00', '18:00'], tz), '2026-11-15 18:00');
	assert.equal(nextSlot(at('2026-11-16T03:00:00Z'), ['06:00', '18:00'], tz), '2026-11-16 06:00');
	// Year end.
	assert.equal(nextSlot(at('2027-01-01T05:00:00Z'), ['06:00'], 'UTC'), '2027-01-01 06:00');
	assert.equal(latestSlot(at('2027-01-01T05:00:00Z'), ['06:00'], 'UTC'), '2026-12-31 06:00');
});
