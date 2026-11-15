import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import path from 'node:path';

const SCHEMA = `
CREATE TABLE IF NOT EXISTS settings (
	key TEXT PRIMARY KEY,
	value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS messages (
	gmail_id TEXT PRIMARY KEY,
	subject TEXT,
	sender TEXT,
	received_at TEXT,
	status TEXT NOT NULL,            -- done | skipped | error
	error TEXT,
	attempts INTEGER NOT NULL DEFAULT 0,
	processed_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS entries (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	gmail_id TEXT,
	position INTEGER NOT NULL DEFAULT 0,
	title TEXT NOT NULL,
	entry_date TEXT,
	scripture TEXT,
	summary TEXT,
	body TEXT NOT NULL,
	source_from TEXT,
	source_subject TEXT,
	audio_file TEXT,
	audio_bytes INTEGER,
	audio_sha256 TEXT,
	audio_error TEXT,
	created_at TEXT NOT NULL,
	updated_at TEXT NOT NULL,
	deleted_at TEXT
);
CREATE INDEX IF NOT EXISTS entries_updated ON entries(updated_at);
CREATE TABLE IF NOT EXISTS profiles (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	name TEXT NOT NULL,
	name_key TEXT NOT NULL UNIQUE,   -- lower-cased, so "Maria" and "maria" are one person
	created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS annotations (
	profile_id INTEGER NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
	id TEXT NOT NULL,                -- made by the app (a UUID)
	entry_id INTEGER NOT NULL,
	start INTEGER NOT NULL,
	end INTEGER NOT NULL,
	color TEXT NOT NULL,
	comment TEXT NOT NULL,
	updated_at TEXT NOT NULL,        -- the device's clock: decides which edit wins
	deleted INTEGER NOT NULL DEFAULT 0,
	server_updated_at TEXT NOT NULL, -- this server's clock: what "since" compares
	PRIMARY KEY (profile_id, id)
);
CREATE INDEX IF NOT EXISTS annotations_changed ON annotations(profile_id, server_updated_at);
CREATE TABLE IF NOT EXISTS bibles (
	id TEXT PRIMARY KEY,
	name TEXT NOT NULL,
	language TEXT NOT NULL,
	bytes INTEGER NOT NULL,
	sha256 TEXT NOT NULL,
	installed_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS entries_gmail ON entries(gmail_id);
`;

export function openDb(dataDir) {
	mkdirSync(dataDir, { recursive: true, mode: 0o750 });
	const db = new DatabaseSync(dataDir === ':memory:' ? ':memory:' : path.join(dataDir, 'devotion.db'));
	db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');
	db.exec(SCHEMA);
	// Columns added after the first release; a database made by an older
	// version gets them here.
	const have = new Set(db.prepare('PRAGMA table_info(entries)').all().map((c) => c.name));
	for (const [column, type] of [['received_at', 'TEXT'], ['archived_at', 'TEXT'], ['archive_path', 'TEXT'], ['refs', 'TEXT']]) {
		if (!have.has(column)) db.exec(`ALTER TABLE entries ADD COLUMN ${column} ${type}`);
	}
	return db;
}

// Millisecond ISO timestamps sort correctly as text, which `since` relies on.
export const now = () => new Date().toISOString();
