// ClickActivityController: clicks by a school's students and the attacks and assists it received, in the previous
// second (2026-11-28). Public: guests watching a campus see the same particles.
import type { ApiClient } from './client';

export interface UniversityActivity {
  /** The counted second (epoch seconds, the server's current second − 1). The same second twice is one report. */
  second: number;
  clicks: number;
  attacks: number;
  assists: number;
}

export function getUniversityActivity(client: ApiClient, universityId: number, signal?: AbortSignal): Promise<UniversityActivity> {
  return client.request<UniversityActivity>(`/universities/${universityId}/activity`, { signal });
}
