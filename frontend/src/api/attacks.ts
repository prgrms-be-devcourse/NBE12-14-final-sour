// SCRUM-168: AttackController / AttackRequest / AttackResponse. The attack guard shares click limits and dedupe.
import type { ApiClient } from './client';
import type { ClickRequest } from './clicks';

export interface AttackRequest extends ClickRequest {
  targetUniversityId: number;
}

export interface AttackResponse {
  acceptedClicks: number;
  damage: number;
  multiplier: number;
  isRival: boolean;
  targetUniversity: { id: number; name: string; totalScore: number; rank: number | null };
  myAttackScore: number;
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

export function submitAttack(client: ApiClient, body: AttackRequest, signal?: AbortSignal): Promise<AttackResponse> {
  return client.request<AttackResponse>('/attacks', { method: 'POST', body, signal });
}
