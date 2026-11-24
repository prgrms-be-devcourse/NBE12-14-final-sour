// The visited school (SCRUM-163). The mock reads the shared mock rows; the API puts the server's detail
// (GET /api/v1/universities/{id}) on the school directory row, which keeps the badge colour, city and region.
// Plan: .agents/01-PLAN/261118-2217-scrum163_visit_api.md.

import { useAuthSession } from '../api/ApiProvider';
import { errorMessage, isApiError } from '../api/errors';
import { selectByMode } from '../api/mode';
import { formatRankChange } from '../api/rankings';
import { useUniversityDetail, type UniversityDetail } from '../api/universities';
import { useMyInfo } from '../api/users';
import { offListSchoolView, type SchoolView } from '../data/schoolDirectory';
import type { SchoolId } from '../data/schoolIds';
import { schoolLogoStatus } from '../data/schoolLogos';
import { REAL_UNIVERSITIES } from '../data/universitiesData';
import { getCampusStage } from '../utils/gameModel';
import { useVisitedSchool } from '../components/VisitingParts';

/** A school row as the visit screen shows it: the server may have no rank yet, and then no rank change either. */
export type VisitSchoolView = Omit<SchoolView, 'rank' | 'rankChange'> & { rank: number | null; rankChange: string | null };

/** Server-only values of the info sheet. Null in the mock, which has no per-school members or contributors. */
export interface VisitSchoolExtras {
  memberCount: number;
  topDepartments: UniversityDetail['topDepartments'];
  topContributors: UniversityDetail['topContributors'];
}

export interface VisitSchool {
  status: 'loading' | 'failed' | 'not-found' | 'ready';
  failureDetail: string;
  retry: () => void;
  school: VisitSchoolView | undefined;
  /** For the spectator bar; null when signed out in API mode. */
  mySchoolShortName: string | null;
  /** The server says this is the viewer's own school: the screen sends them to their campus instead. */
  isMyUniversity: boolean;
  /** Same current alliance as the viewer's school (SCRUM-187): the campus assists instead of attacking. */
  isMyAlly: boolean;
  extras: VisitSchoolExtras | null;
}

export function useMockVisitSchool(schoolId: SchoolId | null): VisitSchool {
  const { school, mySchool } = useVisitedSchool(schoolId);
  return {
    status: school ? 'ready' : 'not-found',
    failureDetail: '',
    retry: () => undefined,
    school,
    mySchoolShortName: mySchool.shortName,
    isMyUniversity: false,
    isMyAlly: false,
    extras: null,
  };
}

/**
 * The directory row with the server's name, score and rank. A school outside the directory (the hidden operator
 * school, #144) gets a plain row from the server name: grey badge, no region.
 */
export function toVisitSchoolView(detail: UniversityDetail): VisitSchoolView {
  const base = REAL_UNIVERSITIES.find((university) => university.id === detail.id)
    ?? offListSchoolView(detail.id, detail.name, detail.totalScore, detail.isMyUniversity);
  return {
    ...base,
    name: detail.name,
    score: detail.totalScore,
    rank: detail.rank,
    rankChange: formatRankChange(detail.rankChange),
    campusLevel: getCampusStage(detail.totalScore).level,
    isMySchool: detail.isMyUniversity,
    isTop3: detail.rank !== null && detail.rank <= 3,
    logoStatus: schoolLogoStatus(base.id),
    logoUrl: detail.logoUrl,
  };
}

export function useApiVisitSchool(schoolId: SchoolId | null): VisitSchool {
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  const viewer = signedIn ? me.data : undefined;
  // The server decides: a school outside the directory may still exist (the hidden operator school, #144); else 404.
  const known = schoolId !== null;
  // Signed in, wait for MyInfo so the first request already carries the viewer (one cache entry, no refetch).
  const query = useUniversityDetail(known ? schoolId : null, { viewerId: viewer?.id ?? null, enabled: !signedIn || !!viewer });
  const myRow = viewer && REAL_UNIVERSITIES.find((university) => university.id === viewer.university.id);
  const school = query.data ? toVisitSchoolView(query.data) : undefined;
  const notFound = !known || (isApiError(query.error) && query.error.status === 404);
  const { refetch } = query;
  return {
    status: notFound ? 'not-found' : school ? 'ready' : query.isError || me.isError ? 'failed' : 'loading',
    failureDetail: errorMessage(query.error ?? me.error),
    retry: () => {
      void refetch();
      if (me.isError) void me.refetch();
    },
    school,
    mySchoolShortName: viewer ? (myRow?.shortName ?? viewer.university.name) : null,
    isMyUniversity: query.data?.isMyUniversity ?? false,
    isMyAlly: query.data?.isMyAlly ?? false,
    extras: query.data
      ? { memberCount: query.data.memberCount, topDepartments: query.data.topDepartments, topContributors: query.data.topContributors }
      : null,
  };
}

export const useVisitSchool = selectByMode({ api: useApiVisitSchool, mock: useMockVisitSchool });
