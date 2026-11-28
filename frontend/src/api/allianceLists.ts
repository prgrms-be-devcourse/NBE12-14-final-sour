import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useApiClient } from './ApiProvider';
import { allianceKeys, MATCH_REFRESH_MS, useRefetchOnReveal, type AllianceApplicationStatus, type AllianceMatch, type AllianceStatus } from './alliances';
import { ApiError, CLIENT_ERROR_CODES } from './errors';

interface AllianceName { id: number; name: string }
export interface AllianceSummary extends AllianceName { status: AllianceStatus; expiredAt: string; memberCount: number }
export interface AllianceSchool extends AllianceName { logoUrl: string | null }
export interface ApplicationRow {
  id: number; alliance: AllianceName; university: AllianceSchool; status: AllianceApplicationStatus; createdAt: string;
}
export type InvitationApprovalBlock = 'ALREADY_PROCESSED' | 'ALLIANCE_NOT_ACTIVE' | 'ALLIANCE_FULL' | 'UNIVERSITY_ALREADY_JOINED';
export interface InvitationRow extends ApplicationRow {
  approvalBlockReason: InvitationApprovalBlock | null;
}
export interface MatchRow extends Pick<AllianceMatch, 'id' | 'status' | 'durationHours' | 'requestExpiredAt' | 'scheduledStartAt' | 'startedAt' | 'endedAt'> {
  opponent: AllianceName; direction: 'INCOMING' | 'OUTGOING'; myScore: number | null; opponentScore: number | null;
  result: 'WIN' | 'LOSE' | 'DRAW' | null;
}
interface Page<T> { items: T[]; nextCursor: number | null }

function usePages<T extends { id: number }>(userId: number, path: string, filters: Record<string, string | boolean>, liveAndVisible = false) {
  const client = useApiClient();
  const query = useInfiniteQuery({
    queryKey: [...allianceKeys.lists(userId), path, filters],
    initialPageParam: null as number | null,
    queryFn: async ({ signal, pageParam }) => {
      const page = await client.get<Page<T>>(path, { signal, query: { ...filters, cursor: pageParam ?? undefined } });
      const cursor = page?.nextCursor;
      if (!Array.isArray(page?.items) || (cursor !== null && (!Number.isSafeInteger(cursor) || cursor <= 0
          || (pageParam !== null && cursor >= pageParam) || cursor !== page.items.at(-1)?.id))) {
        throw new ApiError({ kind: 'invalid-response', status: null, code: CLIENT_ERROR_CODES.invalidResponse });
      }
      return page;
    },
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    refetchInterval: liveAndVisible ? MATCH_REFRESH_MS : false,
  });
  useRefetchOnReveal(liveAndVisible, query.refetch);
  return query;
}

export function useAllianceDirectory(userId: number, mode: 'recruiting' | 'opponents' | 'history') {
  const filters: Record<string, string | boolean> = mode === 'history' ? { scope: 'MEMBERSHIP_HISTORY' }
    : mode === 'recruiting' ? { recruiting: true } : { status: 'ACTIVE' };
  return usePages<AllianceSummary>(userId, '/alliances', filters);
}

export function useAllianceSchools(userId: number, allianceId: number) {
  const client = useApiClient();
  const path = `/alliances/${allianceId}/universities`;
  return useQuery({ queryKey: [...allianceKeys.lists(userId), path], queryFn: ({ signal }) => client.get<AllianceSchool[]>(path, { signal }) });
}

export function useAllianceApplications(userId: number, visible: boolean, allianceId?: number) {
  return usePages<ApplicationRow>(userId, allianceId === undefined ? '/alliances/applications/me' : `/alliances/${allianceId}/applications`, {}, visible);
}

export function useAllianceInvitations(userId: number, visible: boolean, allianceId?: number) {
  return usePages<InvitationRow>(userId, allianceId === undefined ? '/alliances/invitations/me' : `/alliances/${allianceId}/invitations`, {}, visible);
}

export function useAllianceMatches(userId: number, allianceId: number, history: boolean, visible: boolean) {
  return usePages<MatchRow>(userId, `/alliances/${allianceId}/matches`, history ? { status: 'FINISHED' } : {}, !history && visible);
}
