import type { ClickCoordinates } from '../api/clicks';

export const LOCATION_MAX_AGE_MS = 30_000;
export const LOCATION_MAX_ACCURACY_METERS = 100;
export type LocationStatus = 'idle' | 'locating' | 'ready' | 'denied' | 'unavailable' | 'timeout' | 'inaccurate' | 'expired' | 'unsupported' | 'insecure';
export interface ClickLocationSnapshot { status: LocationStatus; accuracy: number | null }
export interface LocationFix { timestamp: number; coords: ClickCoordinates & { accuracy: number } }
interface LocationReader {
  getCurrentPosition(success: (position: LocationFix) => void, error: (error: { code: number }) => void, options: PositionOptions): void;
}
interface LocationOptions {
  geolocation?: () => LocationReader | undefined;
  isSecure?: () => boolean;
  now?: () => number;
  schedule?: (run: () => void, delay: number) => () => void;
}

/** Explicit, one-shot consent. No watch, storage, logging, permission probing, or network dependency. */
export function createClickLocation(options: LocationOptions = {}) {
  const now = options.now ?? Date.now;
  const geolocation = options.geolocation ?? (() => globalThis.navigator?.geolocation);
  const secure = options.isSecure ?? (() => globalThis.isSecureContext === true);
  const schedule = options.schedule ?? ((run, delay) => { const timer = setTimeout(run, delay); return () => clearTimeout(timer); });
  const listeners = new Set<() => void>();
  let state: ClickLocationSnapshot = { status: 'idle', accuracy: null };
  let coordinates: ClickCoordinates | undefined;
  let capturedAt = 0;
  let generation = 0;
  let cancelExpiry: (() => void) | undefined;
  const publish = (status: LocationStatus, accuracy: number | null = null) => {
    state = { status, accuracy };
    for (const listener of [...listeners]) listener();
  };
  function reset(status: LocationStatus) {
    generation++;
    cancelExpiry?.();
    cancelExpiry = undefined;
    coordinates = undefined;
    capturedAt = 0;
    publish(status);
  }
  const fresh = () => now() >= capturedAt && now() - capturedAt < LOCATION_MAX_AGE_MS;
  return {
    getSnapshot: () => state,
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    clear() { reset('idle'); },
    getCoordinates(): ClickCoordinates | undefined {
      if (!coordinates) return undefined;
      // Timers may be throttled in a hidden tab. Never send an expired fix just because its timer is late.
      if (!fresh()) { reset('expired'); return undefined; }
      return { ...coordinates };
    },
    request() {
      if (state.status === 'locating') return;
      reset('idle');
      if (!secure()) { publish('insecure'); return; }
      const reader = geolocation();
      if (!reader) { publish('unsupported'); return; }
      const version = generation;
      publish('locating');
      if (version !== generation) return;
      try {
        reader.getCurrentPosition((fix) => {
          if (version !== generation || state.status !== 'locating') return;
          const { latitude, longitude, accuracy } = fix.coords;
          if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 || !Number.isFinite(longitude) || longitude < -180 || longitude > 180
            || !Number.isFinite(accuracy) || accuracy < 0 || !Number.isFinite(fix.timestamp)) { reset('unavailable'); return; }
          if (accuracy > LOCATION_MAX_ACCURACY_METERS) { reset('inaccurate'); return; }
          capturedAt = fix.timestamp;
          if (!fresh()) { reset('expired'); return; }
          coordinates = { latitude, longitude };
          cancelExpiry = schedule(() => { if (version === generation) reset('expired'); }, LOCATION_MAX_AGE_MS - (now() - capturedAt));
          publish('ready', accuracy);
        }, (error) => {
          if (version !== generation || state.status !== 'locating') return;
          reset(error.code === 1 ? 'denied' : error.code === 3 ? 'timeout' : 'unavailable');
        }, { enableHighAccuracy: true, maximumAge: 0, timeout: 10_000 });
      } catch { if (version === generation) reset('unavailable'); }
    },
  };
}
export type ClickLocation = ReturnType<typeof createClickLocation>;
