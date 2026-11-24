import { useMemo } from 'react';
import { getSchoolViews, offListSchoolView, type SchoolView } from '../data/schoolDirectory.ts';
import { useMockGame } from './useMockGame.tsx';

/** School rows with my school's live score from the shared game state. My school is the player's school (M4). */
export function useSchoolViews(): SchoolView[] {
  const { state, player } = useMockGame();
  return useMemo(() => getSchoolViews(state.schoolScore, player.schoolId), [state.schoolScore, player.schoolId]);
}

/**
 * The player's school row: name, badge, rank and score shown in the HUD, chat and rankings. A school outside the
 * static list (the hidden operator school, #144) gets a row of its own from the server name instead of failing.
 */
export function useMySchool(): SchoolView {
  const { state, player } = useMockGame();
  const mySchool = useSchoolViews().find((school) => school.isMySchool);
  return useMemo(() => mySchool
    ?? offListSchoolView(player.schoolId, player.schoolName ?? '내 학교', state.schoolScore),
  [mySchool, player.schoolId, player.schoolName, state.schoolScore]);
}
