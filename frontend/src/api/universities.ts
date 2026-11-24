// University search, GET /api/v1/universities (UniversityController.searchUniversities). Public: no login needed
// (SecurityConfig). The mock implementation comes with the numeric school IDs (SCRUM-149).

import { useQuery } from '@tanstack/react-query';
import { useApiClient } from './ApiProvider';
import type { ApiClient } from './client';

/** UniversitySearchResponse. logo_url is a nullable column (V1__init.sql), so logoUrl can be null. */
export interface UniversitySummary {
  id: number;
  name: string;
  logoUrl: string | null;
}

/** The backend's default `size`; it accepts 1–100. */
export const UNIVERSITY_SEARCH_SIZE = 20;

export function searchUniversities(
  client: ApiClient,
  keyword: string,
  { size = UNIVERSITY_SEARCH_SIZE, signal }: { size?: number; signal?: AbortSignal } = {},
): Promise<UniversitySummary[]> {
  return client.get<UniversitySummary[]>('/universities', { query: { keyword, size }, signal });
}

/** DepartmentResponse of GET /api/v1/universities/{id}/departments (public). */
export interface DepartmentSummary {
  id: number;
  name: string;
}

export function getDepartments(client: ApiClient, universityId: number, { signal }: { signal?: AbortSignal } = {}): Promise<DepartmentSummary[]> {
  return client.get<DepartmentSummary[]>(`/universities/${universityId}/departments`, { signal, anonymous: true });
}

/**
 * UniversityDetailResponse of GET /api/v1/universities/{id} (public; SCRUM-163). `rank` is null while the school has
 * no Redis ranking (the score then comes from the database). At most three departments and contributors; ties share a
 * rank. `isMyUniversity` and `isMyAlly` (same current alliance, SCRUM-187) are false when signed out. `rankChange` (SCRUM-189)
 * is the rankings' change against the 00:00 KST copy (positive is up); null without a rank or a copy of today.
 */
export interface UniversityDetail {
  id: number;
  name: string;
  logoUrl: string | null;
  totalScore: number;
  rank: number | null;
  memberCount: number;
  topDepartments: Array<{ rank: number; id: number; name: string; totalScore: number }>;
  topContributors: Array<{ rank: number; userId: number; nickname: string; contribScore: number }>;
  isMyUniversity: boolean;
  isMyAlly: boolean;
  rankChange: number | null;
}

/** Same as the rankings: the visited school's score keeps moving while someone watches. */
export const UNIVERSITY_DETAIL_REFRESH_MS = 5_000;

/** Sends the token when there is one, so the server can fill `isMyUniversity`. */
export function getUniversityDetail(client: ApiClient, universityId: number, { signal }: { signal?: AbortSignal } = {}): Promise<UniversityDetail> {
  return client.get<UniversityDetail>(`/universities/${universityId}`, { signal });
}

export const universityKeys = {
  all: ['universities'] as const,
  search: (keyword: string, size: number) => [...universityKeys.all, 'search', { keyword, size }] as const,
  departments: (universityId: number) => [...universityKeys.all, universityId, 'departments'] as const,
  /** The viewer is part of the key because `isMyUniversity` depends on who asks. */
  detail: (universityId: number, viewerId: number | null) => [...universityKeys.all, universityId, 'detail', viewerId ?? 'guest'] as const,
};

/** The visited school's detail; nothing is sent while `universityId` is null or `enabled` is false. */
export function useUniversityDetail(universityId: number | null, { viewerId, enabled = true }: { viewerId: number | null; enabled?: boolean }) {
  const client = useApiClient();
  return useQuery({
    queryKey: universityKeys.detail(universityId ?? -1, viewerId),
    queryFn: ({ signal }) => getUniversityDetail(client, universityId!, { signal }),
    enabled: enabled && universityId !== null,
    refetchInterval: UNIVERSITY_DETAIL_REFRESH_MS,
  });
}

/** The school's departments; nothing is sent while `universityId` is null. */
export function useDepartments(universityId: number | null) {
  const client = useApiClient();
  return useQuery({
    queryKey: universityKeys.departments(universityId ?? -1),
    queryFn: ({ signal }) => getDepartments(client, universityId!, { signal }),
    enabled: universityId !== null,
  });
}

/** Search results for a trimmed keyword. A blank keyword sends nothing: the backend rejects it (@NotBlank). */
export function useUniversitySearch(keyword: string, size = UNIVERSITY_SEARCH_SIZE) {
  const client = useApiClient();
  const trimmed = keyword.trim();
  return useQuery({
    queryKey: universityKeys.search(trimmed, size),
    queryFn: ({ signal }) => searchUniversities(client, trimmed, { size, signal }),
    enabled: trimmed.length > 0,
  });
}
