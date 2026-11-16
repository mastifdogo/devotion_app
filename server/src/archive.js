// Keeps the server small: entries older than the configured number of days
// are written to an SMB share (a text file and the MP3) and then reduced on
// the server to their title, date and summary.
//
// The share is reached with the smbclient program, which needs no mount and
// so works in an unprivileged container.
import { spawn } from 'node:child_process';
import { mkdtemp, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { now } from './db.js';

export class ArchiveError extends Error {
	constructor(message, status = 502) {
		super(message);
		this.name = 'ArchiveError';
		this.status = status;
	}
}

export function runCapture(cmd, args, { timeoutMs = 10 * 60 * 1000 } = {}) {
	return new Promise((resolve, reject) => {
		const child = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'] });
		let out = '';
		const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
		child.stdout.on('data', (d) => (out += d));
		child.stderr.on('data', (d) => (out += d));
		child.on('error', (err) => {
			clearTimeout(timer);
			reject(new ArchiveError(err.code === 'ENOENT' ? 'smbclient is not installed on this server.' : `Could not run smbclient: ${err.message}`, 500));
		});
		child.on('close', (code) => {
			clearTimeout(timer);
			resolve({ code, out });
		});
	});
}

/** A name that is safe as one SMB path component and inside smbclient's quotes. */
export function safeName(text, max = 60) {
	const cleaned = String(text).normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^A-Za-z0-9 ._-]+/g, ' ').replace(/\s+/g, ' ').trim().replace(/^\.+|[. ]+$/g, '');
	return cleaned.slice(0, max).trim() || 'untitled';
}

/** The folder setting as path components, each made safe. */
export function folderParts(folder) {
	return String(folder || '').split(/[\\/]+/).map((p) => p.trim()).filter((p) => p && p !== '.' && p !== '..').map((p) => safeName(p));
}

/** smbclient's errors are NT status names; say what the common ones mean. */
export function explain(out) {
	const status = /NT_STATUS_[A-Z_]+/.exec(out)?.[0];
	const known = {
		NT_STATUS_LOGON_FAILURE: 'the user name or password was not accepted',
		NT_STATUS_ACCESS_DENIED: 'that user may not write there',
		NT_STATUS_BAD_NETWORK_NAME: 'the server has no share with that name',
		NT_STATUS_HOST_UNREACHABLE: 'the server could not be reached',
		NT_STATUS_UNSUCCESSFUL: 'the server could not be reached',
		NT_STATUS_CONNECTION_REFUSED: 'the server refused the connection (is file sharing on?)',
		NT_STATUS_IO_TIMEOUT: 'the server did not answer in time',
		NT_STATUS_NOT_FOUND: 'the server name could not be found',
		NT_STATUS_DISK_FULL: 'the share is full',
	};
	if (status) return known[status] ? `${known[status]} (${status})` : status;
	return out.trim().split('\n').pop()?.slice(0, 200) || 'no answer from smbclient';
}

/**
 * Runs smbclient commands against the share named by the settings with this
 * prefix (smb_share, smb_username, ...). The password goes in an auth file
 * readable only by this user, never in the arguments.
 */
