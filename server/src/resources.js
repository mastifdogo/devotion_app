// What this machine (in practice: the Proxmox container) gives the server,
// read while running, and how much audio work fits in it. Nothing here is
// configured: change the container's cores or memory and the plan follows.
import os from 'node:os';

const MB = 1024 * 1024;
// Measured sizes, rounded up: the server itself, and one run of the Piper
// voice engine with a "medium" voice loaded.
const SERVER_MB = 150;
const VOICE_ENGINE_MB = 220;
const MAX_JOBS = 4;

/** Cores and memory the server may use. Inside a container these are the container's, not the host's. */
export function detect() {
	const cores = Math.max(1, os.availableParallelism?.() ?? os.cpus().length);
	const total = os.totalmem();
	// The service's own limit (systemd MemoryMax, itself a share of the container) when there is one.
	const limit = process.constrainedMemory?.() ?? 0;
	const usable = limit > 0 && limit < total ? limit : total;
	return { cores, memoryMb: Math.round(total / MB), usableMb: Math.round(usable / MB) };
}

/**
 * How many voice engines may run at once.
 *   by memory: what is left after the server itself, in voice engines
 *   by cores:  each engine keeps a core busy; one core is left for the web
 *              pages and the app once there are more than two
 * A speech API uses no memory here, so only a small fixed number applies.
 */
export function plan({ cores, memoryMb, usableMb }, provider = 'piper') {
	if (provider === 'none') return { cores, memoryMb, usableMb, audioJobs: 0, note: 'Audio is turned off.' };
	if (provider !== 'piper') return { cores, memoryMb, usableMb, audioJobs: 2, note: 'Audio comes from a speech service, two requests at a time.' };
	const byMemory = Math.floor((usableMb - SERVER_MB) / VOICE_ENGINE_MB);
	const byCores = cores <= 2 ? 1 : cores - 1;
	const audioJobs = Math.max(0, Math.min(byMemory, byCores, MAX_JOBS));
	let note;
	if (audioJobs === 0) note = `Not enough memory to make audio: the voice engine needs about ${SERVER_MB + VOICE_ENGINE_MB} MB for the server, and it has ${usableMb} MB. Give the container at least 512 MB.`;
	else if (audioJobs === 1) note = byMemory <= byCores && byMemory < MAX_JOBS && cores > 2 ? 'Making audio one entry at a time: more memory would allow more at once.' : 'Making audio one entry at a time.';
	else note = `Making audio ${audioJobs} entries at a time.`;
	return { cores, memoryMb, usableMb, audioJobs, note };
}

export const currentPlan = (provider) => plan(detect(), provider);
