#!/usr/bin/env node
import path from 'node:path';
import { loadConfig } from './config.js';
import { openDb } from './db.js';
import { Settings } from './settings.js';
import { Gmail } from './gmail.js';
import { Extractor } from './extract.js';
import { Tts } from './tts.js';
import { Pipeline } from './pipeline.js';
import { Updater } from './update.js';
import { Voices } from './voices.js';
import { Bibles } from './bibles.js';
import { Archiver } from './archive.js';
import { Wallpapers } from './wallpapers.js';
import { Backups, takePendingRestore } from './backup.js';
import { BibleAudio } from './bibleaudio.js';
import { createServer } from './server.js';

const config = loadConfig();
// A backup restored from the admin page is swapped in before anything opens the database.
if (config.dataDir !== ':memory:') takePendingRestore(config.dataDir);
const db = openDb(config.dataDir);
const settings = new Settings(db, config.env);

if (process.argv[2] === 'reset-admin-password') {
	// The way back in when the password is forgotten: the next visit to the
	// admin page asks for a new one.
	settings.set('admin_password_hash', '');
	console.log('The admin password is cleared. Open the admin page to choose a new one.');
	db.close();
	process.exit(0);
}
const audioDir = path.join(config.dataDir, 'audio');
const gmail = new Gmail({ settings, port: config.port });
const extractor = new Extractor({ settings });
const voices = new Voices({ settings, dataDir: config.dataDir });
const bibles = new Bibles({ db, dataDir: config.dataDir });
const tts = new Tts({ settings, audioDir, voices });
const archiver = new Archiver({ db, settings, audioDir });
const wallpapers = new Wallpapers({ db, dataDir: config.dataDir });
const backups = new Backups({ db, settings, dataDir: config.dataDir });
const bibleAudio = new BibleAudio({ bibles, tts, settings, dataDir: config.dataDir });
const pipeline = new Pipeline({ db, settings, gmail, extractor, tts, audioDir, archiver });
const updater = new Updater({ config, settings });
const server = createServer({ config, db, settings, gmail, pipeline, tts, updater, voices, bibles, archiver, wallpapers, backups, bibleAudio, audioDir });

pipeline.adoptLegacyAudio();
pipeline.reclassify();

server.listen(config.port, config.host, () => {
	console.log(`devotion-server ${config.version} listening on http://${config.host}:${config.port} (data: ${config.dataDir})`);
	pipeline.start();
	backups.start();
	// The whole Bible was being read when the server stopped: carry on.
	bibleAudio.resumeReadAll();
});

for (const signal of ['SIGTERM', 'SIGINT']) {
	process.on(signal, () => {
		pipeline.stop();
		backups.stop();
		bibleAudio.interruptReadAll();
		server.close(() => {
			db.close();
			process.exit(0);
		});
		server.closeAllConnections?.();
	});
}
