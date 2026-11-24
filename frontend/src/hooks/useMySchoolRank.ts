// The main screen's own-school rank and rank change (SCRUM-184). The API mode reads the server's `myUniversity`; the mock
// keeps the rank computed from the demo rows.

import { useMemo } from 'react';
import { useAuthSession } from '../api/ApiProvider';
import { selectByMode } from '../api/mode';
import { formatRankChange, useMySchoolRanking, useOffListSchoolScore } from '../api/rankings';
import { useMyInfo } from '../api/users';
import { getSchoolRankings } from '../data/mockRankings';
import { isListedSchool } from '../data/schoolDirectory';
import type { SchoolId } from '../data/schoolIds';

export interface MySchoolRank {
  /** Null until the server answers, signed out, or the school has no score yet: the HUD shows "순위 없음". */
  rank: number | null;
  /** Badge text (▲n, ▼n, -); null hides it. */
  change: string | null;
  /** Snapshot paired with the rank; null when there is no own-school row. */
  score: number | null;
  scoreStatus: 'ready' | 'loading' | 'failed' | 'unranked';
  retry?: () => void;
}

function useMockMySchoolRank(schoolScore: number, schoolId: SchoolId): MySchoolRank {
  return useMemo(() => {
    const mine = getSchoolRankings(schoolScore, schoolId).find((school) => school.isMySchool)!;
    return { rank: mine.rank, change: mine.change, score: schoolScore, scoreStatus: 'ready' };
  }, [schoolScore, schoolId]);
}

// The demo score is not the server's, so the API mode ignores it.
export function useApiMySchoolRank(_schoolScore: number, _schoolId: SchoolId): MySchoolRank {
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo().data;
  const viewerId = me?.id ?? null;
  const myUniversityId = me?.university.id ?? null;
  const offList = myUniversityId !== null && !isListedSchool(myUniversityId);
  const ranking = useMySchoolRanking(signedIn ? viewerId : null);
  const offListScore = useOffListSchoolScore(signedIn && offList ? viewerId : null, offList ? myUniversityId : null);
  // The hidden operator school (#144) has a score but never a national rank.
  if (offList) {
    return {
      rank: null,
      change: null,
      score: offListScore.data ?? null,
      scoreStatus: offListScore.data !== undefined ? 'unranked' : offListScore.isError ? 'failed' : 'loading',
      retry: () => { void offListScore.refetch(); },
    };
  }
  const mine = ranking.data;
  return {
    rank: mine?.rank ?? null,
    change: formatRankChange(mine?.rankChange),
    score: mine?.totalScore ?? null,
    scoreStatus: schoolScoreStatus(mine, ranking.isError),
    retry: () => { void ranking.refetch(); },
  };
}

function schoolScoreStatus(mine: unknown, isError: boolean): MySchoolRank['scoreStatus'] {
  if (mine) return 'ready';
  if (mine === null) return 'unranked';
  return isError ? 'failed' : 'loading';
}

export const useMySchoolRank = selectByMode({ api: useApiMySchoolRank, mock: useMockMySchoolRank });
