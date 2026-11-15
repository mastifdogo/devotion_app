import test from 'node:test';
import assert from 'node:assert/strict';
import { applySubject, classifySubject, isoDate, lessonDate, readingPassage } from '../src/classify.js';

// The openings of real emails, as the server's HTML-to-text step gives them.
const LESSON_8 = 'COGICM Daily Bible Readings\n\nPreschool: Be kindly affectioned one to another (Romans 12:10).\n\nDon’t Look Back\n\nLesson 8\n\nOctober 25, 2026\n\nBackground Scripture: Genesis 19\n\nLesson Scripture: Genesis 19:17-29; Luke 9:62';
const LESSON_11 = 'COGICM Daily Bible Readings\n\nVictory After Struggling\n\nLesson 11\n\nNovember 15, 2026\n\nBackground Scripture: Genesis 32; 1 Samuel 17';
const READING = 'COGICM Daily Bible Readings\n\nLesson 11 – Victory After Struggling\nToday’s Quote: “There was a saying…” ― Alison Goodman\nNovember 15, 2026 – The struggle is spiritual\n2 Cor. 10:4-6';

test('the subject says lesson or daily reading', () => {
	assert.deepEqual(classifySubject('Lesson 8 – Don’t Look Back'), { kind: 'lesson', lesson_number: 8, title: 'Don’t Look Back' });
	assert.deepEqual(classifySubject('Lesson 10 – Isaac’s Nonresistance'), { kind: 'lesson', lesson_number: 10, title: 'Isaac’s Nonresistance' });
	assert.deepEqual(classifySubject('Lesson 9 - God Provides a Wife for Isaac'), { kind: 'lesson', lesson_number: 9, title: 'God Provides a Wife for Isaac' });
	assert.deepEqual(classifySubject('November 15, 2026 – The struggle is spiritual'), { kind: 'daily_reading', entry_date: '2026-11-15', title: 'The struggle is spiritual' });
	assert.equal(classifySubject('Your receipt'), null);
	assert.equal(classifySubject('Lessons learned – a newsletter'), null);
	assert.equal(classifySubject('February 30, 2026 – Not a day'), null);
	assert.equal(isoDate('march', '1', '2027'), '2027-03-01');
});

test('a lesson is dated by the date printed under its number, not the day it was sent', () => {
	assert.equal(lessonDate(LESSON_8, 8), '2026-10-25');
	assert.equal(lessonDate(LESSON_11, 11), '2026-11-15');
	assert.equal(lessonDate(LESSON_11, 12), null);
	assert.equal(lessonDate('Victory After Struggling Lesson 11 · November 15, 2026', 11), '2026-11-15');
	assert.equal(lessonDate('Lesson 11\n\nIntroduction\n\nOn November 20, 2026 we met', 11), null);
	assert.equal(lessonDate(READING, 11), null); // "Lesson 11 – …" is a heading, not a lesson's date
});

test('the email overrides the model, and a split lesson is joined again', () => {
	const model = [
		{ kind: 'daily_reading', lesson_number: null, title: 'Memory verses', entry_date: '2026-10-18', scripture: null, summary: '', body: 'Preschool: Be kindly.', references: [{ start: 0, end: 9, book: 'Romans', book_index: 44, chapter: 12, verse_start: 10, verse_end: null }] },
		{ kind: 'daily_reading', lesson_number: null, title: 'Don’t Look Back', entry_date: '2026-10-18', scripture: 'Genesis 19:17-29', summary: 'Lot’s wife.', body: 'Remember Lot’s wife.', references: [{ start: 9, end: 19, book: 'Luke', book_index: 41, chapter: 17, verse_start: 32, verse_end: null }] },
	];
	const [lesson] = applySubject(model, { subject: 'Lesson 8 – Don’t Look Back', text: LESSON_8 });
	assert.deepEqual([lesson.kind, lesson.lesson_number, lesson.title, lesson.entry_date, lesson.scripture, lesson.summary], ['lesson', 8, 'Don’t Look Back', '2026-10-25', 'Genesis 19:17-29', 'Lot’s wife.']);
	assert.equal(lesson.body, 'Preschool: Be kindly.\n\nRemember Lot’s wife.');
	assert.deepEqual(lesson.references.map((r) => lesson.body.slice(r.start, r.end)), ['Preschool', 'Lot’s wife']);
	assert.equal(applySubject(model, { subject: 'Lesson 8 – x', text: LESSON_8 }).length, 1);

	const [reading] = applySubject([{ ...model[1], kind: 'lesson', lesson_number: null, entry_date: '2026-11-14' }], { subject: 'November 15, 2026 – The struggle is spiritual', text: READING });
	assert.deepEqual([reading.kind, reading.lesson_number, reading.entry_date, reading.title], ['daily_reading', 11, '2026-11-15', 'The struggle is spiritual']);

	// An email the subject says nothing about keeps what the model said.
	assert.deepEqual(applySubject(model, { subject: 'Weekly digest', text: '' }), model);
	assert.deepEqual(applySubject([], { subject: 'Lesson 8 – x', text: '' }), []);
});

