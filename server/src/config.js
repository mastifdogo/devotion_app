// Process-level configuration, from the environment. Everything an admin
// changes at runtime (keys, Gmail query, voices) lives in the settings table.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));

function readVersion() {
	// A release tarball carries VERSION at its root; a checkout falls back to package.json.
	for (const candidate of [path.join(here, '../../VERSION'), path.join(here, '../VERSION')]) {
		try {
			const v = readFileSync(candidate, 'utf8').trim();
			if (v) return v.replace(/^v/, '');
		} catch {}
	}
	return JSON.parse(readFileSync(path.join(here, '../package.json'), 'utf8')).version;
}

export function loadConfig(env = process.env) {
	return {
		version: readVersion(),
		port: Number(env.PORT || 8787),
		host: env.HOST || '0.0.0.0',
		dataDir: path.resolve(env.DATA_DIR || path.join(here, '../data')),
		// Optional: fixes the admin password from the environment. Without it the
		// password is chosen in the browser on the first visit.
		adminPassword: env.ADMIN_PASSWORD || '',
		githubRepo: env.GITHUB_REPO || 'mastifdogo/devotion_app',
		// Set by the packaged install; without it "install update" is not offered.
		updateRequestFile: env.UPDATE_REQUEST_FILE || '',
		env,
	};
}
