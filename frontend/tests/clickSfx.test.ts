// Campus tap sound: a random bubble-pop clip with pitch jitter, the crit chime layered on critical taps,
// and the synthesized blip while no clip is decoded. Web Audio is replaced by the small fakes below.
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { MAX_VOICES, PITCH_JITTER, SampleBank, jitterRate, pickSampleIndex, type SampleContext } from '../src/utils/sfxSamples';
import { ATTACK_CRIT_PUNCH_GAIN, ATTACK_PUNCH_GAIN, RetroSoundManager } from '../src/utils/sound';
import { SAMPLE_GAIN } from '../src/utils/sfxSamples';

class FakeParam {
  value = 1;
  setValueAtTime() { return this; }
  exponentialRampToValueAtTime() { return this; }
}

class FakeNode {
  connections = 0;
  connect() { this.connections += 1; }
  disconnect() { this.connections = 0; }
}

class FakeSource extends FakeNode {
  buffer: unknown = null;
  playbackRate = new FakeParam();
  onended: (() => void) | null = null;
  started = false;
  stopped = false;
  start() { this.started = true; }
  stop() { this.stopped = true; }
}

class FakeOscillator extends FakeNode {
  type = 'sine';
  frequency = new FakeParam();
  start() {}
  stop() {}
}

class FakeAudioContext {
  static instances: FakeAudioContext[] = [];
  state = 'running';
  currentTime = 0;
  sampleRate = 8000;
  destination = new FakeNode();
  buffers: Array<{ length: number }> = [];
  sources: FakeSource[] = [];
  oscillators: FakeOscillator[] = [];
  decoded: string[] = [];
  failDecode = new Set<string>();
  constructor() { FakeAudioContext.instances.push(this); }
  resume() { return Promise.resolve(); }
  createBufferSource() { const source = new FakeSource(); this.sources.push(source); return source; }
  createOscillator() { const osc = new FakeOscillator(); this.oscillators.push(osc); return osc; }
  createGain() { return Object.assign(new FakeNode(), { gain: new FakeParam() }); }
  createBiquadFilter() { return Object.assign(new FakeNode(), { type: 'lowpass', frequency: new FakeParam() }); }
  createBuffer(_channels: number, length: number) {
    const data = new Float32Array(length);
    const buffer = { length, getChannelData: () => data };
    this.buffers.push(buffer);
    return buffer;
  }
  decodeAudioData(data: ArrayBuffer) {
    const name = new TextDecoder().decode(data);
    if (this.failDecode.has(name)) return Promise.reject(new Error(`cannot decode ${name}`));
    this.decoded.push(name);
    return Promise.resolve({ name });
  }
}

const asContext = (ctx: FakeAudioContext) => ctx as unknown as SampleContext;

/** A fetch whose body is the URL itself, so the fake decoder knows which clip it decoded. */
function fakeFetch(failing: readonly string[] = []) {
  const calls: string[] = [];
  const fetch = async (url: string) => {
    calls.push(url);
    return { ok: !failing.includes(url), arrayBuffer: async () => new TextEncoder().encode(url).buffer as ArrayBuffer };
  };
  return { fetch, calls };
}

/** A random() that returns the given values in turn, repeating the last one. */
function sequence(...values: number[]) {
  let i = 0;
  return () => values[Math.min(i++, values.length - 1)];
}

afterEach(() => {
  FakeAudioContext.instances = [];
  delete (globalThis as { window?: unknown }).window;
});

test('pickSampleIndex reaches every index and never leaves the range', () => {
  const picked = new Set<number>();
  for (let i = 0; i < 7; i++) picked.add(pickSampleIndex(7, () => (i + 0.5) / 7));
  assert.deepEqual([...picked].sort(), [0, 1, 2, 3, 4, 5, 6]);
  assert.equal(pickSampleIndex(7, () => 0), 0);
  assert.equal(pickSampleIndex(7, () => 0.9999999999), 6);
  assert.throws(() => pickSampleIndex(0), RangeError);
});

test('jitterRate stays within the pitch jitter either side of 1', () => {
  assert.equal(PITCH_JITTER, 0.03);
  assert.equal(jitterRate(() => 0), 0.97);
  assert.equal(jitterRate(() => 0.5), 1);
  assert.ok(Math.abs(jitterRate(() => 0.9999999999) - 1.03) < 1e-9);
});

test('a SampleBank plays nothing until a clip is decoded, then a random clip at a jittered rate', async () => {
  const ctx = new FakeAudioContext();
  const { fetch } = fakeFetch();
  const bank = new SampleBank(['a.mp3', 'b.mp3', 'c.mp3'], { fetch, random: sequence(0.5, 0.25) });

  assert.equal(bank.play(asContext(ctx)), false);
  assert.equal(ctx.sources.length, 0);

  await bank.load(asContext(ctx));
  assert.equal(bank.play(asContext(ctx)), true);
  const [source] = ctx.sources;
  assert.deepEqual(source.buffer, { name: 'b.mp3' }); // floor(0.5 * 3) = 1
  assert.equal(source.playbackRate.value, jitterRate(() => 0.25));
  assert.equal(source.started, true);
});

test('a SampleBank loads once and keeps the clips that did load', async () => {
  const ctx = new FakeAudioContext();
  ctx.failDecode.add('c.mp3');
  const { fetch, calls } = fakeFetch(['b.mp3']);
  const bank = new SampleBank(['a.mp3', 'b.mp3', 'c.mp3'], { fetch, random: () => 0.99 });

  await Promise.all([bank.load(asContext(ctx)), bank.load(asContext(ctx))]);
  assert.deepEqual(calls, ['a.mp3', 'b.mp3', 'c.mp3']);
  assert.equal(bank.play(asContext(ctx)), true);
  assert.deepEqual(ctx.sources[0].buffer, { name: 'a.mp3' });
});

