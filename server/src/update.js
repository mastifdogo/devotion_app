// Manual update check against the GitHub repository's releases. Nothing here
// runs on a schedule: GitHub is asked only when an admin presses the button.
import { writeFile } from 'node:fs/promises';

export class UpdateError extends Error {
	constructor(message, status = 502) {
		super(message);
		this.name = 'UpdateError';
		this.status = status;
	}
}

export const TAG_RE = /^v?\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$/;

/** -1, 0 or 1. A version with a suffix (1.2.0-rc.1) is older than the same without. */
export function compareVersions(a, b) {
	const parse = (v) => {
		const [core, ...pre] = String(v).trim().replace(/^v/, '').split('-');
		return { nums: core.split('.').map((n) => parseInt(n, 10) || 0), pre: pre.join('-') };
	};
	const x = parse(a);
	const y = parse(b);
	for (let i = 0; i < Math.max(x.nums.length, y.nums.length); i++) {
		const d = (x.nums[i] || 0) - (y.nums[i] || 0);
		if (d !== 0) return d < 0 ? -1 : 1;
	}
	if (x.pre === y.pre) return 0;
	if (!x.pre) return 1;
	if (!y.pre) return -1;
	return x.pre < y.pre ? -1 : 1;
}

export class Updater {
	constructor({ config, settings, fetchImpl = fetch }) {
		this.config = config;
		this.settings = settings;
		this.fetch = fetchImpl;
	}

	canInstall() {
		return Boolean(this.config.updateRequestFile);
	}

	async check() {
		const { githubRepo, version } = this.config;
		const githubToken = this.settings.get('github_token');
		const headers = { accept: 'application/vnd.github+json', 'x-github-api-version': '2022-11-28', 'user-agent': 'devotion-server' };
		// The token goes to GitHub's API host only.
		if (githubToken) headers.authorization = `Bearer ${githubToken}`;
		let res;
		try {
			res = await this.fetch(`https://api.github.com/repos/${githubRepo}/releases/latest`, { headers });
		} catch (err) {
			throw new UpdateError(`Could not reach GitHub: ${err.cause?.code || err.message}`);
		}
		if (res.status === 401) throw new UpdateError('GitHub rejected the saved GitHub token. Save a valid one, or clear it if the repository is public.', 401);
		if (res.status === 404) {
			throw new UpdateError(githubToken ? `${githubRepo} has no published release yet.` : `${githubRepo} has no published release yet, or it is a private repository (then save a GitHub token under Updates).`, 404);
		}
		if (res.status === 403 || res.status === 429) {
			const remaining = res.headers.get('x-ratelimit-remaining');
			const reset = Number(res.headers.get('x-ratelimit-reset'));
			if (remaining === '0' && reset) {
				const at = new Date(reset * 1000);
				throw new UpdateError(`GitHub's hourly limit for this address is used up. Try again after ${at.toISOString().slice(11, 16)} UTC${githubToken ? '' : ', or save a GitHub token for a much higher limit'}.`, 429);
			}
			throw new UpdateError(`GitHub refused the request (${res.status}).`);
		}
		if (!res.ok) throw new UpdateError(`GitHub answered ${res.status}.`);
		const release = await res.json();
		const latest = String(release.tag_name || '');
		if (!TAG_RE.test(latest)) throw new UpdateError(`The latest release has an unexpected tag: "${latest}".`);
		return {
			current: version,
			latest: latest.replace(/^v/, ''),
			tag: latest,
			newer: compareVersions(latest, version) > 0,
			notes: release.body || '',
			url: release.html_url || '',
			published_at: release.published_at || null,
			can_install: this.canInstall(),
		};
	}

	/**
	 * Hands the tag to the root-owned updater (devotion-update.path watches this
	 * file). The server itself never downloads or replaces code.
	 */
	async requestInstall(tag) {
		if (!this.canInstall()) throw new UpdateError('This server was not installed from a release, so it cannot update itself. Update the checkout instead.', 400);
		if (!TAG_RE.test(tag)) throw new UpdateError('Not a release tag.', 400);
		// Second line: the optional GitHub token, which the updater needs to
		// download from a private repository.
		const token = this.settings.get('github_token');
		await writeFile(this.config.updateRequestFile, tag + '\n' + (/^[A-Za-z0-9_]+$/.test(token) ? token + '\n' : ''), { mode: 0o600 });
	}
}
