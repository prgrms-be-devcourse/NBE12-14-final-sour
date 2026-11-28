// SCRUM-187: AssistController / AssistRequest / AssistResponse. The assist guard shares click limits and dedupe with
// clicks and attacks. Only another school of the member's current alliance can be assisted (400 ASSIST_NOT_ALLY).
import type { ApiClient } from './client';
import type { ClickRequest } from './clicks';

export interface AssistRequest extends ClickRequest {
  targetUniversityId: number;
}

/** `assistScore` was added to the ally (×0.3, floored); `myContribScore` is the member's total contribution after it. */
export interface AssistResponse {
  acceptedClicks: number;
  assistScore: number;
  targetUniversity: { id: number; name: string; totalScore: number; rank: number | null };
  myContribScore: number;
  /** Next attack/assist batch: crit positions and per-click damage rates (see ClickResponse). */
  criticalIndex: number[];
  damageRateIndex: number[];
  /**
   * The target school in the previous second (particles only; absent on older servers): clicks by its students, and
   * the attacks and assists it received, the requester's own included.
   */
  /** The previous second these counts are for (epoch seconds); without it they are not used (older server). */
  second?: number;
  schoolClicksLastSecond?: number;
  schoolAttacksLastSecond?: number;
  schoolAssistsLastSecond?: number;
}

export function submitAssist(client: ApiClient, body: AssistRequest, signal?: AbortSignal): Promise<AssistResponse> {
  return client.request<AssistResponse>('/assists', { method: 'POST', body, signal });
}
