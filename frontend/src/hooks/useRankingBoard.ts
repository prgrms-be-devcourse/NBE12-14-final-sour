// What the two ranking screens show (SCRUM-161): RankingScreenPhone (service screen) and RankingsView (game tab).
// One view model for both modes, so a screen does not know whether its rows came from the mock or the API.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useAuthSession } from '../api/ApiProvider';
import { errorMessage } from '../api/errors';
import { selectByMode } from '../api/mode';
import { formatRankChange, useRankingPages, type RankingKind, type RankingPageByKind } from '../api/rankings';
import { useMyInfo, type MyInfo } from '../api/users';
import { getMockRankings } from '../data/mockRankings';
import { REAL_UNIVERSITIES } from '../data/universitiesData';
import { SCHOOL_LOGO_ASSETS } from '../data/schoolLogos';
import type { SchoolId } from '../data/schoolIds';
import type { ImageTarget } from '../utils/sharedImages';
import { useMockGame } from './useMockGame';

export type RankingTab = RankingKind;

export type RankingRowImage =
  /** Mock rows: the demo image store, as before. */
  | { kind: 'shared'; target: ImageTarget }
  /** API rows: the server's image URL, then the bundled school logo, then the first syllable. */
  | { kind: 'url'; url: string | null; defaultUrl?: string };

export interface RankingBoardRow {
  key: string;
  schoolId?: SchoolId;
  rank: number;
  name: string;
  subtitle: string;
  score: number;
  /** "▲2", "▼1" or "-". API mode: the server's rankChange against 00:00 KST; null hides the badge. */
  change: string | null;
  firstSyllable: string;
  badgeColor: string;
  image: RankingRowImage;
  isMine: boolean;
}

export interface RankingBoard {
  source: 'mock' | 'api';
  /** 'signed-out': a school-scoped tab (학과·개인) needs the viewer's school, which only a session has. */
  status: 'loading' | 'failed' | 'ready' | 'signed-out';
  failureDetail: string;
  retry: () => void;
  /** Loaded rows in rank order, the podium included. */
  rows: RankingBoardRow[];
  /** The viewer's own row, also when it is not loaded yet. null when there is none (signed out, private member). */
  mine: RankingBoardRow | null;
  totalCount: number;
  hasMore: boolean;
  loadingMore: boolean;
  loadMore: () => void;
  refreshing: boolean;
  /** Mock-only captions such as "목업 샘플" and "임시 연번" are shown only when this is true. */
  isSample: boolean;
}

interface BoardOptions {
  /** The mock's pretend network delay when loading more rows; 0 loads at once. */
  mockLoadDelayMs?: number;
}

const MOCK_FIRST_SCHOOLS = 15;
const MOCK_MORE_SCHOOLS = 10;
const MOCK_SCOPED_ROWS = 9;

export function useMockRankingBoard(tab: RankingTab, currentSchoolScore: number, { mockLoadDelayMs = 0 }: BoardOptions = {}): RankingBoard {
  const game = useMockGame();
  const rankings = getMockRankings({ ...game.state, schoolScore: currentSchoolScore }, game.player);
  const dataset = rankings[tab];
  const firstCount = tab === 'school' ? MOCK_FIRST_SCHOOLS : MOCK_SCOPED_ROWS;
  const [visible, setVisible] = useState({ tab, count: firstCount });
  const [loadingMore, setLoadingMore] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);
  // A new tab starts from its first rows again.
  const visibleCount = visible.tab === tab ? visible.count : firstCount;
  const hasMore = tab === 'school' && visibleCount < dataset.length;

  const loadMore = useCallback(() => {
    if (!hasMore || loadingMore) return;
    const grow = () => setVisible({ tab, count: Math.min(dataset.length, visibleCount + MOCK_MORE_SCHOOLS) });
    if (mockLoadDelayMs <= 0) { grow(); return; }
    setLoadingMore(true);
    timer.current = setTimeout(() => { timer.current = null; grow(); setLoadingMore(false); }, mockLoadDelayMs);
  }, [hasMore, loadingMore, tab, dataset.length, visibleCount, mockLoadDelayMs]);

  const toRow = (row: (typeof dataset)[number]): RankingBoardRow => ({
    key: `${tab}:${row.id}`, schoolId: tab === 'school' && typeof row.id === 'number' ? row.id : undefined, rank: row.rank, name: row.name, subtitle: row.region, score: row.score, change: row.change,
    firstSyllable: row.firstSyllable, badgeColor: row.badgeColor, isMine: !!row.isMySchool,
    image: { kind: 'shared', target: typeof row.id === 'number' ? { type: 'school', id: row.id } : { type: tab === 'dept' ? 'department' : 'profile', id: row.id } },
  });
  const mine = dataset.find((row) => row.isMySchool) ?? dataset[6];
  return {
    source: 'mock', status: 'ready', failureDetail: '', retry: () => undefined,
    rows: dataset.slice(0, visibleCount).map(toRow), mine: mine ? toRow(mine) : null,
    totalCount: dataset.length, hasMore, loadingMore, loadMore, refreshing: false, isSample: true,
  };
}

