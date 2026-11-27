// Web Audio API Retro 8-bit Sound Synthesizer, plus recorded clips for the campus tap
import { ATTACK_CRIT_PUNCH_CLIPS, ATTACK_PUNCH_CLIPS, BUBBLE_POP_CLIPS } from '../data/sfxClips';
import { SAMPLE_GAIN, SampleBank } from './sfxSamples';

/**
 * The punch clips are level-matched to the bubble pops by scripts/sfx/normalize-sfx.sh. The normal punch plays at the
 * shared clip gain; the heavy critical punch plays 3 dB lower so it does not jump out over rapid taps.
 */
export const ATTACK_CRIT_PUNCH_DB = -3;
export const ATTACK_PUNCH_GAIN = SAMPLE_GAIN;
export const ATTACK_CRIT_PUNCH_GAIN = SAMPLE_GAIN * 10 ** (ATTACK_CRIT_PUNCH_DB / 20);

export class RetroSoundManager {
  private ctx: AudioContext | null = null;
  public enabled: boolean = true;

  /** The clip banks are replaceable so tests can feed decoded clips without a network. */
  constructor(
    private readonly popClips: SampleBank = new SampleBank(BUBBLE_POP_CLIPS),
    private readonly punchClips: SampleBank = new SampleBank(ATTACK_PUNCH_CLIPS, { gain: ATTACK_PUNCH_GAIN }),
    private readonly critPunchClips: SampleBank = new SampleBank(ATTACK_CRIT_PUNCH_CLIPS, { gain: ATTACK_CRIT_PUNCH_GAIN }),
  ) {}

  private initContext() {
    if (!this.ctx && typeof window !== 'undefined') {
      const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (AudioCtx) {
        this.ctx = new AudioCtx();
        // Decode the clips on the first sound, which always follows a user gesture. Failures fall back to synthesis.
        void this.popClips.load(this.ctx);
        void this.punchClips.load(this.ctx);
        void this.critPunchClips.load(this.ctx);
      }
    }
    if (this.ctx && this.ctx.state === 'suspended') {
      this.ctx.resume().catch(() => {});
    }
  }

