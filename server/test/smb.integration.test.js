// The archive against a real SMB server and the real smbclient program.
// Skipped unless SMB_TEST_SHARE is set; CI sets it up (see ci.yml).
//   SMB_TEST_SHARE=//127.0.0.1/devotion  SMB_TEST_USER  SMB_TEST_PASSWORD
//   SMB_TEST_DIR=<the directory behind the share, to look at what arrived>
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { openDb, now } from '../src/db.js';
import { Settings } from '../src/settings.js';
import { Archiver } from '../src/archive.js';

const { SMB_TEST_SHARE: share, SMB_TEST_USER: user, SMB_TEST_PASSWORD: password, SMB_TEST_DIR: shareDir } = process.env;
const quiet = { info() {}, error() {} };

test('archive to a real SMB share', { skip: share ? false : 'SMB_TEST_SHARE is not set' }, async (t) => {
	const dir = mkdtempSync(path.join(tmpdir(), 'devotion-smb-test-'));
	const audioDir = path.join(dir, 'audio');
	mkdirSync(audioDir);
	const db = openDb(dir);
	t.after(() => { db.close(); rmSync(dir, { recursive: true, force: true }); });
	const settings = new Settings(db, {});
	const archiver = new Archiver({ db, settings, audioDir, log: quiet });
	const folder = `Devotion Test/run ${Date.now()}`;

	// A wrong password is reported as such, and nothing is archived.
	settings.applyForm({ smb_share: share, smb_folder: folder, smb_username: user, smb_password: password + 'x' });
	await assert.rejects(archiver.test(), /user name or password was not accepted/);
	settings.applyForm({ smb_password: password });
	assert.deepEqual(await archiver.test(), { ok: true, folder });
	// The test leaves nothing behind but the folder itself.
	assert.deepEqual(existsSync(path.join(shareDir, folder)), true);

	const audio = Buffer.alloc(300_000, 3);
	writeFileSync(path.join(audioDir, '1-abc.mp3'), audio);
	const old = new Date(Date.now() - 45 * 86400000).toISOString();
	writeFileSync(path.join(audioDir, '1-amy.mp3'), audio.subarray(0, 1000));
	const insert = db.prepare(`INSERT INTO entries (gmail_id, title, entry_date, scripture, summary, body, source_from, source_subject, received_at, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
	insert.run('m1', 'Grace: "enough" & more; É', old.slice(0, 10), 'John 1:16', 'A summary.', 'First paragraph.\n\nSecond paragraph.', 'a@example.org', 'Subject', old, now(), now());
	insert.run('m2', 'Recent', null, null, '', 'Stays here.', null, null, now(), now(), now());
	const audioRow = db.prepare('INSERT INTO entry_audio (entry_id, voice, file, bytes, sha256, created_at) VALUES (?, ?, ?, ?, ?, ?)');
	audioRow.run(1, 'en_US-lessac-medium', '1-abc.mp3', audio.length, 'x', now());
	audioRow.run(1, 'en_US-amy-medium', '1-amy.mp3', 1000, 'y', now());

	assert.equal(await archiver.runOnce(), 1);
	const [y, m] = [old.slice(0, 4), old.slice(5, 7)];
	const base = path.join(shareDir, folder, y, m, `${old.slice(0, 10)}_1_Grace-enough-more-E`);
	assert.deepEqual(readFileSync(base + '_en_US-lessac-medium.mp3'), audio);
	assert.deepEqual(readFileSync(base + '_en_US-amy-medium.mp3'), audio.subarray(0, 1000));
	assert.match(readFileSync(base + '.txt', 'utf8'), /^Grace: "enough" & more; É\n.*\nJohn 1:16\n\nSummary: A summary\.\n\nFirst paragraph\.\n\nSecond paragraph\./s);

	const rows = db.prepare('SELECT id, body, archived_at IS NOT NULL AS archived, archive_path FROM entries ORDER BY id').all();
	assert.deepEqual(rows.map((r) => [r.body, r.archived]), [['', 1], ['Stays here.', 0]]);
	assert.equal(db.prepare('SELECT COUNT(*) AS n FROM entry_audio').get().n, 0);
	assert.equal(existsSync(path.join(audioDir, '1-amy.mp3')), false);
	assert.equal(rows[0].archive_path, `${folder}/${y}/${m}/${old.slice(0, 10)}_1_Grace-enough-more-E.txt`);
	assert.equal(existsSync(path.join(audioDir, '1-abc.mp3')), false);
	assert.equal(await archiver.runOnce(), 0);
});

test('backup to a real SMB share, keep the newest, and stage a restore', { skip: share ? false : 'SMB_TEST_SHARE is not set' }, async (t) => {
	const { Backups } = await import('../src/backup.js');
	const dir = mkdtempSync(path.join(tmpdir(), 'devotion-smb-backup-'));
	const db = openDb(dir);
	t.after(() => { db.close(); rmSync(dir, { recursive: true, force: true }); });
	const settings = new Settings(db, {});
	const folder = `Devotion Test/backups ${Date.now()}`;
	settings.applyForm({ backup_smb_share: share, backup_smb_folder: folder, backup_smb_username: user, backup_smb_password: password, backup_keep: '1' });
	const backups = new Backups({ db, settings, dataDir: dir, exit: () => {}, log: quiet });
	assert.deepEqual(await backups.test(), { ok: true, folder });
	assert.deepEqual(await backups.list(), []);
	await backups.runOnce();
	await new Promise((r) => setTimeout(r, 1100));
	const second = await backups.runOnce();
	assert.equal(second.removed, 1);
	const list = await backups.list();
	assert.equal(list.length, 1);
	assert.ok(list[0].bytes > 0);
	if (shareDir) assert.equal(readFileSync(path.join(shareDir, folder, list[0].name)).length, list[0].bytes);
	await backups.restore(list[0].name);
	assert.ok(existsSync(path.join(dir, 'devotion.db.restore')));
});