test('a SampleBank stops the oldest clip once MAX_VOICES ring at once', async () => {
  const ctx = new FakeAudioContext();
  const bank = new SampleBank(['a.mp3'], { fetch: fakeFetch().fetch });
  await bank.load(asContext(ctx));

  for (let i = 0; i < MAX_VOICES; i++) bank.play(asContext(ctx));
  assert.equal(ctx.sources.some((source) => source.stopped), false);

  bank.play(asContext(ctx));
  assert.deepEqual(ctx.sources.map((source) => source.stopped), [true, ...Array(MAX_VOICES).fill(false)]);

  // A clip that ended frees its voice, so the next one does not stop another.
  ctx.sources[1].onended?.();
  assert.equal(ctx.sources[1].connections, 0);
  bank.play(asContext(ctx));
  assert.equal(ctx.sources.filter((source) => source.stopped).length, 1);
});

/** A sound manager on a fake window whose clip bank has a.mp3 and b.mp3. */
function managerOnFakeWindow() {
  (globalThis as { window?: unknown }).window = { AudioContext: FakeAudioContext };
  const bank = new SampleBank(['a.mp3', 'b.mp3'], { fetch: fakeFetch().fetch });
  const punch = new SampleBank(['punch.mp3'], { fetch: fakeFetch().fetch, gain: ATTACK_PUNCH_GAIN });
  const critPunch = new SampleBank(['heavy.mp3'], { fetch: fakeFetch().fetch, gain: ATTACK_CRIT_PUNCH_GAIN });
  return { manager: new RetroSoundManager(bank, punch, critPunch), bank, punch, critPunch };
}

test('playPopSound plays the synthesized blip until the clips are decoded', () => {
  const { manager } = managerOnFakeWindow();
  manager.playPopSound(false);
  const [ctx] = FakeAudioContext.instances;
  assert.equal(ctx.sources.length, 0);
  assert.deepEqual(ctx.oscillators.map((osc) => osc.type), ['square']);
});

test('playPopSound plays one clip, and a critical tap adds the crit chime', async () => {
  const { manager, bank } = managerOnFakeWindow();
  manager.playPopSound(false); // creates the context and starts decoding
  const [ctx] = FakeAudioContext.instances;
  await bank.load(asContext(ctx));
  ctx.sources = [];
  ctx.oscillators = [];

  manager.playPopSound(false);
  assert.equal(ctx.sources.length, 1);
  assert.equal(ctx.oscillators.length, 0);

  manager.playPopSound(true);
  assert.equal(ctx.sources.length, 2);
  assert.deepEqual(ctx.oscillators.map((osc) => osc.type), ['triangle']);
});

test('playPopSound is silent while sound is off', () => {
  const { manager } = managerOnFakeWindow();
  manager.enabled = false;
  manager.playPopSound(true);
  assert.equal(FakeAudioContext.instances.length, 0);
});

test('playTapSound keeps its synthesized blip and chime for other UI sounds', () => {
  const { manager } = managerOnFakeWindow();
  manager.playTapSound(false);
  manager.playTapSound(true);
  const [ctx] = FakeAudioContext.instances;
  assert.equal(ctx.sources.length, 0);
  assert.deepEqual(ctx.oscillators.map((osc) => osc.type), ['square', 'triangle']);
});

test('playAttackSound plays the synthesized blast until the punch clips are decoded', () => {
  const { manager } = managerOnFakeWindow();
  manager.playAttackSound(false);
  const [ctx] = FakeAudioContext.instances;
  assert.deepEqual(ctx.oscillators.map((osc) => osc.type), ['sine']);
  assert.ok(ctx.sources.length > 0 && ctx.sources.every((source) => source.buffer === ctx.buffers[0]), 'noise only, no clip yet');
});

test('playAttackSound plays the punch clip, and a critical hit plays the heavy punch instead, 3 dB lower', async () => {
  const { manager, punch, critPunch } = managerOnFakeWindow();
  manager.playAttackSound(false); // creates the context and starts decoding
  const [ctx] = FakeAudioContext.instances;
  await Promise.all([punch.load(asContext(ctx)), critPunch.load(asContext(ctx))]);
  ctx.sources = [];
  ctx.oscillators = [];

  manager.playAttackSound(false);
  manager.playAttackSound(true);
  assert.deepEqual(ctx.sources.map((source) => source.buffer), [{ name: 'punch.mp3' }, { name: 'heavy.mp3' }]);
  assert.equal(ctx.oscillators.length, 0, 'no synthesized blast or chime once the clips play');
  assert.equal(ATTACK_PUNCH_GAIN, SAMPLE_GAIN);
  assert.ok(Math.abs(20 * Math.log10(ATTACK_CRIT_PUNCH_GAIN / ATTACK_PUNCH_GAIN) + 3) < 1e-9, 'the heavy punch is 3 dB below the punch');
});

test('playAssistSound is a rising four-note heal, and a critical assist adds the chime', () => {
  const { manager } = managerOnFakeWindow();
  manager.playAssistSound(false);
  const [ctx] = FakeAudioContext.instances;
  assert.equal(ctx.sources.length, 0);
  assert.deepEqual(ctx.oscillators.map((osc) => osc.type), ['triangle', 'triangle', 'triangle', 'triangle']);

  manager.playAssistSound(true);
  assert.equal(ctx.oscillators.length, 9);
});

test('playAttackSound and playAssistSound are silent while sound is off', () => {
  const { manager } = managerOnFakeWindow();
  manager.enabled = false;
  manager.playAttackSound(true);
  manager.playAssistSound(true);
  assert.equal(FakeAudioContext.instances.length, 0);
});
