// Text to an MP3 file. Two engines:
//   piper   a local neural voice (no account, nothing leaves the server);
//           piper writes WAV and lame encodes it.
//   openai  any service that speaks OpenAI's /v1/audio/speech API.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { currentPlan } from './resources.js';

export class TtsError extends Error {
	constructor(message) {
		super(message);
		this.name = 'TtsError';
	}
}

/** What the voice reads: the title, the reference, then the devotion. */
export function speechText(entry) {
	const parts = [];
	if (entry.kind === 'lesson' && entry.lesson_number) parts.push(`Lesson ${entry.lesson_number}.`);
	if (entry.kind === 'daily_reading' && /^\d{4}-\d{2}-\d{2}$/.test(entry.entry_date || '')) {
		// "Sunday, November 15." — the year adds nothing when listening.
		const day = new Date(entry.entry_date + 'T12:00:00Z');
		parts.push(day.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric', timeZone: 'UTC' }) + '.');
	}
	parts.push(entry.title.replace(/[.!?:]*$/, '') + '.');
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
			// Lowest CPU priority, so on one core the web pages and the app still answer.
			child = spawn('nice', ['-n', '19', cmd, ...args], { stdio: ['pipe', 'ignore', 'pipe'] });
		} catch (err) {
			reject(new TtsError(`Could not start ${cmd}: ${err.message}`));
			return;
		}
		let stderr = '';
		let timedOut = false;
		const timer = setTimeout(() => {
			timedOut = true;
			child.kill('SIGKILL');
		}, timeoutMs);
		child.stderr.on('data', (d) => (stderr = (stderr + d).slice(-2000)));
		child.on('error', (err) => {
			clearTimeout(timer);
			reject(new TtsError(err.code === 'ENOENT' ? `${cmd} is not installed on this server.` : `Could not run ${cmd}: ${err.message}`));
		});
		child.on('close', (code, signal) => {
			clearTimeout(timer);
			if (code === 0) resolve();
			else if (code === 127) reject(new TtsError(`${cmd} is not installed on this server.`));
			else if (signal === 'SIGKILL' && !timedOut) reject(new TtsError(`${cmd} was stopped for using too much memory. Give the container more memory (1 GB is recommended), then use "Retry failed audio".`));
			else reject(new TtsError(`${cmd} failed (${signal || 'exit ' + code}): ${stderr.trim().split('\n').pop() || 'no output'}`));
		});
		child.stdin.on('error', () => {}); // the close handler reports the failure
		child.stdin.end(input ?? '');
	});
}

/** A voice id for a speech-API voice: safe in a file name and a URL. */
export function openaiVoiceId(voice) {
	return 'api-' + (String(voice || 'default').toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'default');
}

export class Tts {
	constructor({ settings, audioDir, voices = null, fetchImpl = fetch, runImpl = run, planImpl = currentPlan }) {
		this.settings = settings;
		this.voices = voices;
		this.audioDir = audioDir;
		this.fetch = fetchImpl;
		this.run = runImpl;
		// However many places ask for audio (a mail check, a new voice, "Redo
		// audio"…), only as many voice engines run at once as the machine has
		// room for; the rest wait their turn.
		this.planImpl = planImpl;
		this.active = 0;
		this.waiters = [];
	}

	/** What this machine allows right now (re-read each time: a container can be resized while running). */
	plan() {
		return this.planImpl(this.provider());
	}

	/** How many syntheses may run at once. */
	jobs() {
		return Math.max(1, this.plan().audioJobs);
	}

	get waiting() {
		return this.waiters.length;
	}

	provider() {
		return this.settings.get('tts_provider');
	}

	/**
	 * The voices audio is made in: every Piper voice on the server, or the one
	 * voice of a speech API. Each is { id, name, description }.
	 */
	voiceList() {
		const p = this.provider();
		if (p === 'piper') return this.voices ? this.voices.installed() : this.settings.get('piper_model') ? [{ id: path.basename(this.settings.get('piper_model'), '.onnx'), name: 'Piper', description: '' }] : [];
		if (p === 'openai') {
			const voice = this.settings.get('tts_openai_voice');
			return [{ id: openaiVoiceId(voice), name: voice, description: 'Speech service' }];
		}
		return [];
	}

	/** Whether entries of this kind are read by the speech API while Piper is the engine. */
	premiumFor(kind) {
		const key = kind === 'lesson' ? 'premium_lessons' : 'premium_readings';
		return this.provider() === 'piper' && this.settings.get(key) === 'on' && this.settings.get('tts_openai_key') !== '';
	}

