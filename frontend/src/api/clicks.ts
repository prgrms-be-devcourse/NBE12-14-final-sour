// ClickController, ClickRequest and ClickResponse (SCRUM-155).
import type { ApiClient } from './client';

export const CLICK_BATCH_WINDOW_MS = 1000;
export const MAX_CLICK_BATCH_SIZE = 20;

export interface ClickCoordinates { latitude: number; longitude: number }
/** SCRUM-185: optional real-world coordinates; only the server decides whether the library bonus applies. */
export interface ClickRequest extends Partial<ClickCoordinates> {
  submissionId: string;
  clickCount: number;
}

export interface ClickResponse {
  acceptedClicks: number;
  earnedScore: number;
  multiplier: number;
  myContribScore: number;
  university: { id: number; totalScore: number; rank: number; logoUrl: string };
  department: { id: number; totalScore: number };
  /** The previous second these counts are for (epoch seconds); without it they are not used (older server). */
  second?: number;
  schoolClicksLastSecond: number;
  /** Attacks my school received and assists it got in the previous second (particles only; absent on older servers). */
  schoolAttacksLastSecond?: number;
  schoolAssistsLastSecond?: number;
  /** The click came from inside the school library: `multiplier` already includes its x1.2 (ClickService). */
  libraryBonus: boolean;
  /** Next batch only: 0-based click positions the server will double (ClickActionGuard). */
  criticalIndex: number[];
  /** Next batch only: one damage rate (percent, 70~130) per click position. */
  damageRateIndex: number[];
}

export function submitClicks(client: ApiClient, body: ClickRequest): Promise<ClickResponse> {
  return client.request<ClickResponse>('/clicks', { method: 'POST', body });
}
