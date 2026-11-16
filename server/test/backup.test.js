// Backups of the database to an SMB share, against a stand-in for smbclient
// that keeps the "share" in a temporary folder (the real program is tested in
// smb.integration.test.js).
import test from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { openDb, now } from '../src/db.js';
import { Settings } from '../src/settings.js';
import { Backups, backupName, checkDatabase, parseListing, takePendingRestore } from '../src/backup.js';

const quiet = { info() {}, error() {} };

/** A tiny smbclient: mkdir, put, get, ls (with * patterns), del, rmdir. */
function fakeSmb(shareDir) {
	const calls = [];
	const run = async (cmd, args) => {
		assert.equal(cmd, 'smbclient');
		const commands = args[args.indexOf('-c') + 1];
		calls.push(commands);
		let out = '';
		for (const c of commands.split('; ')) {
			const q = [...c.matchAll(/"([^"]*)"/g)].map((m) => m[1]);
			const at = (p) => path.join(shareDir, ...p.split('\\'));
			const verb = c.split(' ')[0];
			if (verb === 'mkdir') { if (existsSync(at(q[0]))) out += 'NT_STATUS_OBJECT_NAME_COLLISION making remote directory\n'; else mkdirSync(at(q[0])); }
			else if (verb === 'rmdir') rmSync(at(q[0]), { recursive: true });
			else if (verb === 'put') copyFileSync(q[0], at(q[1]));
			else if (verb === 'get') { if (existsSync(at(q[0]))) copyFileSync(at(q[0]), q[1]); else out += `NT_STATUS_OBJECT_NAME_NOT_FOUND opening remote file ${q[0]}\n`; }
			else if (verb === 'del') rmSync(at(q[0]), { force: true });
			else if (verb === 'ls') {
				const full = at(q[0]);
				const dir = path.dirname(full); const pattern = new RegExp('^' + path.basename(full).replace(/[.]/g, '\\.').replace(/\*/g, '.*') + '$');
				// Like smbclient, "ls folder" lists the folder itself.
				const names = existsSync(dir) ? readdirSync(dir).filter((n) => pattern.test(n)) : [];
				if (!names.length) out += `NT_STATUS_NO_SUCH_FILE listing ${q[0]}\n`;
				for (const n of names) {
					const st = statSync(path.join(dir, n));
					out += `  ${n.padEnd(36)} ${st.isDirectory() ? 'D' : 'A'} ${String(st.isDirectory() ? 0 : st.size).padStart(10)}  Mon Nov 16 03:00:00 2026\n`;
				}
				out += '\n\t\t61255492 blocks of size 1024. 1010560 blocks available\n';
			}
		}
		return { code: 0, out };
	};
	return { run, calls };
}

function setup(t) {
	const dir = mkdtempSync(path.join(tmpdir(), 'devotion-backup-test-'));
	const share = path.join(dir, 'share');
	const data = path.join(dir, 'data');
	mkdirSync(share);
	const db = openDb(data);
	t.after(() => { try { db.close(); } catch {} rmSync(dir, { recursive: true, force: true }); });
	const settings = new Settings(db, {});
	const smb = fakeSmb(share);
	const exits = [];
	const backups = new Backups({ db, settings, dataDir: data, runImpl: smb.run, exit: (c) => exits.push(c), log: quiet });
	return { dir, share, data, db, settings, smb, backups, exits };
}

test('backup names and listings', () => {
	assert.equal(backupName(new Date('2026-11-16T03:04:05.678Z')), 'devotion-2026-11-16_030405.db.gz');
	const out = '  .   D 0  x\n  devotion-2026-11-15_030000.db.gz      A    12345  Sun Nov 15\n  devotion-2026-11-16_030000.db.gz      A    999  Mon Nov 16\n  notes.txt   A  5  x\n  devotion-evil.db.gz  A 1 x\n';
	assert.deepEqual(parseListing(out), [
		{ name: 'devotion-2026-11-16_030000.db.gz', bytes: 999, created_at: '2026-11-16T03:00:00Z' },
		{ name: 'devotion-2026-11-15_030000.db.gz', bytes: 12345, created_at: '2026-11-15T03:00:00Z' },
	]);
});