  public playTapSound(isCrit: boolean = false) {
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;
      if (isCrit) this.playCritChime(this.ctx);
      else this.playTapBlip(this.ctx);
    } catch {
      // Audio playback might be prevented by browser policy
    }
  }

  /**
   * Campus tap: a random bubble-pop clip at a slightly jittered pitch. A critical tap layers the crit chime on top.
   * Until the clips are decoded (or if none load), the synthesized tap blip plays instead.
   */
  public playPopSound(isCrit: boolean = false) {
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;
      if (!this.popClips.play(this.ctx)) this.playTapBlip(this.ctx);
      if (isCrit) this.playCritChime(this.ctx);
    } catch {
      // Audio playback might be prevented by browser policy
    }
  }

  /**
   * Attack tap on another school: the punch clip, or the heavy punch on a critical hit (no chime: the clip is the
   * critical sound). Until the clips are decoded (or if they fail to load), the synthesized blast plays instead.
   */
  public playAttackSound(isCrit: boolean = false) {
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;
      if (!(isCrit ? this.critPunchClips : this.punchClips).play(this.ctx)) this.playBlast(this.ctx, isCrit ? 1.3 : 1);
    } catch {
      // Audio playback might be prevented by browser policy
    }
  }

  /** Assist tap on an ally school: a game heal, rising sparkle notes. A critical assist adds the crit chime. */
  public playAssistSound(isCrit: boolean = false) {
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;
      this.playHeal(this.ctx);
      if (isCrit) this.playCritChime(this.ctx);
    } catch {
      // Audio playback might be prevented by browser policy
    }
  }

  private noise: { ctx: AudioContext; buffer: AudioBuffer } | null = null;

  /** Half a second of white noise, made once per context: the body of the blast. */
  private noiseBuffer(ctx: AudioContext): AudioBuffer {
    if (this.noise?.ctx === ctx) return this.noise.buffer;
    const buffer = ctx.createBuffer(1, Math.floor(ctx.sampleRate * 0.5), ctx.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < data.length; i++) data[i] = Math.random() * 2 - 1;
    this.noise = { ctx, buffer };
    return buffer;
  }

  /**
   * A short "kwang": a hard hit at the start (noise whose low-pass drops from 2.5kHz in 80ms), a punchy sine thump from
   * 160Hz to 45Hz that dies fast, and a brief low tail. Loud only for the first instant, so rapid attacks stay bearable.
   * `size` lengthens the thump and tail.
   */
  private playBlast(ctx: AudioContext, size: number) {
    const now = ctx.currentTime;
    const noise = this.noiseBuffer(ctx);
    const noiseLayer = (from: number, to: number, volume: number, length: number) => {
      const source = ctx.createBufferSource();
      source.buffer = noise;
      const filter = ctx.createBiquadFilter();
      filter.type = 'lowpass';
      filter.frequency.setValueAtTime(from, now);
      filter.frequency.exponentialRampToValueAtTime(to, now + length);
      const gain = ctx.createGain();
      gain.gain.setValueAtTime(volume, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + length);
      source.connect(filter);
      filter.connect(gain);
      gain.connect(ctx.destination);
      source.start(now);
      source.stop(now + length);
    };

    noiseLayer(2500, 300, 0.14, 0.08);

    const thumpLength = 0.22 * size;
    const thump = ctx.createOscillator();
    const thumpGain = ctx.createGain();
    thump.type = 'sine';
    thump.frequency.setValueAtTime(160, now);
    thump.frequency.exponentialRampToValueAtTime(45, now + thumpLength);
    thumpGain.gain.setValueAtTime(0.2, now);
    thumpGain.gain.exponentialRampToValueAtTime(0.001, now + thumpLength);
    thump.connect(thumpGain);
    thumpGain.connect(ctx.destination);
    thump.start(now);
    thump.stop(now + thumpLength);

    noiseLayer(800, 80, 0.06, 0.3 * size);
  }

  /** C5-E5-G5-C6 arpeggio, each note a soft triangle that overlaps the next. */
  private playHeal(ctx: AudioContext) {
    const now = ctx.currentTime;
    [523.25, 659.25, 783.99, 1046.5].forEach((frequency, index) => {
      const start = now + index * 0.045;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'triangle';
      osc.frequency.setValueAtTime(frequency, start);
      gain.gain.setValueAtTime(0.001, now);
      gain.gain.setValueAtTime(0.1, start);
      gain.gain.exponentialRampToValueAtTime(0.001, start + 0.16);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(start);
      osc.stop(start + 0.16);
    });
  }

  /** Satisfying short 8-bit blip. */
  private playTapBlip(ctx: AudioContext) {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'square';
    const now = ctx.currentTime;
    osc.frequency.setValueAtTime(440, now);
    osc.frequency.exponentialRampToValueAtTime(880, now + 0.06);
    gain.gain.setValueAtTime(0.12, now);
    gain.gain.exponentialRampToValueAtTime(0.001, now + 0.07);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(now);
    osc.stop(now + 0.07);
  }

  /** High sparkling chime, D5 to D6. */
  private playCritChime(ctx: AudioContext) {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'triangle';
    const now = ctx.currentTime;
    osc.frequency.setValueAtTime(587.33, now); // D5
    osc.frequency.exponentialRampToValueAtTime(1174.66, now + 0.12); // D6
    gain.gain.setValueAtTime(0.18, now);
    gain.gain.exponentialRampToValueAtTime(0.001, now + 0.14);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(now);
    osc.stop(now + 0.14);
  }

  public playLevelUpSound() {
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;

      const now = this.ctx.currentTime;
      // Arpeggio notes: C5, E5, G5, C6
      const notes = [523.25, 659.25, 783.99, 1046.50];
      notes.forEach((freq, i) => {
        const osc = this.ctx!.createOscillator();
        const gain = this.ctx!.createGain();
        osc.type = 'square';
        const start = now + i * 0.08;
        osc.frequency.setValueAtTime(freq, start);
        gain.gain.setValueAtTime(0.15, start);
        gain.gain.exponentialRampToValueAtTime(0.001, start + 0.16);
        osc.connect(gain);
        gain.connect(this.ctx!.destination);
        osc.start(start);
        osc.stop(start + 0.16);
      });
    } catch {}
  }

  public playBuySound() {
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;

      const now = this.ctx.currentTime;
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();
      osc.type = 'square';
      osc.frequency.setValueAtTime(987.77, now); // B5
      osc.frequency.setValueAtTime(1318.51, now + 0.08); // E6
      gain.gain.setValueAtTime(0.15, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.22);
      osc.connect(gain);
      gain.connect(this.ctx.destination);
      osc.start(now);
      osc.stop(now + 0.22);
    } catch {}
  }

  public playFeverSound() {
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;

      const now = this.ctx.currentTime;
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();
      osc.type = 'sawtooth';
      osc.frequency.setValueAtTime(320, now);
      osc.frequency.exponentialRampToValueAtTime(1280, now + 0.25);
      gain.gain.setValueAtTime(0.16, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.28);
      osc.connect(gain);
      gain.connect(this.ctx.destination);
      osc.start(now);
      osc.stop(now + 0.28);
    } catch {}
  }

  public playCheerSound() {
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;

      const now = this.ctx.currentTime;
      // Multi-tone whistle / fanfare
      [659.25, 783.99, 880.00].forEach((freq, i) => {
        const osc = this.ctx!.createOscillator();
        const gain = this.ctx!.createGain();
        osc.type = 'triangle';
        const start = now + i * 0.06;
        osc.frequency.setValueAtTime(freq, start);
        gain.gain.setValueAtTime(0.14, start);
        gain.gain.exponentialRampToValueAtTime(0.001, start + 0.15);
        osc.connect(gain);
        gain.connect(this.ctx!.destination);
        osc.start(start);
        osc.stop(start + 0.15);
      });
    } catch {}
  }

  public playTabSound() {
    if (!this.enabled) return;
    try {
      this.initContext();
      if (!this.ctx) return;

      const now = this.ctx.currentTime;
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(600, now);
      gain.gain.setValueAtTime(0.08, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.05);
      osc.connect(gain);
      gain.connect(this.ctx.destination);
      osc.start(now);
      osc.stop(now + 0.05);
    } catch {}
  }
}

export const soundManager = new RetroSoundManager();
