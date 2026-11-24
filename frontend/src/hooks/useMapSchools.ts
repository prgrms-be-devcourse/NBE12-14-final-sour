// Schools on the map (SCRUM-162). The mock reads the shared mock rows; the API overlays the server's rank, score and
// logo on the 76-school directory, whose marker layout stays in the frontend (the seed has no coordinates).
// Plan: .agents/01-PLAN/261118-2203-scrum162_map_api.md.

import { useMemo } from 'react';
import { useAuthSession } from '../api/ApiProvider';
import { errorMessage } from '../api/errors';
import { selectByMode } from '../api/mode';
import { useMapUniversities, type MapUniversity } from '../api/map';
import { useMyInfo } from '../api/users';
import type { SchoolView } from '../data/schoolDirectory';
import { schoolLogoStatus } from '../data/schoolLogos';
import { REAL_UNIVERSITIES } from '../data/universitiesData';
import { getCampusStage } from '../utils/gameModel';
import { useSchoolViews } from './useSchoolViews';

export interface MapSchools {
  status: 'loading' | 'failed' | 'ready';
  failureDetail: string;
  retry: () => void;
  /** In rank order (the list sheet keeps this order). */
  schools: SchoolView[];
}

export function useMockMapSchools(): MapSchools {
  return { status: 'ready', failureDetail: '', retry: () => undefined, schools: useSchoolViews() };
}

/**
 * The directory with the server's values. A school missing from the response has no score yet: 0 points, sharing the
 * last place after the ranked schools, and never in the TOP 3. Server schools without a directory row have no marker
 * position and are left out.
 */
export function toMapSchoolViews(entries: readonly MapUniversity[], myUniversityId: number | null): SchoolView[] {
  const byId = new Map(entries.map((entry) => [entry.universityId, entry]));
  // Ties keep the server's order (its ranking list shows them the same way); unranked schools follow by name.
  const serverOrder = new Map(entries.map((entry, index) => [entry.universityId, index]));
  const order = (id: number) => serverOrder.get(id) ?? entries.length;
  const unrankedRank = entries.length + 1;
  return REAL_UNIVERSITIES.map((base) => {
    const entry = byId.get(base.id);
    const score = entry?.totalScore ?? 0;
    const rank = entry?.rank ?? unrankedRank;
    return {
      ...base,
      score,
      rank,
      rankChange: '-',
      campusLevel: getCampusStage(score).level,
      isMySchool: base.id === myUniversityId,
      isTop3: !!entry && rank <= 3,
      logoStatus: schoolLogoStatus(base.id),
      logoUrl: entry?.logoUrl ?? null,
    };
  }).sort((a, b) => a.rank - b.rank || order(a.id) - order(b.id) || a.name.localeCompare(b.name, 'ko') || a.id - b.id);
}

export function useApiMapSchools(): MapSchools {
  const query = useMapUniversities();
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  const myUniversityId = (signedIn ? me.data?.university.id : undefined) ?? null;
  const schools = useMemo(() => (query.data ? toMapSchoolViews(query.data, myUniversityId) : []), [query.data, myUniversityId]);
  const { refetch } = query;
  return {
    status: query.data ? 'ready' : query.isError ? 'failed' : 'loading',
    failureDetail: errorMessage(query.error),
    retry: () => { void refetch(); },
    schools,
  };
}

export const useMapSchools = selectByMode({ api: useApiMapSchools, mock: useMockMapSchools });
