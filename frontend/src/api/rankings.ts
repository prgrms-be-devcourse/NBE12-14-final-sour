// Rankings (SCRUM-161): GET /api/v1/rankings/universities[/{id}/departments, /{id}/users] (RankingController.java).
// Public endpoints; with a session the server adds the caller's own row (myUniversity, myDepartment, me).
// Ties share a rank. `rankChange` (SCRUM-184) is yesterday's rank − today's, against the server's 00:00 KST copy of the
// rankings: positive is up, null when there is nothing to compare (new member, or no copy for today).

import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useApiClient } from './ApiProvider';
import type { ApiClient } from './client';
import { getUniversityDetail, type UniversityDetail } from './universities';

/** Rows per request. The backend accepts 1–100 and defaults to 20. */
export const RANKING_PAGE_SIZE = 20;
/** The screens say "5초마다 갱신". Paused while the tab is in the background (TanStack Query default). */
export const RANKING_REFRESH_MS = 5_000;
/** The main screen's own-school rank. Ranks move with the 1-minute score batch, so a slower poll is enough. */
export const MY_SCHOOL_RANK_REFRESH_MS = 30_000;

const PUBLIC_TOP_LIMIT = 10;
const PUBLIC_TOP_STALE_MS = 60_000;

interface RankingPage<Item> {
  items: Item[];
  page: number;
  size: number;
  totalCount: number;
  hasNext: boolean;
}

/** UniversityRankingResponse. */
export interface UniversityRankingPage extends RankingPage<{
  rank: number; universityId: number; name: string; logoUrl: string | null; totalScore: number; rankChange: number | null;
}> {
  myUniversity: { rank: number; universityId: number; totalScore: number; rankChange: number | null } | null;
}

/** DepartmentRankingResponse. `myDepartment` is only set when the school is the caller's own. */
export interface DepartmentRankingPage extends RankingPage<{
  rank: number; departmentId: number; name: string; logoUrl: string | null; totalScore: number; rankChange: number | null;
}> {
  myDepartment: { rank: number; departmentId: number; totalScore: number; rankChange: number | null } | null;
}

/** UserRankingResponse. Private members are left out by the server; `me` is only set at the caller's own school. */
export interface UserRankingPage extends RankingPage<{
  rank: number; userId: number; nickname: string; profileImageUrl: string | null;
  department: { id: number; name: string } | null; contribScore: number; rankChange: number | null;
}> {
  me: { rank: number; contribScore: number; rankChange: number | null } | null;
}

/** The badge text the screens already draw: ▲n up, ▼n down, - unchanged; null hides the badge. */
export function formatRankChange(change: number | null | undefined): string | null {
  if (change === null || change === undefined) return null;
  if (change > 0) return `▲${change}`;
  if (change < 0) return `▼${-change}`;
  return '-';
}

export type RankingKind = 'school' | 'dept' | 'personal';
export interface RankingPageByKind {
  school: UniversityRankingPage;
  dept: DepartmentRankingPage;
  personal: UserRankingPage;
}

export function rankingPath(kind: RankingKind, universityId: number | null): string {
  if (kind === 'school') return '/rankings/universities';
  if (universityId === null) throw new Error('학과·개인 순위에는 학교 ID가 필요합니다.');
  return `/rankings/universities/${universityId}/${kind === 'dept' ? 'departments' : 'users'}`;
}

export function getRankingPage<K extends RankingKind>(
  client: ApiClient,
  kind: K,
  { universityId = null, page, size = RANKING_PAGE_SIZE, signal, anonymous = false }: {
    universityId?: number | null; page: number; size?: number; signal?: AbortSignal; anonymous?: boolean;
  },
): Promise<RankingPageByKind[K]> {
  // The default attaches a session token so full ranking screens can include the caller's own row.
  return client.get<RankingPageByKind[K]>(rankingPath(kind, universityId), { query: { page, size }, signal, anonymous });
}

export const rankingKeys = {
  all: ['rankings'] as const,
  /** The viewer is part of the key: the caller's own row depends on who is signed in. */
  list: (kind: RankingKind, universityId: number | null, viewerId: number | null) =>
    [...rankingKeys.all, kind, universityId, viewerId ?? 'guest'] as const,
  mySchool: (viewerId: number) => [...rankingKeys.all, 'my-school', viewerId] as const,
  publicTop: ['rankings', 'public-top', PUBLIC_TOP_LIMIT] as const,
};

/** Public landing snapshot: no polling or session-specific data. Keep one in-flight read across brief screen changes. */
export function usePublicUniversityTop() {
  const client = useApiClient();
  return useQuery({
    queryKey: rankingKeys.publicTop,
    queryFn: async () => {
      const { items } = await getRankingPage(client, 'school', { page: 0, size: PUBLIC_TOP_LIMIT, anonymous: true });
      return items.slice(0, PUBLIC_TOP_LIMIT).map(({ universityId, rank, name, totalScore }) => ({ universityId, rank, name, totalScore }));
    },
    staleTime: PUBLIC_TOP_STALE_MS,
    retry: false,
    retryOnMount: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
}

/**
 * Offset pages joined by scrolling. Nothing is sent while `enabled` is false (a school-scoped tab without a school).
 * A refetch reloads every page that was already open, so the joined list can repeat a row when ranks move between
 * pages; callers remove repeats by ID.
 */
export function useRankingPages<K extends RankingKind>(
  kind: K,
  { universityId, viewerId, enabled = true }: { universityId: number | null; viewerId: number | null; enabled?: boolean },
) {
  const client = useApiClient();
  return useInfiniteQuery({
    queryKey: rankingKeys.list(kind, universityId, viewerId),
    queryFn: ({ pageParam, signal }) => getRankingPage(client, kind, { universityId, page: pageParam, signal }),
    initialPageParam: 0,
    getNextPageParam: (last: RankingPageByKind[K]) => (last.hasNext ? last.page + 1 : undefined),
    enabled: enabled && (kind === 'school' || universityId !== null),
    refetchInterval: RANKING_REFRESH_MS,
  });
}

/** The caller's own school row (`myUniversity`) from a one-row school page. Signed-in callers only. */
export function useMySchoolRanking(viewerId: number | null) {
  const client = useApiClient();
  return useQuery({
    queryKey: rankingKeys.mySchool(viewerId ?? 0),
    queryFn: ({ signal }) => getRankingPage(client, 'school', { page: 0, size: 1, signal }),
    select: (page: UniversityRankingPage) => page.myUniversity,
    enabled: viewerId !== null,
    refetchInterval: MY_SCHOOL_RANK_REFRESH_MS,
  });
}

/**
 * My school's score when it is outside the static list (the hidden operator school, #144). The rankings leave hidden
 * schools out, so `myUniversity` is null for them; GET /universities/{id} reads the hidden school's score instead.
 * The key sits under rankingKeys.mySchool, so a click answer cancels a request in flight as it does the ranking's.
 */
export function useOffListSchoolScore(viewerId: number | null, universityId: number | null) {
  const client = useApiClient();
  return useQuery({
    queryKey: [...rankingKeys.mySchool(viewerId ?? 0), 'off-list', universityId ?? 0] as const,
    queryFn: ({ signal }) => getUniversityDetail(client, universityId!, { signal }),
    select: (detail: UniversityDetail) => detail.totalScore,
    enabled: viewerId !== null && universityId !== null,
    refetchInterval: MY_SCHOOL_RANK_REFRESH_MS,
  });
}
