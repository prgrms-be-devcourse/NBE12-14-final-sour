// Recorded sound-effect clips played through Web Audio: a random clip per call, a small pitch jitter, and a cap on
// how many clips ring at once so rapid taps do not pile up into clipping.

/** Largest playback-rate change either way: 0.03 is about half a semitone. */
export const PITCH_JITTER = 0.03;
/** Clips allowed to ring at once; the oldest stops when another starts. */
export const MAX_VOICES = 6;
/** Output gain per clip. The clips are level-matched to -24 dB RMS by scripts/sfx/normalize-sfx.sh. */
export const SAMPLE_GAIN = 0.8;

/** A uniformly random index in [0, count). */
export function pickSampleIndex(count: number, random: () => number = Math.random): number {
  if (!Number.isInteger(count) || count <= 0) throw new RangeError(`count must be a positive integer, got ${count}`);
  return Math.min(count - 1, Math.floor(random() * count));
}

/** A playback rate in [1 - jitter, 1 + jitter]. */
export function jitterRate(random: () => number = Math.random, jitter: number = PITCH_JITTER): number {
  return 1 + (random() * 2 - 1) * jitter;
}

/** The parts of an AudioContext a SampleBank uses. */
export interface SampleContext {
  readonly destination: AudioNode;
  createBufferSource(): AudioBufferSourceNode;
  createGain(): GainNode;
  decodeAudioData(data: ArrayBuffer): Promise<AudioBuffer>;
}

type FetchBytes = (url: string) => Promise<{ ok: boolean; arrayBuffer(): Promise<ArrayBuffer> }>;

export interface SampleBankOptions {
  fetch?: FetchBytes;
  random?: () => number;
  maxVoices?: number;
  gain?: number;
}

export class SampleBank {
  private buffers: AudioBuffer[] = [];
  private loading: Promise<void> | null = null;
  private voices: AudioBufferSourceNode[] = [];
  private readonly fetchBytes: FetchBytes;
  private readonly random: () => number;
  private readonly maxVoices: number;
  private readonly gain: number;

  constructor(private readonly urls: readonly string[], options: SampleBankOptions = {}) {
    this.fetchBytes = options.fetch ?? ((url) => fetch(url));
    this.random = options.random ?? Math.random;
    this.maxVoices = options.maxVoices ?? MAX_VOICES;
    this.gain = options.gain ?? SAMPLE_GAIN;
  }

  /** Whether at least one clip is decoded and play() will sound. */
  get ready(): boolean {
    return this.buffers.length > 0;
  }

  /**
   * Fetches and decodes every clip once; later calls return the same promise. A clip that fails to load is left out,
   * so one bad file does not silence the rest. Never rejects.
   */
  load(ctx: SampleContext): Promise<void> {
    this.loading ??= Promise.allSettled(this.urls.map(async (url) => {
      const response = await this.fetchBytes(url);
      if (!response.ok) throw new Error(`failed to fetch ${url}`);
      return ctx.decodeAudioData(await response.arrayBuffer());
    })).then((results) => {
      this.buffers = results.flatMap((result) => (result.status === 'fulfilled' ? [result.value] : []));
    });
    return this.loading;
  }

  /** Starts one random clip at a jittered rate. Returns false, playing nothing, while no clip is decoded. */
  play(ctx: SampleContext): boolean {
    if (!this.ready) return false;
    while (this.voices.length >= this.maxVoices) this.voices.shift()?.stop();

    const source = ctx.createBufferSource();
    source.buffer = this.buffers[pickSampleIndex(this.buffers.length, this.random)];
    source.playbackRate.value = jitterRate(this.random);
    const gain = ctx.createGain();
    gain.gain.value = this.gain;
    source.connect(gain);
    gain.connect(ctx.destination);
    source.onended = () => {
      this.voices = this.voices.filter((voice) => voice !== source);
      source.disconnect();
      gain.disconnect();
    };
    this.voices.push(source);
    source.start();
    return true;
  }
}
