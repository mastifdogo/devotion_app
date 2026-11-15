// The voices the admin page offers for Piper. One ships with the server; the
// others are downloaded on request into the data directory. Every file is
// pinned by SHA-256, so a voice is exactly the one that was listed here.
import { createHash } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { access, mkdir, rename, rm, readFile, writeFile } from 'node:fs/promises';
import { Readable } from 'node:stream';
import { pipeline as pipe } from 'node:stream/promises';
import path from 'node:path';

const BASE = 'https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0';

export const VOICES = [
	{ id: 'en_US-lessac-medium', name: 'Lessac', description: 'American English, female (installed with the server)', dir: 'en/en_US/lessac/medium', sha256: '5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f', jsonSha256: 'efe19c417bed055f2d69908248c6ba650fa135bc868b0e6abb3da181dab690a0' },
	{ id: 'en_US-amy-medium', name: 'Amy', description: 'American English, female', dir: 'en/en_US/amy/medium', sha256: 'b3a6e47b57b8c7fbe6a0ce2518161a50f59a9cdd8a50835c02cb02bdd6206c18', jsonSha256: '95a23eb4d42909d38df73bb9ac7f45f597dbfcde2d1bf9526fdeaf5466977d77' },
	{ id: 'en_US-hfc_female-medium', name: 'HFC', description: 'American English, female, calm', dir: 'en/en_US/hfc_female/medium', sha256: '914c473788fc1fa8b63ace1cdcdb44588f4ae523d3ab37df1536616835a140b7', jsonSha256: '03f1fa0622b80463283592d97aca9f6e89aec345a5c56b7257723e0093c58b6c' },
	{ id: 'en_US-ryan-medium', name: 'Ryan', description: 'American English, male', dir: 'en/en_US/ryan/medium', sha256: 'abf4c274862564ed647ba0d2c47f8ee7c9b717d27bdad9219100eb310db4047a', jsonSha256: '44034c056cb15681b2ad494307c7f3f2e4499d1253c700c711fa0a4607ffe78d' },
	{ id: 'en_US-joe-medium', name: 'Joe', description: 'American English, male', dir: 'en/en_US/joe/medium', sha256: '58afce0321b8d9c46d7cdf9c16500cc55a793b4220212dba6b70fb788b3baf06', jsonSha256: '3d6d5410b3795cb1950595247ef8f06190719e6fdbfa3a2356d8ec368e1aad33' },
	{ id: 'en_US-hfc_male-medium', name: 'HFC', description: 'American English, male, calm', dir: 'en/en_US/hfc_male/medium', sha256: 'd11e403a02bdf5a670c877b3dc56e0e1c8cece6fb30289586314dffdc0a78cb0', jsonSha256: 'f66847424aed0bf99ecbb5d7cfde47c0a906f426a0daf7c46f305e7d21afd886' },
	{ id: 'en_GB-alan-medium', name: 'Alan', description: 'British English, male', dir: 'en/en_GB/alan/medium', sha256: '0a309668932205e762801f1efc2736cd4b0120329622adf62be09e56339d3330', jsonSha256: 'c0f0d124e5895c00e7c03b35dcc8287f319a6998a365b182deb5c8e752ee8c1e' },
	{ id: 'en_GB-jenny_dioco-medium', name: 'Jenny', description: 'British English, female', dir: 'en/en_GB/jenny_dioco/medium', sha256: '469c630d209e139dd392a66bf4abde4ab86390a0269c1e47b4e5d7ce81526b01', jsonSha256: 'a9a7a93a317c9a3cb6563e37eb057df9ef09c06188a8a4341b0fcb58cba54dd4' },
	{ id: 'en_GB-northern_english_male-medium', name: 'Northern', description: 'Northern English, male', dir: 'en/en_GB/northern_english_male/medium', sha256: '57a219ae8e638873db7d18893304be5069c42868f392bb95c3ff17f0690d0689', jsonSha256: '69557ed3d974463453e9b0c09dd99a7ed0e52b8b87b64b357dbeeb2540a97d47' },
	{ id: 'es_ES-davefx-medium', name: 'Dave', description: 'Spanish (Spain), male', dir: 'es/es_ES/davefx/medium', sha256: '6658b03b1a6c316ee4c265a9896abc1393353c2d9e1bca7d66c2c442e222a917', jsonSha256: '0e0dda87c732f6f38771ff274a6380d9252f327dca77aa2963d5fbdf9ec54842' },
];

