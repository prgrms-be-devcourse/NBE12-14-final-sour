// 임시 목업 값: 실제 집계·전송 정책이 아니다.
export interface MockRealtimeConfig {
  pollIntervalMs: number;
  maxParticlesPerTick: number;
  maxActiveParticles: number;
  particleLifetimeMs: number;
}
export const MOCK_REALTIME_CONFIG: MockRealtimeConfig = {
  pollIntervalMs: 1000,
  maxParticlesPerTick: 20,
  maxActiveParticles: 40,
  particleLifetimeMs: 1200,
};
export interface PlannedOtherClick { delayMs: number; xPct: number; yPct: number; weight: number }
export function createSeededRandom(seed: number): () => number {
  let state = seed >>> 0;
  return () => { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 0x100000000; };
}
export function planOtherClickParticles(count: number, config: MockRealtimeConfig, rng: () => number): PlannedOtherClick[] {
  if (!Number.isFinite(count) || count <= 0 || config.maxParticlesPerTick <= 0 || config.pollIntervalMs <= 0) return [];
  const total = Math.floor(count);
  const length = Math.min(total, Math.floor(config.maxParticlesPerTick));
  if (!length) return [];
  const sample = () => Math.max(0, Math.min(0.999999, rng()));
  return Array.from({ length }, (_, i) => ({
    delayMs: (i + sample()) * config.pollIntervalMs / length,
    xPct: 15 + sample() * 70,
    yPct: 22 + sample() * 56,
    weight: Math.floor(total / length) + (i < total % length ? 1 : 0),
  }));
}
export interface OtherClicksSource { exchange(myClicks: number): { otherClicks: number } | Promise<{ otherClicks: number }> }
// 실제 서비스에서는 source를 API로 교체한다. 서버가 요청자 본인의 클릭을 제외해야 한다.
export function createMockOtherClicksSource(seed = 7123): OtherClicksSource {
  const random = createSeededRandom(seed);
  return { exchange: (_myClicks) => ({ otherClicks: Math.floor(random() * 61) }) };
}