// Four more daily readings: the subject, and the text's opening.
const READINGS = [
	['October 23, 2026 – Exhort one another daily', 'COGICM Daily Bible Readings\n\nLesson 8 – Don’t Look Back\nToday’s Quote: “Success is determined not by whether or not you face obstacles…” ― Ben Carson\nOctober 23, 2026 – Exhort one another daily\nHeb. 3:7-13\n\nKJV\n7 Wherefore (as the Holy Ghost saith…', ['2026-10-23', 8, 'Exhort one another daily', 'Heb. 3:7-13']],
	['October 24, 2026 – The lukewarm state', 'COGICM Daily Bible Readings\n\nLesson 8 – Don’t Look Back\nToday’s Quote: “....thoughts—just mere thoughts—are as powerful as electric batteries…” ― Frances Hodgson Burnett\nOctober 24, 2026 – The lukewarm state\nRev. 3:14-19\n\nKJV\n14 And unto the angel…', ['2026-10-24', 8, 'The lukewarm state', 'Rev. 3:14-19']],
	['October 25, 2026 – Jesus’s prayer for us', 'COGICM Daily Bible Readings\n\nLesson 8 – Don’t Look Back\nToday’s Quote: “I am still determined to be cheerful and happy…” ― Martha Washington\nOctober 25, 2026 – Jesus’s prayer for us\nJohn 17:11-19\n\nKJV\n11 And now I am no more in the world…', ['2026-10-25', 8, 'Jesus’s prayer for us', 'John 17:11-19']],
	['October 26, 2026 – God’s plan for mankind', 'COGICM Daily Bible Readings\n\nLesson 9 – God Provides a Wife for Isaac\nToday’s Quote: “Christianity says that God loves man…” ― C.S. Lewis\nOctober 26, 2026 – God’s plan for mankind\nGen. 2:18-24\n\nKJV\n18 And the LORD God said…', ['2026-10-26', 9, 'God’s plan for mankind', 'Gen. 2:18-24']],
	['November 15, 2026 – The struggle is spiritual', READING + '\n\nKJV\n4 (For the weapons…', ['2026-11-15', 11, 'The struggle is spiritual', '2 Cor. 10:4-6']],
];

test('daily readings: the day, the week\'s lesson and the printed passage', () => {
	const model = { kind: 'lesson', lesson_number: 3, title: 'x', entry_date: '2026-01-01', scripture: 'Hebrews 3', summary: 'S.', body: 'B', references: [] };
	for (const [subject, text, expected] of READINGS) {
		const [e] = applySubject([model], { subject, text });
		assert.deepEqual([e.entry_date, e.lesson_number, e.title, e.scripture, e.kind], [...expected, 'daily_reading'], subject);
	}
	assert.equal(readingPassage('November 15, 2026 – A title\nToday we think about many things at length.'), null);
	assert.equal(readingPassage('November 15, 2026 – A title\n1 Samuel 17'), '1 Samuel 17');
	assert.equal(readingPassage('November 15, 2026 – A title\nPsalm 23; John 1:1-5'), 'Psalm 23; John 1:1-5');
});
