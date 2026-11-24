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
}

export function submitAttack(client: ApiClient, body: AttackRequest, signal?: AbortSignal): Promise<AttackResponse> {
  return client.request<AttackResponse>('/attacks', { method: 'POST', body, signal });
}