const SCHOOL_BY_ID = new Map(REAL_UNIVERSITIES.map((school) => [school.id as number, school]));
const DEPT_COLOR = '#2563eb';
const PERSON_COLOR = '#0284c7';

function schoolRow(id: number, rank: number, name: string, score: number, change: string | null, logoUrl: string | null, isMine: boolean): RankingBoardRow {
  const known = SCHOOL_BY_ID.get(id);
  return {
    key: `school:${id}`, schoolId: id, rank, name, score, change, isMine,
    subtitle: known ? `${known.region} · ${known.city}` : '',
    firstSyllable: known?.firstSyllable ?? name.slice(0, 1), badgeColor: known?.badgeColor ?? DEPT_COLOR,
    image: { kind: 'url', url: logoUrl, defaultUrl: SCHOOL_LOGO_ASSETS[id as SchoolId] },
  };
}

/** Rows of every loaded page in order. A refetch can move a row onto the next page; the first copy wins. */
export function toApiRows<K extends RankingKind>(kind: K, pages: readonly RankingPageByKind[K][], me: MyInfo | undefined): RankingBoardRow[] {
  const seen = new Set<string>();
  const rows: RankingBoardRow[] = [];
  const schoolName = me?.university.name ?? '';
  for (const page of pages) {
    for (const item of page.items) {
      let row: RankingBoardRow;
      if (kind === 'school') {
        const school = item as UniversityItem;
        row = schoolRow(school.universityId, school.rank, school.name, school.totalScore, formatRankChange(school.rankChange), school.logoUrl, school.universityId === me?.university.id);
      } else if (kind === 'dept') {
        const dept = item as DepartmentItem;
        row = {
          key: `dept:${dept.departmentId}`, rank: dept.rank, name: dept.name, subtitle: schoolName, score: dept.totalScore, change: formatRankChange(dept.rankChange),
          firstSyllable: dept.name.slice(0, 1), badgeColor: DEPT_COLOR, isMine: dept.departmentId === me?.department.id,
          image: { kind: 'url', url: dept.logoUrl },
        };
      } else {
        const user = item as UserItem;
        row = {
          key: `personal:${user.userId}`, rank: user.rank, name: user.nickname, score: user.contribScore, change: formatRankChange(user.rankChange),
          subtitle: [schoolName, user.department?.name].filter(Boolean).join(' · '),
          firstSyllable: user.nickname.slice(0, 1), badgeColor: PERSON_COLOR, isMine: user.userId === me?.id,
          image: { kind: 'url', url: user.profileImageUrl },
        };
      }
      if (seen.has(row.key)) continue;
      seen.add(row.key);
      rows.push(row);
    }
  }
  return rows;
}

type UniversityItem = RankingPageByKind['school']['items'][number];
type DepartmentItem = RankingPageByKind['dept']['items'][number];
type UserItem = RankingPageByKind['personal']['items'][number];

