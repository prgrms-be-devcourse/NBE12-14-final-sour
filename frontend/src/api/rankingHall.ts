import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo } from 'react';
import { useApiClient } from './ApiProvider';
import { getRankingPage, rankingKeys, type RankingKind, type RankingPageByKind } from './rankings';

const HALL_REFRESH_MS = 30_000;
type HallTail<Page> = { pages: Page[]; incomplete: boolean };
type RankedPage = { page: number; hasNext: boolean; items: readonly { rank: number }[] };

export const isHallRank = (rank: number) => rank === 1 || rank === 2 || rank === 3;

function nextHallPage(page: RankedPage) {
  // An empty page can have hasNext: the server may skip directory records that vanished mid-query.
  return page.hasNext && !page.items.some(row => row.rank > 3) ? page.page + 1 : undefined;
}

function joinHallPages<Page extends RankedPage>(list: readonly Page[], tail: readonly Page[]) {
  const cached = new Map(tail.map(page => [page.page, page]));
  const pages: Page[] = [];
  for (let index: number | undefined = 0; index !== undefined;) {
    const page: Page | undefined = list[index] ?? cached.get(index);
    if (!page || page.page !== index) break;
    pages.push(page);
    index = nextHallPage(page);
  }
  return pages;
}

export function useRankingHall<K extends RankingKind>(kind: K, { universityId, viewerId, viewerSchoolId, enabled, list }: {
  universityId: number | null; viewerId: number | null; viewerSchoolId: number | null;
  enabled: boolean; list: readonly RankingPageByKind[K][] | undefined;
}) {
  type Page = RankingPageByKind[K];
  const client = useApiClient();
  const cache = useQueryClient();
  const prefix = useMemo(() => enabled && list ? joinHallPages(list, []) : [], [enabled, list]);
  const last = prefix.at(-1);
  const start = last && nextHallPage(last);
  const scope = [...rankingKeys.list(kind, universityId, viewerId), 'hall', viewerSchoolId] as const;
  // A completed list prefix detaches any pending tail request. If load-more merely extends the
  // prefix, keep the request in flight; joining below gives the normal list precedence.
  const queryKey = [...scope, start !== undefined] as const;
  const tail = useQuery({
    queryKey,
    enabled: enabled && start !== undefined,
    queryFn: async ({ signal }): Promise<HallTail<Page>> => {
      const previous = cache.getQueryData<HallTail<Page>>(queryKey);
      const pages: Page[] = [];
      let page = start;
      try {
        while (page !== undefined) {
          signal.throwIfAborted();
          const result = await getRankingPage(client, kind, { universityId, page, signal });
          if (result.page !== page) throw new Error('Unexpected ranking page');
          pages.push(result);
          page = nextHallPage(result);
        }
        return { pages, incomplete: false };
      } catch (error) {
        if (signal.aborted) throw error;
        // Keep the successful part of this scan and previously seen rows beyond the failure.
        return { pages: pages.concat(previous?.pages.filter(old => page !== undefined && old.page >= page) ?? []), incomplete: true };
      }
    },
    retry: false,
    staleTime: HALL_REFRESH_MS,
    refetchInterval: HALL_REFRESH_MS,
    refetchOnWindowFocus: false,
  });
  const tailPages = tail.data?.pages;
  const pages = useMemo(() => enabled && list && tailPages ? joinHallPages(list, tailPages) : prefix,
    [enabled, list, prefix, tailPages]);
  const incomplete = enabled && start !== undefined && tail.data?.incomplete === true;
  return {
    scope: JSON.stringify(scope),
    pages,
    incomplete,
    loading: tail.isFetching && (incomplete || tail.data === undefined),
    retry: () => { void tail.refetch({ cancelRefetch: false }); },
  };
}
