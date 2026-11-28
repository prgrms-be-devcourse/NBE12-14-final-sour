import { useEffect, useState } from 'react';
import { createMockOtherClicksSource, createSeededRandom, MOCK_REALTIME_CONFIG, planOtherClickParticles, type MockRealtimeConfig, type OtherClicksSource, type PlannedOtherClick } from '../data/mockRealtime';
import type { ActivityKind, OtherClicksFeed, SchoolActivity } from '../utils/otherClicksFeed';

export interface OtherClickScheduler {
  interval: (callback: () => void, ms: number) => () => void;
  timeout: (callback: () => void, ms: number) => () => void;
}
const DEFAULT_SCHEDULER: OtherClickScheduler = {
  interval: (callback, ms) => { const id = setInterval(callback, ms); return () => clearInterval(id); },
  timeout: (callback, ms) => { const id = setTimeout(callback, ms); return () => clearTimeout(id); },
};
/** API mode: with no new click response for this long, the count is cleared rather than left stale. */
export const OTHER_CLICKS_STALE_MS = 3000;
export interface OtherClickParticle extends PlannedOtherClick { id: number; kind: ActivityKind }
interface OtherClicksOptions {
  getMyClicksSinceLastTick: () => number;
  config?: MockRealtimeConfig;
  source?: OtherClicksSource;
  scheduler?: OtherClickScheduler;
  /**
   * API mode (SCRUM-159): estimates pushed from click responses. When given, the mock source is never polled.
   * Each estimate covers the previous second, so its particles are spread over `config.pollIntervalMs` (1s).
   */
  feed?: OtherClicksFeed | null;
  /** Without a feed, poll the mock source (default). The visit screen turns it off: it has no mock activity. */
  mockPolling?: boolean;
}
const KINDS: ReadonlyArray<{ kind: ActivityKind; key: keyof SchoolActivity }> = [
  { kind: 'click', key: 'clicks' }, { kind: 'attack', key: 'attacks' }, { kind: 'assist', key: 'assists' },
];
const wholeCount = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0);

export function useOtherClicks({ getMyClicksSinceLastTick, config = MOCK_REALTIME_CONFIG, source, scheduler = DEFAULT_SCHEDULER, feed = null, mockPolling = true }: OtherClicksOptions) {
  const [defaultSource] = useState(() => createMockOtherClicksSource());
  const [particles, setParticles] = useState<OtherClickParticle[]>([]);
  const [activity, setActivity] = useState<SchoolActivity | null>(null);
  useEffect(() => {
    // 새 소스/설정으로 전환하면 이전 세대의 타이머가 취소되므로 표시도 함께 초기화한다.
    setParticles((current) => current.length ? [] : current);
    setActivity(null);
    let disposed = false;
    let sequence = 0;
    const random = createSeededRandom(1831);
    const pending = new Set<() => void>();
    const schedule = (callback: () => void, ms: number) => {
      let cancel = () => {};
      cancel = scheduler.timeout(() => { pending.delete(cancel); if (!disposed) callback(); }, ms);
      pending.add(cancel);
      return () => { pending.delete(cancel); cancel(); };
    };
    const show = (received: SchoolActivity) => {
      if (disposed) return;
      const counts = { clicks: wholeCount(received.clicks), attacks: wholeCount(received.attacks), assists: wholeCount(received.assists) };
      setActivity(counts);
      // Each kind is planned on its own, so a busy second of clicks cannot crowd out a few attacks or assists.
      for (const { kind, key } of KINDS) {
        const perTick = kind === 'click' ? config.maxParticlesPerTick : config.maxActionParticlesPerTick;
        for (const planned of planOtherClickParticles(counts[key], { ...config, maxParticlesPerTick: perTick }, random)) {
          const id = ++sequence;
          schedule(() => {
            setParticles((current) => config.maxActiveParticles > 0 ? [...current, { ...planned, id, kind }].slice(-config.maxActiveParticles) : []);
            schedule(() => setParticles((current) => current.filter((particle) => particle.id !== id)), config.particleLifetimeMs);
          }, planned.delayMs);
        }
      }
    };
    let stop: () => void;
    if (feed) {
      let cancelStale = () => {};
      const unsubscribe = feed.subscribe((received) => {
        show(received);
        cancelStale();
        cancelStale = schedule(() => setActivity(null), OTHER_CLICKS_STALE_MS);
      });
      stop = unsubscribe;
    } else if (!mockPolling) {
      stop = () => {};
    } else {
      stop = scheduler.interval(() => {
        const myClicks = getMyClicksSinceLastTick();
        try {
          Promise.resolve((source ?? defaultSource).exchange(myClicks))
            .then((report) => show({ clicks: report.otherClicks, attacks: report.otherAttacks ?? 0, assists: report.otherAssists ?? 0 }))
            .catch(() => {});
        } catch { /* 요청 실패 시 해당 틱의 연출은 생략한다. */ }
      }, config.pollIntervalMs);
    }
    return () => { disposed = true; stop(); for (const cancel of pending) cancel(); };
  }, [getMyClicksSinceLastTick, config, source, scheduler, defaultSource, feed, mockPolling]);
  return { particles, activity, otherClicks: activity?.clicks ?? null };
}

/** Mock mode only: polls the mock source every `config.pollIntervalMs`. */
export function useMockOtherClicks(options: Omit<OtherClicksOptions, 'feed'>) {
  return useOtherClicks(options);
}