export async function smbRun(run, settings, prefix, commands) {
	const dir = await mkdtemp(path.join(tmpdir(), 'devotion-smb-'));
	try {
		const auth = path.join(dir, 'auth');
		const lines = [`username = ${settings.get(prefix + 'username') || 'guest'}`, `password = ${settings.get(prefix + 'password')}`];
		if (settings.get(prefix + 'domain')) lines.push(`domain = ${settings.get(prefix + 'domain')}`);
		await writeFile(auth, lines.join('\n') + '\n', { mode: 0o600 });
		return await run('smbclient', [settings.get(prefix + 'share'), '-A', auth, '-c', commands.join('; ')]);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
}

/** mkdir for each level; "already exists" is the normal case and not an error. */
export function mkdirs(parts) {
	return parts.map((_, i) => `mkdir "${parts.slice(0, i + 1).join('\\')}"`);
}

export function entryText(entry) {
	const head = [entry.kind === 'lesson' && entry.lesson_number ? `Lesson ${entry.lesson_number}: ${entry.title}` : entry.title, entry.entry_date, entry.scripture].filter(Boolean).join('\n');
	const source = [entry.source_subject && `Email: ${entry.source_subject}`, entry.source_from && `From: ${entry.source_from}`, entry.received_at && `Received: ${entry.received_at}`].filter(Boolean).join('\n');
	return [head, entry.summary && `Summary: ${entry.summary}`, entry.body, source].filter(Boolean).join('\n\n') + '\n';
}

export class Archiver {
	constructor({ db, settings, audioDir, runImpl = runCapture, log = console }) {
		Object.assign(this, { db, settings, audioDir, run: runImpl, log });
		this.running = false;
		this.status = { last_run: null, last_error: null, archived: 0 };
	}

	configured() {
		return this.settings.get('smb_share') !== '';
	}

	days() {
		return Math.max(1, Number(this.settings.get('archive_days')) || 30);
	}

	/** Runs smbclient commands against the share; the password never appears in the arguments. */
	smb(commands) {
		return smbRun(this.run, this.settings, 'smb_', commands);
	}

	mkdirs(parts) {
		return mkdirs(parts);
	}

	/** Checks the share can be reached and written to, for the admin page's button. */
	async test() {
		if (!this.configured()) throw new ArchiveError('Enter the share first, as //server/share.', 400);
		const parts = folderParts(this.settings.get('smb_folder'));
		const name = `devotion-test-${Date.now()}`;
		const probe = [...parts, name].join('\\');
		const { out } = await this.smb([...this.mkdirs(parts), `mkdir "${probe}"`, `ls "${probe}"`, `rmdir "${probe}"`]);
		// The share listing the folder just made (as a directory) is the proof;
		// mkdir's own output is not checked.
		if (!new RegExp(`^\\s+${name}\\s+D\\w*\\s+0\\s`, 'm').test(out)) throw new ArchiveError(`Could not write to the share: ${explain(out)}.`);
		return { ok: true, folder: parts.join('/') || '(top of the share)' };
	}

	due() {
		const cutoff = new Date(Date.now() - this.days() * 86400000).toISOString();
		return this.db.prepare('SELECT * FROM entries WHERE deleted_at IS NULL AND archived_at IS NULL AND COALESCE(received_at, created_at) < ? ORDER BY id').all(cutoff);
	}

	/** Archives every entry that is due. Returns how many were moved. */
	async runOnce() {
		if (!this.configured() || this.running) return 0;
		this.running = true;
		let moved = 0;
		try {
			for (const entry of this.due()) {
				await this.archiveEntry(entry);
				moved++;
			}
			this.status = { last_run: now(), last_error: null, archived: moved };
			return moved;
		} catch (err) {
			this.log.error('archive:', err.message);
			this.status = { last_run: now(), last_error: err.message, archived: moved };
			throw err;
		} finally {
			this.running = false;
		}
	}

	async archiveEntry(entry) {
		const date = (entry.received_at || entry.created_at).slice(0, 10);
		const parts = [...folderParts(this.settings.get('smb_folder')), date.slice(0, 4), date.slice(5, 7)];
		const label = entry.kind === 'lesson' && entry.lesson_number ? `Lesson ${entry.lesson_number} ${entry.title}` : entry.title;
		const base = `${date}_${entry.id}_${safeName(label, 50).replace(/ /g, '-')}`;
		const remoteDir = parts.join('\\');
		const dir = await mkdtemp(path.join(tmpdir(), 'devotion-archive-'));
		try {
			const text = path.join(dir, 'entry.txt');
			await writeFile(text, entryText(entry));
			const files = [{ local: text, remote: `${base}.txt`, size: (await stat(text)).size }];
			// Every voice the entry was made in, named after the voice.
			const audio = this.db.prepare('SELECT voice, file FROM entry_audio WHERE entry_id = ? AND file IS NOT NULL ORDER BY voice').all(entry.id);
			for (const a of audio) {
				const local = path.join(this.audioDir, path.basename(a.file));
				const info = await stat(local).catch(() => null);
				if (info) files.push({ local, remote: `${base}_${safeName(a.voice || 'audio', 40).replace(/ /g, '-')}.mp3`, size: info.size });
			}
			const { out } = await this.smb([
				...this.mkdirs(parts),
				...files.map((f) => `put "${f.local}" "${remoteDir}\\${f.remote}"`),
				`ls "${remoteDir}\\${base}*"`,
			]);
			// Nothing is removed from the server until the share lists each file at its full size.
			for (const f of files) {
				const listed = new RegExp(`^\\s+${f.remote.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s+\\w*\\s+(\\d+)\\s`, 'm').exec(out);
				if (!listed || Number(listed[1]) !== f.size) throw new ArchiveError(`Could not archive "${entry.title}": ${explain(out)}.`);
			}
			for (const a of audio) await rm(path.join(this.audioDir, path.basename(a.file)), { force: true });
			this.db.prepare('DELETE FROM entry_audio WHERE entry_id = ?').run(entry.id);
			const t = now();
			this.db.prepare("UPDATE entries SET body = '', archived_at = ?, archive_path = ?, updated_at = ? WHERE id = ?")
				.run(t, `${parts.join('/')}/${base}.txt`, t, entry.id);
			this.log.info(`archived entry ${entry.id} to ${parts.join('/')}/${base}`);
		} finally {
			await rm(dir, { recursive: true, force: true });
		}
	}
}
