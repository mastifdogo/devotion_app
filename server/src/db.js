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
CREATE INDEX IF NOT EXISTS entries_gmail ON entries(gmail_id);
`;

export function openDb(dataDir) {
	mkdirSync(dataDir, { recursive: true, mode: 0o750 });
	const db = new DatabaseSync(dataDir === ':memory:' ? ':memory:' : path.join(dataDir, 'devotion.db'));
	db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');
	db.exec(SCHEMA);
	return db;
}

// Millisecond ISO timestamps sort correctly as text, which `since` relies on.
export const now = () => new Date().toISOString();