test('backs up, keeps the newest, and restores', async (t) => {
	const { share, data, db, settings, smb, backups, exits } = setup(t);
	await assert.rejects(backups.runOnce(), /backup share first/);
	settings.applyForm({ backup_smb_share: '//nas/backups', backup_smb_folder: 'Devotion backups/home', backup_smb_username: 'me', backup_smb_password: 'pw', backup_keep: '2' });
	assert.equal(settings.get('backup_smb_share'), '//nas/backups');
	assert.deepEqual(await backups.test(), { ok: true, folder: 'Devotion backups/home' });
	assert.deepEqual(await backups.list(), []);

	db.prepare('INSERT INTO profiles (name, name_key, created_at) VALUES (?, ?, ?)').run('Maria', 'maria', now());
	const first = await backups.runOnce();
	assert.match(first.last_file, /^Devotion backups\/home\/devotion-\d{4}-\d{2}-\d{2}_\d{6}\.db\.gz$/);
	// The password is never in smbclient's arguments.
	assert.ok(smb.calls.every((c) => !c.includes('pw')));
	const folder = path.join(share, 'Devotion backups', 'home');
	assert.equal(readdirSync(folder).length, 1);

	// Old ones beyond "keep" are removed, newest kept; other files are left alone.
	writeFileSync(path.join(folder, 'devotion-2020-01-01_000000.db.gz'), 'old');
	writeFileSync(path.join(folder, 'devotion-2020-01-02_000000.db.gz'), 'old');
	writeFileSync(path.join(folder, 'readme.txt'), 'mine');
	await new Promise((r) => setTimeout(r, 1100)); // a new name (names have seconds)
	db.prepare('INSERT INTO profiles (name, name_key, created_at) VALUES (?, ?, ?)').run('Sam', 'sam', now());
	const second = await backups.runOnce();
	assert.equal(second.removed, 2);
	const left = readdirSync(folder).sort();
	assert.equal(left.length, 3);
	assert.ok(left.includes('readme.txt'));
	const list = await backups.list();
	assert.equal(list.length, 2);
	assert.ok(list[0].name > list[1].name);

	// Restoring the first backup: refused for a bad name, staged after checking, then swapped in at startup.
	await assert.rejects(backups.restore('../../etc/passwd'), /not the name of a backup/);
	await assert.rejects(backups.restore('devotion-2019-01-01_000000.db.gz'), /Could not download/);
	writeFileSync(path.join(folder, 'devotion-2019-02-02_000000.db.gz'), 'not gzip');
	await assert.rejects(backups.restore('devotion-2019-02-02_000000.db.gz'), /damaged/);
	const older = list[1].name;
	assert.deepEqual(await backups.restore(older), { ok: true, name: older });
	assert.ok(existsSync(path.join(data, 'devotion.db.restore')));
	backups.restartSoon();
	await new Promise((r) => setTimeout(r, 600));
	assert.deepEqual(exits, [75]);

	db.close();
	assert.equal(takePendingRestore(data, quiet), true);
	assert.equal(takePendingRestore(data, quiet), false);
	assert.ok(existsSync(path.join(data, 'devotion.db.before-restore')));
	const restored = openDb(data);
	assert.deepEqual(restored.prepare('SELECT name FROM profiles ORDER BY name').all().map((r) => r.name), ['Maria']);
	restored.close();
	assert.equal(checkDatabase(path.join(data, 'devotion.db.before-restore')), null);
	writeFileSync(path.join(data, 'junk'), 'hello');
	assert.match(checkDatabase(path.join(data, 'junk')), /not a database|damaged/);
});

test('scheduled backups run at the set times, not when the schedule is first saved', async (t) => {
	const { settings, backups } = setup(t);
	settings.applyForm({ backup_smb_share: '//nas/backups', backup_times: '3:00', timezone: 'UTC' });
	assert.equal(settings.get('backup_times'), '03:00');
	assert.equal(await backups.tick(new Date('2026-11-16T14:00:00Z')), false); // only remembers where it is
	assert.equal(await backups.tick(new Date('2026-11-16T20:00:00Z')), false);
	assert.equal(await backups.tick(new Date('2026-11-17T03:00:30Z')), true);
	assert.equal(await backups.tick(new Date('2026-11-17T03:01:00Z')), false);
	assert.ok(backups.view().last_file);
	settings.applyForm({ backup_times: '' });
	assert.equal(settings.get('backup_times'), '');
	assert.equal(await backups.tick(new Date('2026-11-18T03:00:30Z')), false);
	settings.applyForm({ backup_times: 'nonsense', backup_keep: '0' });
	assert.equal(settings.get('backup_times'), '');
	assert.equal(settings.get('backup_keep'), '14');
});
