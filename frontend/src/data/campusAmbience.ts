// 임시 목업 값. 실제 서비스의 시간대와 연출 정책이 아니다.
export type CampusPhase = 'dawn' | 'day' | 'dusk' | 'night';
export type AmbienceOverride = CampusPhase | 'auto';
type PhaseVisual = { tint: string; tintOpacity: number; light: string; lightType: 'sun' | 'warm' | 'night'; glowIntensity: number; windowLights: boolean; particlePalette: string };
export interface AmbienceConfig {
  phases: readonly { phase: CampusPhase; startHour: number }[];
  transitionMinutes: number;
  maxTintOpacity: number;
  minNightTintOpacity: number;
  maxLightOpacity: number;
  windowLightOpacity: number;
  updateIntervalMs: number;
  visuals: Record<CampusPhase, PhaseVisual>;
}
export const MOCK_AMBIENCE_CONFIG: AmbienceConfig = {
  phases: [{ phase: 'dawn', startHour: 5 }, { phase: 'day', startHour: 7 }, { phase: 'dusk', startHour: 17 }, { phase: 'night', startHour: 19 }],
  transitionMinutes: 20,
  maxTintOpacity: 0.6,
  minNightTintOpacity: 0.4,
  maxLightOpacity: 0.35,
  windowLightOpacity: 1,
  updateIntervalMs: 60_000,
  visuals: {
    dawn: { tint: '#bd806b', tintOpacity: 0.32, light: '#ffcc87', lightType: 'warm', glowIntensity: 0.20, windowLights: false, particlePalette: 'warm' },
    day: { tint: '#d9efff', tintOpacity: 0.04, light: '#fff8d1', lightType: 'sun', glowIntensity: 0.15, windowLights: false, particlePalette: 'sky' },
    dusk: { tint: '#a35f60', tintOpacity: 0.42, light: '#ffb15c', lightType: 'warm', glowIntensity: 0.22, windowLights: false, particlePalette: 'warm' },
    night: { tint: '#14245c', tintOpacity: 0.6, light: '#ffd279', lightType: 'night', glowIntensity: 0.24, windowLights: true, particlePalette: 'light' },
  },
};
export interface CampusPhaseInfo { phase: CampusPhase; next: CampusPhase; blend: number }

// Transition occupies the final transitionMinutes of a phase; exact boundaries start at blend 0.
export function getCampusTimePhase(now: number, config: AmbienceConfig): CampusPhaseInfo {
  const minutesPerDay = 24 * 60;
  const localMinutes = ((now / 60_000 + 9 * 60) % minutesPerDay + minutesPerDay) % minutesPerDay;
  const phases = config.phases;
  let index = phases.length - 1;
  for (let i = 0; i < phases.length; i++) if (localMinutes >= phases[i].startHour * 60) index = i;
  const nextIndex = (index + 1) % phases.length;
  const end = phases[nextIndex].startHour * 60;
  const remaining = (end - localMinutes + minutesPerDay) % minutesPerDay;
  const blend = config.transitionMinutes > 0 ? Math.max(0, Math.min(1, (config.transitionMinutes - remaining) / config.transitionMinutes)) : 0;
  return { phase: phases[index].phase, next: phases[nextIndex].phase, blend };
}

export function resolveAmbience(info: CampusPhaseInfo, override: AmbienceOverride, config: AmbienceConfig) {
  const phase = override === 'auto' ? info.phase : override;
  const next = override === 'auto' ? info.next : phase;
  const blend = override === 'auto' ? Math.max(0, Math.min(1, info.blend)) : 0;
  const makeLayer = (key: CampusPhase, weight: number) => {
    const visual = config.visuals[key];
    return { ...visual, phase: key, tintOpacity: Math.min(config.maxTintOpacity, Math.max(0, visual.tintOpacity * weight)), glowIntensity: Math.min(config.maxLightOpacity, Math.max(0, visual.glowIntensity * weight)), windowOpacity: Math.max(0, Math.min(1, config.windowLightOpacity * weight)) };
  };
  return { phase, next, blend, layers: [makeLayer(phase, 1 - blend), makeLayer(next, blend)], particlePalette: config.visuals[blend >= 0.5 ? next : phase].particlePalette };
}
