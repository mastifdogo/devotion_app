import test from 'node:test';
import assert from 'node:assert/strict';
import { htmlToText } from '../src/html.js';
import { parseMessage, redirectUri } from '../src/gmail.js';
import { chunkText, speechText } from '../src/tts.js';
import { compareVersions, TAG_RE } from '../src/update.js';
import { normalizeEntries } from '../src/extract.js';

const b64 = (s) => Buffer.from(s).toString('base64url');

test('htmlToText keeps paragraphs and drops markup, styles and scripts', () => {
	const text = htmlToText('<html><head><style>p{color:red}</style><title>x</title></head><body><h1>Grace &amp; Peace</h1><p>First&nbsp;line<br>second line</p><script>alert(1)</script><ul><li>one</li><li>two</li></ul><p>&#8220;Quoted&#8221; &#x2014; end</p></body></html>');
	assert.equal(text, 'Grace & Peace\n\nFirst line\nsecond line\n\n- one\n\n- two\n\n“Quoted” — end');
});

test('parseMessage prefers the part that holds the content', () => {
	const msg = {
		id: 'abc',
		internalDate: '1763200000000',
		payload: {
			mimeType: 'multipart/alternative',
			headers: [{ name: 'Subject', value: 'Daily' }, { name: 'From', value: 'A <a@example.org>' }],
			parts: [
				{ mimeType: 'text/plain', body: { data: b64('View this email in your browser') } },
				{ mimeType: 'text/html', body: { data: b64('<p>' + 'The Lord is my shepherd. '.repeat(10) + '</p>') } },
				{ mimeType: 'text/plain', filename: 'notes.txt', body: { data: b64('attachment text that must be ignored') } },
			],
		},
	};
	const parsed = parseMessage(msg);
	assert.equal(parsed.subject, 'Daily');
	assert.equal(parsed.from, 'A <a@example.org>');
	assert.match(parsed.text, /^The Lord is my shepherd/);
	assert.doesNotMatch(parsed.text, /attachment/);
	assert.equal(parsed.date, new Date(1763200000000).toISOString());
});

test('redirectUri uses a public https address, otherwise localhost', () => {
	assert.equal(redirectUri('https://d.example.net/', 8787), 'https://d.example.net/oauth/callback');
	assert.equal(redirectUri('http://192.168.1.5:8787', 8787), 'http://localhost:8787/oauth/callback');
	assert.equal(redirectUri('', 9000), 'http://localhost:9000/oauth/callback');
});

test('chunkText never exceeds the limit and loses no words', () => {
	const text = ['Short paragraph.', 'A sentence here. '.repeat(40).trim(), 'x'.repeat(250), 'End.'].join('\n\n');
	const chunks = chunkText(text, 100);
	assert.ok(chunks.length > 5);
	for (const c of chunks) assert.ok(c.length <= 100, `chunk of ${c.length}`);
	assert.equal(chunks.join(' ').replace(/\s+/g, ''), text.replace(/\s+/g, ''));
	assert.deepEqual(chunkText('One.\n\nTwo.', 100), ['One.\n\nTwo.']);
});

test('speechText reads title, scripture, body', () => {
	assert.equal(speechText({ title: 'Rest', scripture: 'Psalm 23', body: 'He leads me.' }), 'Rest.\n\nPsalm 23.\n\nHe leads me.');
	assert.equal(speechText({ title: 'Why?', scripture: null, body: 'B' }), 'Why.\n\nB');
});

test('compareVersions', () => {
	assert.equal(compareVersions('v1.2.10', '1.2.9'), 1);
	assert.equal(compareVersions('1.2.0', 'v1.2.0'), 0);
	assert.equal(compareVersions('1.2', '1.2.1'), -1);
	assert.equal(compareVersions('1.3.0-rc.1', '1.3.0'), -1);
	assert.equal(compareVersions('1.3.0', '1.3.0-rc.1'), 1);
	assert.ok(TAG_RE.test('v1.2.3-rc.1'));
	assert.ok(!TAG_RE.test('v1.2.3; rm -rf /'));
});

test('normalizeEntries drops empty bodies and bad dates', () => {
	const out = normalizeEntries({ entries: [{ title: ' T ', entry_date: 'Nov 3', scripture: '', summary: null, body: 'a\n\n\n\nb' }, { title: 'x', body: '  ' }] });
	assert.deepEqual(out, [{ title: 'T', entry_date: null, scripture: null, summary: '', body: 'a\n\nb', references: [] }]);
	assert.deepEqual(normalizeEntries(null), []);
});

import { parseKeywords, buildQuery, matchKeyword } from '../src/pipeline.js';

