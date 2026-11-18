#!/usr/bin/env node
// Writes the Bible version the Android app carries with it, so the app has a
// Bible before it has ever reached a server. It is the same text, from the same
// pinned and checksummed download, in the same form, as the server keeps and
// serves, so highlights and audio line up between the two.
//
//   node server/scripts/app-bible.js            writes android/app/src/main/assets/bibles/kjv.json
//   node server/scripts/app-bible.js --check    fails unless that file is exactly what would be written
//
// The file is committed; run this again only when the catalogue's entry changes.
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { CATALOG, download } from '../src/bibles.js';

const ID = 'kjv';
const out = path.join(path.dirname(fileURLToPath(import.meta.url)), '../../android/app/src/main/assets/bibles', `${ID}.json`);
const data = await download(CATALOG.find((c) => c.id === ID));
if (process.argv.includes('--check')) {
	const have = await readFile(out).catch(() => null);
	if (!have || !have.equals(data)) {
		console.error(`${out} is not the ${ID} version of the catalogue: run node server/scripts/app-bible.js`);
		process.exit(1);
	}
	console.log(`${out} is up to date (${data.length} bytes).`);
} else {
	await mkdir(path.dirname(out), { recursive: true });
	await writeFile(out, data);
	console.log(`${out}: ${data.length} bytes`);
}
