// Backups of the server's database to an SMB share: on a schedule (times of
// day, like the mail check) or from the admin page, keeping the newest few.
// The database holds the entries, profiles, highlights and comments, and the
// settings (including the Gmail and API keys), so the backup is everything
// needed to rebuild a server; audio, voices, Bibles and wallpapers are made
// or downloaded again.
//
// Restoring downloads a backup, checks it is a Devotion database, and leaves
// it next to the live one; the server then restarts and swaps it in before
// opening anything (see takePendingRestore).
import { createReadStream, createWriteStream, existsSync, renameSync, rmSync } from 'node:fs';
import { mkdtemp, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pipeline as streamPipeline } from 'node:stream/promises';
import { DatabaseSync } from 'node:sqlite';
import { createGunzip, createGzip } from 'node:zlib';
import { ArchiveError, explain, folderParts, mkdirs, runCapture, smbRun } from './archive.js';
import { now } from './db.js';
import { latestSlot, nextSlot, parseTimes } from './schedule.js';

export const PREFIX = 'backup_smb_';
const NAME = /^devotion-(\d{4}-\d{2}-\d{2})_(\d{2})(\d{2})(\d{2})\.db\.gz$/;
const RESTORE_FILE = 'devotion.db.restore';

/** "devotion-2026-11-16_031500.db.gz" for that moment (UTC). */
export function backupName(date = new Date()) {
	const iso = date.toISOString();
	return `devotion-${iso.slice(0, 10)}_${iso.slice(11, 13)}${iso.slice(14, 16)}${iso.slice(17, 19)}.db.gz`;
}

/** The backups in an smbclient listing, newest first: [{ name, bytes, created_at }]. */
export function parseListing(out) {
	const found = [];
	for (const line of String(out).split('\n')) {
		const m = /^\s+(devotion-[\d_-]+\.db\.gz)\s+\w*\s+(\d+)\s/.exec(line);
		const n = m && NAME.exec(m[1]);
		if (!n) continue;
		found.push({ name: m[1], bytes: Number(m[2]), created_at: `${n[1]}T${n[2]}:${n[3]}:${n[4]}Z` });
	}
	return found.sort((a, b) => b.name.localeCompare(a.name));
}

/** True when the file is an SQLite database with this server's tables and passes its own check. */
export function checkDatabase(file) {
	let db;
	try {
		db = new DatabaseSync(file, { readOnly: true });
		const ok = db.prepare('PRAGMA integrity_check').get();
		if (Object.values(ok)[0] !== 'ok') return 'the database is damaged';
		const tables = new Set(db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all().map((r) => r.name));
		for (const t of ['settings', 'entries', 'profiles', 'annotations']) if (!tables.has(t)) return `it has no ${t} table, so it is not a Devotion backup`;
		return null;
	} catch (err) {
		return `it is not a database (${err.message})`;
	} finally {
		db?.close();
	}
}

/**
 * Called at startup before the database is opened: a restore staged by the
 * admin page replaces the live database, which is kept as devotion.db.before-restore.
 * Returns true when it swapped.
 */
export function takePendingRestore(dataDir, log = console) {
	const staged = path.join(dataDir, RESTORE_FILE);
	if (!existsSync(staged)) return false;
	const live = path.join(dataDir, 'devotion.db');
	const old = path.join(dataDir, 'devotion.db.before-restore');
	rmSync(old, { force: true });
	if (existsSync(live)) renameSync(live, old);
	// The old database's write-ahead log belongs to it, not to the restored one.
	for (const ext of ['-wal', '-shm']) {
		if (existsSync(live + ext)) renameSync(live + ext, old + ext);
	}
	renameSync(staged, live);
	log.info?.('restored the database from a backup; the previous one is devotion.db.before-restore');
	return true;
}

export class Backups {
	constructor({ db, settings, dataDir, runImpl = runCapture, exit = (code) => process.exit(code), log = console }) {
		Object.assign(this, { db, settings, dataDir, run: runImpl, exit, log });
		this.running = false;
		this.status = { last_run: null, last_error: null, last_file: null };
		this.timer = null;
	}

	configured() {
		return this.settings.get(PREFIX + 'share') !== '';
	}

	times() {
		return parseTimes(this.settings.get('backup_times')) || [];
	}

	keep() {
		return Math.min(365, Math.max(1, Number(this.settings.get('backup_keep')) || 14));
	}

	folder() {
		return folderParts(this.settings.get(PREFIX + 'folder'));
	}

	smb(commands) {
		return smbRun(this.run, this.settings, PREFIX, commands);
	}

	/** For the admin page. */
	view() {
		const times = this.times();
		const tz = this.settings.get('timezone');
		return {
			...this.status,
			configured: this.configured(),
			running: this.running,
			next: this.configured() && times.length ? nextSlot(new Date(), times, tz) : null,
		};
	}

	start() {
		this.timer = setInterval(() => this.tick().catch((err) => this.log.error('backup:', err.message)), 30_000);
		this.timer.unref?.();
	}

	stop() {
		clearInterval(this.timer);
	}

	/** Backs up when a scheduled time has passed since the last scheduled backup. */
	async tick(at = new Date()) {
		const times = this.times();
		if (!this.configured() || !times.length || this.running) return false;
		const slot = latestSlot(at, times, this.settings.get('timezone'));
		const last = this.settings.get('backup_last_slot');
		if (slot === last) return false;
		this.settings.set('backup_last_slot', slot);
		// The first time a schedule is seen it only remembers where it is, so
		// saving the settings at 14:00 does not start a backup for 03:00.
		if (!last) return false;
		await this.runOnce();
		return true;
	}