test('keywords: parsing, the Gmail query, and matching', () => {
	const kws = parseKeywords('Daily Devotion\n  morning "prayer" , daily devotion\n\n{x}');
	assert.deepEqual(kws, ['Daily Devotion', 'morning prayer', 'x']);
	assert.equal(buildQuery(kws, ' from:a@b.c '), 'from:a@b.c {"Daily Devotion" "morning prayer" "x"}');
	assert.equal(buildQuery(['a'], ''), '{"a"}');
	assert.equal(matchKeyword({ subject: 'Your MORNING\n prayer', text: '' }, kws), 'morning prayer');
	assert.equal(matchKeyword({ subject: 'Receipt', from: 'shop', text: 'thanks' }, ['devotion']), null);
});

import { locateReferences, ENTRY_SCHEMA } from '../src/extract.js';

test('verse references are pinned to where they stand in the text', () => {
	const body = 'As John 3:16 says, and again John 3:16-17 (cf. Ps. 23), then v. 5.\n\nJohn 3:16';
	const refs = locateReferences(body, [
		{ text: 'John 3:16', book: 'John', chapter: 3, verse_start: 16, verse_end: null },
		{ text: 'John 3:16-17', book: 'John', chapter: 3, verse_start: 16, verse_end: 17 },
		{ text: 'Ps. 23', book: 'Psalms', chapter: 23, verse_start: null, verse_end: null },
		{ text: 'v. 5', book: 'Romans', chapter: 8, verse_start: 5, verse_end: 5 },
		{ text: 'Hezekiah 4:1', book: 'Hezekiah', chapter: 4, verse_start: 1, verse_end: null },
		{ text: 'John 3:16', book: 'John', chapter: 0, verse_start: 16, verse_end: null },
		{ text: 'Mark 1:1', book: 'Mark', chapter: 1, verse_start: 1, verse_end: null },
		null,
	]);
	assert.deepEqual(refs.map((r) => [body.slice(r.start, r.end), r.book_index, r.chapter, r.verse_start, r.verse_end]), [
		['John 3:16', 42, 3, 16, null],
		['John 3:16-17', 42, 3, 16, 17], // the longer reference wins where both match
		['Ps. 23', 18, 23, null, null],
		['v. 5', 44, 8, 5, null],
		['John 3:16', 42, 3, 16, null],
	]);
	assert.deepEqual(locateReferences('text', undefined), []);
	assert.equal(ENTRY_SCHEMA.properties.entries.items.properties.references.items.properties.book.enum.length, 66);
});

import { plan as resourcePlan, detect } from '../src/resources.js';

test('the audio plan follows the cores and memory the server has', () => {
	const jobs = (cores, usableMb, provider) => resourcePlan({ cores, memoryMb: usableMb, usableMb }, provider).audioJobs;
	assert.equal(jobs(1, 205), 0); // a 256 MB container: no room for the voice engine
	assert.equal(jobs(1, 410), 1); // 512 MB
	assert.equal(jobs(1, 819), 1); // 1 GB, one core: the core is the limit
	assert.equal(jobs(2, 1638), 1); // two cores: still one, the other serves the pages
	assert.equal(jobs(4, 819), 3); // four cores, 1 GB: cores allow 3 and so does memory
	assert.equal(jobs(4, 600), 2); // four cores, less memory: memory is the limit
	assert.equal(jobs(16, 16000), 4); // never more than four
	assert.equal(jobs(1, 205, 'openai'), 2); // a speech service needs no memory here
	assert.equal(jobs(8, 8000, 'none'), 0);
	assert.match(resourcePlan({ cores: 1, memoryMb: 256, usableMb: 205 }).note, /at least 512 MB/);
	const here = detect();
	assert.ok(here.cores >= 1 && here.memoryMb > 0 && here.usableMb > 0 && here.usableMb <= here.memoryMb);
});

test('mp3Duration walks the frames; parseWav reads streamed WAVs', async () => {
	const { mp3Duration, parseWav, wavFile } = await import('../src/tts.js');
	// MPEG-2 layer III, 64 kbit/s at 24 kHz: 192-byte frames of 576 samples.
	const frame = Buffer.alloc(192); frame.set([0xff, 0xf3, 0x84, 0xc4]);
	const id3 = Buffer.alloc(10 + 20); id3.write('ID3', 0, 'latin1'); id3[9] = 20;
	assert.equal(Math.round(mp3Duration(Buffer.concat([id3, ...Array(100).fill(frame)])) * 1000), 2400);
	assert.equal(mp3Duration(Buffer.alloc(500, 7)), 0);
	const wav = wavFile({ rate: 8000, channels: 1 }, [Buffer.alloc(16000, 1)]);
	assert.deepEqual([parseWav(wav).rate, parseWav(wav).data.length], [8000, 16000]);
	wav.writeUInt32LE(0xffffffff, 40); // a streamed file does not know its size
	assert.equal(parseWav(wav).data.length, 16000);
	assert.throws(() => parseWav(Buffer.alloc(100, 1)));
});