	/** Premium is on for lessons, daily readings, or both. */
	premiumLessons() {
		return this.premiumFor('lesson') || this.premiumFor('daily_reading');
	}

	/** What the premium voice is used for now, to notice a change: "", "lessons", "readings" or "lessons+readings", with the voice. */
	premiumState() {
		const kinds = [this.premiumFor('lesson') && 'lessons', this.premiumFor('daily_reading') && 'readings'].filter(Boolean);
		return kinds.length ? `${kinds.join('+')}:${this.premiumVoice().id}` : '';
	}

	premiumVoice() {
		const voice = this.settings.get('tts_openai_voice');
		return { id: openaiVoiceId(voice), name: `Premium (${voice})`, description: 'Speech service', lessons_only: true };
	}

	/** The voices this entry is made in: only the premium voice where that is on for its kind. */
	voicesFor(entry) {
		if (this.premiumFor(entry.kind)) return [this.premiumVoice()];
		return this.voiceList();
	}

	/** Every voice an entry may have audio in, for the app and the website to name them. */
	allVoices() {
		return this.premiumLessons() ? [...this.voiceList(), this.premiumVoice()] : this.voiceList();
	}

	/** The voice a listener hears by default for this entry, given the voices it has audio in. */
	preferredVoiceFor(entry, voiceIds) {
		if (this.premiumFor(entry.kind) && voiceIds.includes(this.premiumVoice().id)) return this.premiumVoice().id;
		return this.defaultVoiceId();
	}

	defaultVoiceId() {
		const p = this.provider();
		if (p === 'piper') return this.voices ? this.voices.defaultId() : this.voiceList()[0]?.id || '';
		if (p === 'openai') return this.voiceList()[0].id;
		return '';
	}

	/** Why audio can't be made right now, or null when it can. */
	notReady() {
		const p = this.provider();
		if (p === 'none') return 'Audio is turned off.';
		const plan = this.plan();
		if (plan.audioJobs === 0) return plan.note;
		if (p === 'piper') return this.voiceList().length ? null : 'No piper voice model is configured.';
		if (p === 'openai') return this.settings.get('tts_openai_key') ? null : 'No API key is set for the speech service.';
		return `Unknown speech engine "${p}".`;
	}

	/** Writes <audioDir>/<name>.mp3 in the given voice and returns { file, bytes, sha256 }. */
	synthesize(name, text, voiceId = this.defaultVoiceId()) {
		const start = () => {
			this.active++;
			return this.synthesizeNow(name, text, voiceId).finally(() => {
				this.active--;
				// The limit is read again here, so a resized container takes effect between files.
				while (this.waiters.length && this.active < this.jobs()) this.waiters.shift()();
			});
		};
		if (this.active < this.jobs()) return start();
		return new Promise((resolve, reject) => this.waiters.push(() => start().then(resolve, reject)));
	}

	async synthesizeNow(name, text, voiceId) {
		const blocked = this.notReady();
		if (blocked) throw new TtsError(blocked);
		await mkdir(this.audioDir, { recursive: true });
		const file = `${name}.mp3`;
		const final = path.join(this.audioDir, file);
		const tmp = final + '.part';
		try {
			const premium = this.premiumLessons() && voiceId === this.premiumVoice().id;
			if (this.provider() === 'piper' && !premium) await this.piper(text, tmp, voiceId);
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

	async piper(text, outFile, voiceId) {
		const wav = outFile + '.wav';
		// Piper speaks one line at a time; a paragraph per line keeps natural pauses.
		const lines = text.split(/\n+/).map((l) => l.trim()).filter(Boolean).join('\n') + '\n';
		const model = this.voices ? this.voices.pathFor(voiceId) : this.settings.get('piper_model');
		// Piper stretches time: above 1 is slower.
		const length = { slow: '1.18', normal: '1.0', fast: '0.87' }[this.settings.get('tts_rate')] || '1.0';
		await this.run(this.settings.get('piper_bin'), ['--model', model, '--length_scale', length, '--sentence_silence', '0.35', '--output_file', wav], { input: lines });
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
				body: JSON.stringify({ model: this.settings.get('tts_openai_model'), voice: this.settings.get('tts_openai_voice'), input: chunk, response_format: 'mp3', speed: { slow: 0.88, normal: 1, fast: 1.15 }[this.settings.get('tts_rate')] || 1 }),
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
