// School map (SCRUM-162): GET /api/v1/map/universities (UniversityMapController.java). Public.
// Only schools in the Redis ranking appear, ranked like the rankings (ties share a rank). The server caches the list
// for 5 seconds. latitude/longitude are the library coordinates, null in the seed (V2__seed_universities.sql), so the
// map keeps its own marker layout by school ID.

import { useQuery } from '@tanstack/react-query';
import { useApiClient } from './ApiProvider';
import type { ApiClient } from './client';
import { RANKING_REFRESH_MS } from './rankings';

/** The backend's maximum; the map needs every ranked school. */
export const MAP_UNIVERSITY_LIMIT = 300;

/** UniversityMapResponse. BigDecimal coordinates arrive as JSON numbers. */
export interface MapUniversity {
  rank: number;
  universityId: number;
  name: string;
  logoUrl: string | null;
  totalScore: number;
  latitude: number | null;
  longitude: number | null;
}

export function getMapUniversities(client: ApiClient, { limit = MAP_UNIVERSITY_LIMIT, signal }: { limit?: number; signal?: AbortSignal } = {}): Promise<MapUniversity[]> {
  return client.get<MapUniversity[]>('/map/universities', { query: { limit }, signal, anonymous: true });
}

export const mapKeys = {
  universities: ['map', 'universities'] as const,
};

export function useMapUniversities() {
  const client = useApiClient();
  // Match the ranking refresh and the server's five-second cache.
  return useQuery({ queryKey: mapKeys.universities, queryFn: ({ signal }) => getMapUniversities(client, { signal }), refetchInterval: RANKING_REFRESH_MS });
}
