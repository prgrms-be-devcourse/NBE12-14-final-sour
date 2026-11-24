import { useQuery } from '@tanstack/react-query';
import { useApiClient } from './ApiProvider';
import type { ApiClient } from './client';

/** ClickerResponse: requiredScore is the cost to REACH this level, not its next upgrade cost. */
export interface ClickerLevel {
  level: number;
  clickPower: number;
  requiredScore: number;
}

export interface ClickerUpgradeResponse {
  level: number;
  clickPower: number;
  nextLevelRequiredScore: number | null;
  myContribScore: number;
}

export const clickerKeys = { levels: ['clickers', 'levels'] as const };

export function getClickerLevels(client: ApiClient, signal?: AbortSignal): Promise<ClickerLevel[]> {
  return client.get<ClickerLevel[]>('/clickers', { anonymous: true, signal });
}

/** Not idempotent: no request body, no automatic retry. */
export function upgradeClicker(client: ApiClient, signal?: AbortSignal): Promise<ClickerUpgradeResponse> {
  return client.request<ClickerUpgradeResponse>('/clickers/upgrade', { method: 'POST', signal });
}

export function useClickerLevels() {
  const client = useApiClient();
  return useQuery({ queryKey: clickerKeys.levels, queryFn: ({ signal }) => getClickerLevels(client, signal) });
}
