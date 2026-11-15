// Text to an MP3 file. Two engines:
//   piper   a local neural voice (no account, nothing leaves the server);
//           piper writes WAV and lame encodes it.
//   openai  any service that speaks OpenAI's /v1/audio/speech API.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';

export class TtsError extends Error {
	constructor(message) {
		super(message);
		this.name = 'TtsError';
	}
}

/** What the voice reads: the title, the reference, then the devotion. */
export function speechText(entry) {
	const parts = [entry.title.replace(/[.!?:]*$/, '') + '.'];
	if (entry.scripture) parts.push(entry.scripture.replace(/[.]*$/, '') + '.');
	parts.push(entry.body);
	return parts.join('\n\n');
}

/**
 * Splits text into pieces of at most `max` characters, at paragraph ends where
 * possible, then sentence ends, then spaces. Speech APIs cap the input length.
 */
export function chunkText(text, max) {
	const chunks = [];
	let current = '';
	const push = (piece, joiner) => {
		if (piece.length > max) {
			if (current) chunks.push(current), (current = '');
			const sentences = piece.match(/[^.!?]+[.!?]+["')\]]*\s*|[^.!?]+$/g) || [piece];
			if (sentences.length > 1) {
				for (const s of sentences) push(s.trim(), ' ');
			} else {
				let rest = piece;
				while (rest.length > max) {
					let cut = rest.lastIndexOf(' ', max);
					if (cut <= 0) cut = max;
					chunks.push(rest.slice(0, cut).trim());
					rest = rest.slice(cut).trim();
				}
				current = rest;
			}
			return;
		}
		if (!piece) return;
		if (current && current.length + joiner.length + piece.length > max) chunks.push(current), (current = piece);
		else current = current ? current + joiner + piece : piece;
	};
	for (const para of text.split(/\n{2,}/)) push(para.trim(), '\n\n');
	if (current) chunks.push(current);
	return chunks;
}

function run(cmd, args, { input, timeoutMs = 15 * 60 * 1000 } = {}) {
	return new Promise((resolve, reject) => {
		let child;
		try {
			child = spawn(cmd, args, { stdio: ['pipe', 'ignore', 'pipe'] });
		} catch (err) {
			reject(new TtsError(`Could not start ${cmd}: ${err.message}`));
			return;
		}
		let stderr = '';
		const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
		child.stderr.on('data', (d) => (stderr = (stderr + d).slice(-2000)));
		child.on('error', (err) => {
			clearTimeout(timer);
			reject(new TtsError(err.code === 'ENOENT' ? `${cmd} is not installed on this server.` : `Could not run ${cmd}: ${err.message}`));
		});
		child.on('close', (code, signal) => {
			clearTimeout(timer);
			if (code === 0) resolve();
			else reject(new TtsError(`${cmd} failed (${signal || 'exit ' + code}): ${stderr.trim().split('\n').pop() || 'no output'}`));
		});
		child.stdin.on('error', () => {}); // the close handler reports the failure
		child.stdin.end(input ?? '');
	});
}

export class Tts {
	constructor({ settings, audioDir, fetchImpl = fetch, runImpl = run }) {
		this.settings = settings;
		this.audioDir = audioDir;
		this.fetch = fetchImpl;
		this.run = runImpl;
	}

	provider() {
		return this.settings.get('tts_provider');
	}

	/** Why audio can't be made right now, or null when it can. */
	notReady() {
		const p = this.provider();
		if (p === 'none') return 'Audio is turned off.';
		if (p === 'piper') return this.settings.get('piper_model') ? null : 'No piper voice model is configured.';
		if (p === 'openai') return this.settings.get('tts_openai_key') ? null : 'No API key is set for the speech service.';
		return `Unknown speech engine "${p}".`;
	}

	/** Writes <audioDir>/<name>.mp3 and returns { file, bytes, sha256 }. */
	async synthesize(name, text) {
		const blocked = this.notReady();
		if (blocked) throw new TtsError(blocked);
		await mkdir(this.audioDir, { recursive: true });
		const file = `${name}.mp3`;
		const final = path.join(this.audioDir, file);
		const tmp = final + '.part';
		try {
			if (this.provider() === 'piper') await this.piper(text, tmp);
			else await this.openai(text, tmp);
			const data = await readFile(tmp);
			if (data.length < 256) throw new TtsError('The speech engine produced an empty file.');
			await rename(tmp, final);
			return { file, bytes: data.length, sha256: createHash('sha256').update(data).digest('hex') };
		} finally {
			await rm(tmp, { force: true });
			await rm(tmp + '.wav', { force: true });
		}
	}

	async piper(text, outFile) {
		const wav = outFile + '.wav';
		// Piper speaks one line at a time; a paragraph per line keeps natural pauses.
		const lines = text.split(/\n+/).map((l) => l.trim()).filter(Boolean).join('\n') + '\n';
		await this.run(this.settings.get('piper_bin'), ['--model', this.settings.get('piper_model'), '--sentence_silence', '0.35', '--output_file', wav], { input: lines });
		await stat(wav).catch(() => {
			throw new TtsError('piper finished without writing audio.');
		});
		await this.run('lame', ['--quiet', '-m', 'm', '-V', '6', wav, outFile]);
	}

	async openai(text, outFile) {
		const base = this.settings.get('tts_openai_base_url').replace(/\/+$/, '');
		const buffers = [];
		for (const chunk of chunkText(text, 3800)) {
			const res = await this.fetch(`${base}/audio/speech`, {
				method: 'POST',
				headers: { authorization: `Bearer ${this.settings.get('tts_openai_key')}`, 'content-type': 'application/json' },
				body: JSON.stringify({ model: this.settings.get('tts_openai_model'), voice: this.settings.get('tts_openai_voice'), input: chunk, response_format: 'mp3' }),
			});
			if (!res.ok) {
				const detail = await res.text().catch(() => '');
				throw new TtsError(`The speech service answered ${res.status}: ${detail.slice(0, 300)}`);
			}
			buffers.push(Buffer.from(await res.arrayBuffer()));
		}
		// MP3 is a stream of independent frames, so parts can simply follow each other.
		await writeFile(outFile, Buffer.concat(buffers));
	}
}