/** The viewer's row from the newest page's my* field, so it shows before the viewer's page is loaded. */
export function toApiMine<K extends RankingKind>(kind: K, pages: readonly RankingPageByKind[K][], rows: readonly RankingBoardRow[], me: MyInfo | undefined): RankingBoardRow | null {
  if (!me) return null;
  const first = pages[0];
  if (!first) return null;
  if (kind === 'school') {
    const mine = (first as UniversityRankingFirst).myUniversity;
    if (!mine) return null;
    const loaded = rows.find((row) => row.key === `school:${mine.universityId}`);
    return { ...(loaded ?? schoolRow(mine.universityId, mine.rank, me.university.name, mine.totalScore, null, me.university.logoUrl, true)),
      rank: mine.rank, score: mine.totalScore, change: formatRankChange(mine.rankChange), isMine: true };
  }
  if (kind === 'dept') {
    const mine = (first as DepartmentRankingFirst).myDepartment;
    if (!mine) return null;
    const loaded = rows.find((row) => row.key === `dept:${mine.departmentId}`);
    return {
      ...(loaded ?? { key: `dept:${mine.departmentId}`, name: me.department.name, subtitle: me.university.name, change: null,
        firstSyllable: me.department.name.slice(0, 1), badgeColor: DEPT_COLOR, image: { kind: 'url', url: null } }),
      rank: mine.rank, score: mine.totalScore, change: formatRankChange(mine.rankChange), isMine: true,
    };
  }
  const mine = (first as UserRankingFirst).me;
  if (!mine) return null;
  const loaded = rows.find((row) => row.key === `personal:${me.id}`);
  return {
    ...(loaded ?? { key: `personal:${me.id}`, name: me.nickname, subtitle: `${me.university.name} · ${me.department.name}`, change: null,
      firstSyllable: me.nickname.slice(0, 1), badgeColor: PERSON_COLOR, image: { kind: 'url', url: me.profileImageUrl } }),
    rank: mine.rank, score: mine.contribScore, change: formatRankChange(mine.rankChange), isMine: true,
  };
}
type UniversityRankingFirst = RankingPageByKind['school'];
type DepartmentRankingFirst = RankingPageByKind['dept'];
type UserRankingFirst = RankingPageByKind['personal'];

export function useApiRankingBoard(tab: RankingTab, _currentSchoolScore: number, _options: BoardOptions = {}): RankingBoard {
  const signedIn = useAuthSession().status === 'signed-in';
  const myInfo = useMyInfo();
  const me = signedIn ? myInfo.data : undefined;
  const waitingForMe = signedIn && !me;
  const universityId = tab === 'school' ? null : me?.university.id ?? null;
  const query = useRankingPages(tab, { universityId, viewerId: me?.id ?? null, enabled: !waitingForMe });
  const pages = query.data?.pages;
  const rows = useMemo(() => (pages ? toApiRows(tab, pages, me) : []), [tab, pages, me]);
  const mine = useMemo(() => (pages ? toApiMine(tab, pages, rows, me) : null), [tab, pages, rows, me]);
  const { hasNextPage, isFetchingNextPage, fetchNextPage, refetch } = query;
  const loadMore = useCallback(() => {
    // Scroll events fire many times before a re-render; cancelRefetch: false joins the request already in flight
    // instead of cancelling it and sending the same page again.
    if (hasNextPage && !isFetchingNextPage) void fetchNextPage({ cancelRefetch: false });
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);
  const retry = useCallback(() => {
    if (myInfo.isError) void myInfo.refetch();
    void refetch();
  }, [myInfo, refetch]);

  let status: RankingBoard['status'];
  if (!signedIn && tab !== 'school') status = 'signed-out';
  else if (pages) status = 'ready';
  else if (query.isError || myInfo.isError) status = 'failed';
  else status = 'loading';

  return {
    source: 'api', status, failureDetail: errorMessage(query.error ?? myInfo.error), retry, rows, mine,
    totalCount: pages?.[0]?.totalCount ?? 0, hasMore: !!hasNextPage, loadingMore: isFetchingNextPage, loadMore,
    refreshing: query.isFetching && !isFetchingNextPage && !!pages, isSample: false,
  };
}

/** `currentSchoolScore` feeds the mock's live school score; the API reads scores from the server. */
export const useRankingBoard = selectByMode({ api: useApiRankingBoard, mock: useMockRankingBoard });
