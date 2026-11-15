import test from 'node:test';
import assert from 'node:assert/strict';
import { applySubject, classifySubject, isoDate, lessonDate } from '../src/classify.js';

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
