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

/** A voice id for a voice of the local speech server. */
export function localVoiceId(voice) {
	return 'local-' + (String(voice || 'default').toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'default');
}

const ENGINES = ['piper', 'openai', 'local', 'none'];

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

	/** The engine's own voices on this server (Piper). */
	piperVoices() {
		return this.voices ? this.voices.installed() : this.settings.get('piper_model') ? [{ id: path.basename(this.settings.get('piper_model'), '.onnx'), name: 'Piper', description: '' }] : [];
	}

	/**
	 * What was chosen to read this kind of text: 'piper', 'openai' (a speech
	 * API on the internet), 'local' (a speech server on the owner's network
	 * that speaks the same API) or 'none'. kind: 'lesson', 'daily_reading' or 'bible'.
	 * Servers set up before there was a choice per kind keep what they had.
	 */
	chosenEngine(kind) {
		const chosen = this.settings.get(kind === 'bible' ? 'bible_engine' : kind === 'lesson' ? 'lessons_engine' : 'readings_engine');
		if (ENGINES.includes(chosen)) return chosen;
		if (kind === 'bible') return 'none';
		const old = this.settings.get('tts_provider');
		if (old !== 'piper') return old === 'openai' ? 'openai' : 'none';
		return this.settings.get(kind === 'lesson' ? 'premium_lessons' : 'premium_readings') === 'on' ? 'openai' : 'piper';
	}

	/** Whether a speech API has what it needs to be called. */
	apiReady(engine) {
		return engine === 'local' ? this.settings.get('tts_local_base_url') !== '' : this.settings.get('tts_openai_key') !== '';
	}

	/** The engine actually used: a speech API that is not set up yet leaves the text with Piper. */
	engineFor(kind) {
		const chosen = this.chosenEngine(kind);
		if ((chosen === 'openai' || chosen === 'local') && !this.apiReady(chosen)) return this.settings.get('tts_provider') === 'piper' ? 'piper' : chosen;
		return chosen;
	}

	/** The engines entries are read by (the Bible's is separate: it is made on demand). */
	entryEngines() {
		return [...new Set([this.engineFor('daily_reading'), this.engineFor('lesson')])].filter((e) => e !== 'none');
	}

	/** For the memory plan: Piper if anything runs on this server, else a speech service, else nothing. */
	provider() {
		const used = [...this.entryEngines(), this.engineFor('bible')];
		return used.includes('piper') ? 'piper' : used.some((e) => e === 'openai' || e === 'local') ? 'openai' : 'none';
	}

	/** The one voice of a speech API, as the app and the website see it. */
	apiVoice(engine) {
		const local = engine === 'local';
		const voice = this.settings.get(local ? 'tts_local_voice' : 'tts_openai_voice');
		const mixed = this.entryEngines().includes('piper');
		const out = { id: local ? localVoiceId(voice) : openaiVoiceId(voice), name: local ? `Local (${voice})` : mixed ? `Premium (${voice})` : voice, description: local ? 'Speech server on this network' : 'Speech service' };
		// Not one of the voices a listener can choose between: it belongs to a kind of entry.
		if (mixed) out.lessons_only = true;
		return out;
	}

	/** Kept for older callers: the premium (speech API) voice. */
	premiumVoice() {
		return this.apiVoice('openai');
	}

	/** The voices this entry is made in: every Piper voice, or the one voice of its speech API. */
	voicesFor(entry) {
		const engine = this.engineFor(entry.kind === 'lesson' ? 'lesson' : 'daily_reading');
		if (engine === 'piper') return this.piperVoices();
		return engine === 'none' ? [] : [this.apiVoice(engine)];
	}

	/** Every voice an entry may have audio in, for the app and the website to name them. */
	allVoices() {
		const engines = this.entryEngines();
		return [...(engines.includes('piper') ? this.piperVoices() : []), ...engines.filter((e) => e !== 'piper').map((e) => this.apiVoice(e))];
	}

	voiceList() {
		return this.allVoices();
	}

	/** What changed when the engines or their voices change, to notice it: "piper|api-nova". */
	engineState() {
		return ['daily_reading', 'lesson'].map((k) => { const e = this.engineFor(k); return e === 'openai' || e === 'local' ? this.apiVoice(e).id : e; }).join('|');
	}

	/** The speech API voice this kind of entry is read by, or null when it is read by Piper's voices. */
	pinnedVoiceFor(entry) {
		const engine = this.engineFor(entry.kind === 'lesson' ? 'lesson' : 'daily_reading');
		return engine === 'openai' || engine === 'local' ? this.apiVoice(engine).id : null;
	}

	/** The voice a listener hears by default for this entry, given the voices it has audio in. */
	preferredVoiceFor(entry, voiceIds) {
		const engine = this.engineFor(entry.kind === 'lesson' ? 'lesson' : 'daily_reading');
		if ((engine === 'openai' || engine === 'local') && voiceIds.includes(this.apiVoice(engine).id)) return this.apiVoice(engine).id;
		return this.defaultVoiceId();
	}

	/** The server's default voice: Piper's chosen one, or the speech API's when nothing is read by Piper. */
	defaultVoiceId() {
		const engines = this.entryEngines();
		if (engines.includes('piper')) return this.voices ? this.voices.defaultId() : this.piperVoices()[0]?.id || '';
		return engines.length ? this.apiVoice(engines[0]).id : '';
	}

	/** The voice the Bible is read in, or null when Bible audio is off. */
	bibleVoiceId() {
		const engine = this.engineFor('bible');
		if (engine === 'none') return null;
		if (engine === 'piper') return (this.voices ? this.voices.defaultId() : this.piperVoices()[0]?.id) || null;
		return this.apiVoice(engine).id;
	}

	/** Why audio can't be made right now, or null when it can. */
	notReady() {
		const engines = this.entryEngines();
		if (!engines.length) return 'Audio is turned off.';
		const plan = this.plan();
		if (plan.audioJobs === 0) return plan.note;
		if (engines.includes('piper') && !this.piperVoices().length) return 'No piper voice model is configured.';
		if (engines.includes('openai') && !this.apiReady('openai')) return 'No API key is set for the speech service.';
		if (engines.includes('local') && !this.apiReady('local')) return 'No address is set for the local speech server.';
		return null;
	}

	/** Writes <dir>/<name>.mp3 (dir: the entries' audio folder unless given) in the given voice and returns { file, bytes, sha256 }. */
	synthesize(name, text, voiceId = this.defaultVoiceId(), dir = this.audioDir) {
		const start = () => {
			this.active++;
			return this.synthesizeNow(name, text, voiceId, dir).finally(() => {
				this.active--;
				// The limit is read again here, so a resized container takes effect between files.
				while (this.waiters.length && this.active < this.jobs()) this.waiters.shift()();
			});
		};
		if (this.active < this.jobs()) return start();
		return new Promise((resolve, reject) => this.waiters.push(() => start().then(resolve, reject)));
	}

	/** Which engine makes this voice: a speech API's own voice id, else Piper. */
	engineOfVoice(voiceId) {
		for (const engine of ['local', 'openai']) {
			if (this.apiReady(engine) && voiceId === this.apiVoice(engine).id) return engine;
		}
		return 'piper';
	}

	async synthesizeNow(name, text, voiceId, dir = this.audioDir) {
		const engine = this.engineOfVoice(voiceId);
		if (engine === 'piper') {
			const plan = this.planImpl('piper');
			if (plan.audioJobs === 0) throw new TtsError(plan.note);
			if (!this.piperVoices().length) throw new TtsError('No piper voice model is configured.');
		}
		await mkdir(dir, { recursive: true });
		const file = `${name}.mp3`;
		const final = path.join(dir, file);
		const tmp = final + '.part';
		try {
			if (engine === 'piper') await this.piper(text, tmp, voiceId);
			else await this.speechApi(engine, text, tmp);
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

	/** OpenAI's /audio/speech API: the service itself ('openai'), or a server on the owner's network that speaks it ('local'). */
	async speechApi(engine, text, outFile) {
		const k = engine === 'local' ? 'tts_local_' : 'tts_openai_';
		const base = this.settings.get(k + 'base_url').replace(/\/+$/, '');
		const key = this.settings.get(k + 'key');
		const what = engine === 'local' ? 'local speech server' : 'speech service';
		const buffers = [];
		for (const chunk of chunkText(text, 3800)) {
			let res;
			try {
				res = await this.fetch(`${base}/audio/speech`, {
					method: 'POST',
					headers: { ...(key ? { authorization: `Bearer ${key}` } : {}), 'content-type': 'application/json' },
					body: JSON.stringify({ model: this.settings.get(k + 'model'), voice: this.settings.get(k + 'voice'), input: chunk, response_format: 'mp3', speed: { slow: 0.88, normal: 1, fast: 1.15 }[this.settings.get('tts_rate')] || 1 }),
					// A local model on a small machine can take minutes for a long chapter.
					signal: AbortSignal.timeout(engine === 'local' ? 15 * 60 * 1000 : 5 * 60 * 1000),
				});
			} catch (err) {
				throw new TtsError(`Could not reach the ${what} at ${base}: ${err.cause?.code || err.message}`);
			}
			if (!res.ok) {
				const detail = await res.text().catch(() => '');
				throw new TtsError(`The ${what} answered ${res.status}: ${detail.slice(0, 300)}`);
			}
			buffers.push(Buffer.from(await res.arrayBuffer()));
		}
		// MP3 is a stream of independent frames, so parts can simply follow each other.
		await writeFile(outFile, Buffer.concat(buffers));
	}
}
