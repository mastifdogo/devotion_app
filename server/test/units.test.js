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
	assert.deepEqual(out, [{ title: 'T', entry_date: null, scripture: null, summary: '', body: 'a\n\nb' }]);
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
