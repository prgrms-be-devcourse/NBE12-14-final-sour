import { selectByMode } from '../api/mode';
import { useUniversityDetail } from '../api/universities';
import { useMyInfo } from '../api/users';
import type { CampusSignInfo } from '../components/CampusSignText';
import { getMockRankings } from '../data/mockRankings';
import { useMockGame } from './useMockGame';

/**
 * The school's first-ranked department for its campus signboard (user request 2026-11-27). API mode reads the
 * school detail's `topDepartments`, refreshed with the detail; null while loading or when the school has none.
 */
function useApiCampusTopDepartment(universityId: number | null): CampusSignInfo | null {
  const { data: me } = useMyInfo();
  const { data } = useUniversityDetail(universityId, { viewerId: me?.id ?? null });
  const top = data?.topDepartments.find((row) => row.rank === 1) ?? data?.topDepartments[0];
  return top ? { name: top.name, score: top.totalScore } : null;
}

/** Mock mode: the top row of the mock department ranking of the player's school. */
function useMockCampusTopDepartment(_universityId: number | null): CampusSignInfo | null {
  const { state, player } = useMockGame();
  const top = getMockRankings(state, player).dept[0];
  return top ? { name: top.name, score: top.score } : null;
}

export const useCampusTopDepartment = selectByMode({ api: useApiCampusTopDepartment, mock: useMockCampusTopDepartment });
