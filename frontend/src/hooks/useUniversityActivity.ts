import { createContext, useContext, useEffect, useMemo } from 'react';
import { useApiClient } from '../api/ApiProvider';
import { getUniversityActivity, type UniversityActivity } from '../api/activity';
import { activityHubFor, type ActivityHub } from '../utils/universityActivity';
import type { OtherClicksFeed } from '../utils/otherClicksFeed';
import type { OtherClickScheduler } from './useMockOtherClicks';

export const ACTIVITY_POLL_MS = 1000;
/** After failures the poll waits 2s, 4s, 8s, then 10s at most; one success brings it back to every second. */
export const ACTIVITY_MAX_BACKOFF_MS = 10_000;

/** API mode only (App). Without it (mock mode, isolated mockups) the campus polls the mock source instead. */
export interface UniversityActivitySource {
  hub: ActivityHub;
  fetchActivity(universityId: number, signal?: AbortSignal): Promise<UniversityActivity>;
  /** The signed-in member's school: the campus screen's school. */
  myUniversityId: number | null;
  scheduler?: OtherClickScheduler;
  now?: () => number;
  /** Whether the page is in the background; the poll waits until it is shown again. */
  isHidden?: () => boolean;
}
export const UniversityActivityContext = createContext<UniversityActivitySource | null>(null);
export function useUniversityActivitySource(): UniversityActivitySource | null {
  return useContext(UniversityActivityContext);
}

/** App root in API mode: the hub the click and attack queues report to, and the public activity endpoint. */
export function useApiUniversityActivitySource(myUniversityId: number | null): UniversityActivitySource {
  const client = useApiClient();
  return useMemo(() => ({
    hub: activityHubFor(client),
    fetchActivity: (universityId: number, signal?: AbortSignal) => getUniversityActivity(client, universityId, signal),
    myUniversityId,
  }), [client, myUniversityId]);
}

const DEFAULT_SCHEDULER: OtherClickScheduler = {
  interval: (callback, ms) => { const id = setInterval(callback, ms); return () => clearInterval(id); },
  timeout: (callback, ms) => { const id = setTimeout(callback, ms); return () => clearTimeout(id); },
};
const documentHidden = () => typeof document !== 'undefined' && document.visibilityState === 'hidden';

/**
 * The previous-second activity of one school while a screen shows it. Every second it asks the server, unless one of
 * my own click, attack or assist answers about this school just reported it. Null without a source or school.
 */
export function useUniversityActivityFeed(source: UniversityActivitySource | null, universityId: number | null): OtherClicksFeed | null {
  const hub = source?.hub ?? null;
  const fetchActivity = source?.fetchActivity;
  const scheduler = source?.scheduler ?? DEFAULT_SCHEDULER;
  const now = source?.now ?? Date.now;
  const isHidden = source?.isHidden ?? documentHidden;
  useEffect(() => {
    if (!hub || !fetchActivity || universityId === null) return;
    const abort = new AbortController();
    let inFlight = false;
    let failures = 0;
    let retryAt = 0;
    const stop = scheduler.interval(() => {
      if (inFlight || isHidden() || now() < retryAt || hub.isFresh(universityId)) return;
      inFlight = true;
      fetchActivity(universityId, abort.signal)
        .then((activity) => {
          failures = 0;
          retryAt = 0;
          if (!abort.signal.aborted) hub.report(universityId, activity);
        })
        .catch(() => {
          if (abort.signal.aborted) return;
          failures += 1;
          retryAt = now() + Math.min(ACTIVITY_MAX_BACKOFF_MS, ACTIVITY_POLL_MS * 2 ** failures);
        })
        .finally(() => { inFlight = false; });
    }, ACTIVITY_POLL_MS);
    return () => { stop(); abort.abort(); };
  }, [hub, fetchActivity, scheduler, now, isHidden, universityId]);
  return useMemo(() => (hub && universityId !== null ? hub.feed(universityId) : null), [hub, universityId]);
}