	/** Checks the share can be reached and written to. */
	async test() {
		if (!this.configured()) throw new ArchiveError('Enter the share first, as //server/share.', 400);
		const parts = this.folder();
		const name = `devotion-test-${Date.now()}`;
		const probe = [...parts, name].join('\\');
		const { out } = await this.smb([...mkdirs(parts), `mkdir "${probe}"`, `ls "${probe}"`, `rmdir "${probe}"`]);
		if (!new RegExp(`^\\s+${name}\\s+D\\w*\\s+0\\s`, 'm').test(out)) throw new ArchiveError(`Could not write to the share: ${explain(out)}.`);
		return { ok: true, folder: parts.join('/') || '(top of the share)' };
	}

	async list() {
		if (!this.configured()) return [];
		const parts = this.folder();
		const where = parts.length ? `${parts.join('\\')}\\` : '';
		const { out } = await this.smb([`ls "${where}devotion-*.db.gz"`]);
		// No backups yet is NT_STATUS_NO_SUCH_FILE: an empty list, not an error.
		const found = parseListing(out);
		if (found.length || /NT_STATUS_NO_SUCH_FILE|NT_STATUS_OBJECT_NAME_NOT_FOUND|NT_STATUS_OBJECT_PATH_NOT_FOUND/.test(out)) return found;
		if (/NT_STATUS_/.test(out)) throw new ArchiveError(`Could not list the backups: ${explain(out)}.`);
		return found;
	}

	/** Makes one backup now, then removes the oldest beyond the number to keep. */
	async runOnce() {
		if (!this.configured()) throw new ArchiveError('Enter and save the backup share first.', 409);
		if (this.running) throw new ArchiveError('A backup is already running.', 409);
		this.running = true;
		const dir = await mkdtemp(path.join(tmpdir(), 'devotion-backup-'));
		try {
			const snapshot = path.join(dir, 'devotion.db');
			// A consistent copy of the live database, safe while the server keeps writing.
			this.db.prepare('VACUUM INTO ?').run(snapshot);
			const name = backupName();
			const gz = path.join(dir, name);
			await streamPipeline(createReadStream(snapshot), createGzip({ level: 6 }), createWriteStream(gz));
			const size = (await stat(gz)).size;
			const parts = this.folder();
			const remote = [...parts, name].join('\\');
			const { out } = await this.smb([...mkdirs(parts), `put "${gz}" "${remote}"`, `ls "${remote}"`]);
			const listed = new RegExp(`^\\s+${name.replace(/\./g, '\\.')}\\s+\\w*\\s+(\\d+)\\s`, 'm').exec(out);
			if (!listed || Number(listed[1]) !== size) throw new ArchiveError(`The backup did not arrive on the share: ${explain(out)}.`);
			const removed = await this.prune().catch((err) => {
				this.log.error('backup: removing old backups:', err.message);
				return 0;
			});
			this.status = { last_run: now(), last_error: null, last_file: `${parts.join('/')}${parts.length ? '/' : ''}${name}`, bytes: size, removed };
			this.log.info(`backup: ${name} (${size} bytes) saved to the share`);
			return this.status;
		} catch (err) {
			this.status = { ...this.status, last_run: now(), last_error: err.message };
			this.log.error('backup:', err.message);
			throw err;
		} finally {
			this.running = false;
			await rm(dir, { recursive: true, force: true });
		}
	}

	/** Deletes the oldest backups beyond the number to keep. Only names this server makes are touched. */
	async prune() {
		const all = await this.list();
		const old = all.slice(this.keep());
		if (!old.length) return 0;
		const parts = this.folder();
		await this.smb(old.map((b) => `del "${[...parts, b.name].join('\\')}"`));
		return old.length;
	}

	/**
	 * Downloads a backup, checks it, stages it for the next start and restarts
	 * the server (systemd starts it again). The current database is kept as
	 * devotion.db.before-restore.
	 */
	async restore(name) {
		if (!NAME.test(String(name))) throw new ArchiveError('That is not the name of a backup.', 400);
		if (!this.configured()) throw new ArchiveError('Enter and save the backup share first.', 409);
		if (this.running) throw new ArchiveError('A backup is running; try again when it has finished.', 409);
		this.running = true;
		const dir = await mkdtemp(path.join(tmpdir(), 'devotion-restore-'));
		try {
			const gz = path.join(dir, name);
			const { out } = await this.smb([`get "${[...this.folder(), name].join('\\')}" "${gz}"`]);
			if (!(await stat(gz).catch(() => null))) throw new ArchiveError(`Could not download the backup: ${explain(out)}.`);
			const file = path.join(dir, 'devotion.db');
			await streamPipeline(createReadStream(gz), createGunzip(), createWriteStream(file)).catch(() => {
				throw new ArchiveError('The backup file is damaged (it could not be unpacked).', 422);
			});
			const problem = checkDatabase(file);
			if (problem) throw new ArchiveError(`This backup can't be restored: ${problem}.`, 422);
			// Same file system as the live database, so the swap at startup is a rename.
			const staged = path.join(this.dataDir, RESTORE_FILE);
			await streamPipeline(createReadStream(file), createWriteStream(staged, { mode: 0o600 }));
			this.log.info(`backup: ${name} is staged; restarting to restore it`);
			return { ok: true, name };
		} finally {
			this.running = false;
			await rm(dir, { recursive: true, force: true });
		}
	}

	/** Stops the process after the reply is sent; systemd (Restart=on-failure) starts it again. */
	restartSoon() {
		setTimeout(() => this.exit(75), 500).unref?.();
	}
}