export class VoiceError extends Error {
	constructor(message, status = 400) {
		super(message);
		this.name = 'VoiceError';
		this.status = status;
	}
}

const exists = (p) => access(p).then(() => true, () => false);

export class Voices {
	constructor({ settings, dataDir, fetchImpl = fetch, catalog = VOICES }) {
		this.settings = settings;
		this.catalog = catalog;
		this.dir = path.join(dataDir, 'voices');
		this.fetch = fetchImpl;
		this.busy = new Set();
	}

	file(id) {
		return path.join(this.dir, `${id}.onnx`);
	}

	/** The model file Piper should use right now. */
	async modelPath() {
		const chosen = this.settings.get('piper_voice');
		if (chosen && (await exists(this.file(chosen)))) return this.file(chosen);
		return this.settings.get('piper_model');
	}

	async list() {
		const builtIn = path.basename(this.settings.get('piper_model'), '.onnx');
		const chosen = this.settings.get('piper_voice') || builtIn;
		return Promise.all(this.catalog.map(async (v) => {
			const installed = v.id === builtIn || (await exists(this.file(v.id)));
			return { id: v.id, name: v.name, description: v.description, installed, built_in: v.id === builtIn, downloading: this.busy.has(v.id), selected: installed && v.id === chosen };
		}));
	}

	/** Makes a voice the one in use, downloading it first if needed. */
	async select(id) {
		const voice = this.catalog.find((v) => v.id === id);
		if (!voice) throw new VoiceError('No such voice.', 404);
		const builtIn = path.basename(this.settings.get('piper_model'), '.onnx');
		if (id !== builtIn && !(await exists(this.file(id)))) await this.download(voice);
		this.settings.set('piper_voice', id === builtIn ? '' : id);
	}

	async download(voice) {
		if (this.busy.has(voice.id)) throw new VoiceError('This voice is already being downloaded.', 409);
		this.busy.add(voice.id);
		const tmp = this.file(voice.id) + '.part';
		try {
			await mkdir(this.dir, { recursive: true });
			const url = `${BASE}/${voice.dir}/${voice.id}.onnx`;
			const [model, config] = await Promise.all([this.fetch(url), this.fetch(url + '.json')]).catch((err) => {
				throw new VoiceError(`Could not reach the voice download site: ${err.cause?.code || err.message}`, 502);
			});
			if (!model.ok || !config.ok) throw new VoiceError(`The voice download site answered ${model.ok ? config.status : model.status}.`, 502);
			const json = Buffer.from(await config.arrayBuffer());
			if (createHash('sha256').update(json).digest('hex') !== voice.jsonSha256) throw new VoiceError('The voice\'s settings file does not match its checksum.', 502);
			const hash = createHash('sha256');
			await pipe(Readable.fromWeb(model.body), async function* (source) {
				for await (const chunk of source) {
					hash.update(chunk);
					yield chunk;
				}
			}, createWriteStream(tmp));
			if (hash.digest('hex') !== voice.sha256) throw new VoiceError('The downloaded voice does not match its checksum.', 502);
			await writeFile(this.file(voice.id) + '.json', json);
			await rename(tmp, this.file(voice.id));
		} finally {
			this.busy.delete(voice.id);
			await rm(tmp, { force: true });
		}
	}

	async remove(id) {
		if (!this.catalog.some((v) => v.id === id)) throw new VoiceError('No such voice.', 404);
		if (this.settings.get('piper_voice') === id) this.settings.set('piper_voice', '');
		await rm(this.file(id), { force: true });
		await rm(this.file(id) + '.json', { force: true });
	}
}
