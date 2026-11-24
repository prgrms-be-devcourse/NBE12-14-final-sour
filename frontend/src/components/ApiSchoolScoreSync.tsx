import React, { useEffect, useRef } from 'react';
import { useAuthSession } from '../api/ApiProvider';
import { useMySchoolRanking, useOffListSchoolScore } from '../api/rankings';
import { useMyInfo } from '../api/users';
import { isListedSchool } from '../data/schoolDirectory';
import { useMockGame } from '../hooks/useMockGame';

interface ApiSchoolScoreSyncProps {
  getPendingScore: () => number;
  isSending: () => boolean;
}

/**
 * Mounted once at the app root in API mode (SCRUM-188): the main HUD's school score is the server's
 * `myUniversity.totalScore`, refetched every 30 seconds, plus this member's clicks the server has not confirmed yet.
 * It shares the query that feeds the HUD rank, so it adds no request. Until the first answer the game keeps its value.
 *
 * Click answers carry a newer total and still apply at once. Two rules keep an older ranking total from replacing one:
 * a ranking answer is skipped while a click batch is on its way (it may or may not count it), and a click answer
 * cancels a ranking request already in flight (useApiCampusClicks).
 *
 * A school outside the static list (the hidden operator school, #144) has no ranking row; its score comes from the
 * school detail instead (useOffListSchoolScore), under the same two rules.
 */
export const ApiSchoolScoreSync: React.FC<ApiSchoolScoreSyncProps> = ({ getPendingScore, isSending }) => {
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo().data;
  const viewerId = me?.id ?? null;
  const myUniversityId = me?.university.id ?? null;
  const offList = myUniversityId !== null && !isListedSchool(myUniversityId);
  const ranking = useMySchoolRanking(signedIn ? viewerId : null);
  const offListScore = useOffListSchoolScore(signedIn && offList ? viewerId : null, offList ? myUniversityId : null);
  // Null ranking row: the ranking has no score for the school yet, which the click script also starts from 0.
  const rankingScore = ranking.data === undefined ? undefined : ranking.data?.totalScore ?? 0;
  const score = offList ? offListScore.data : rankingScore;
  const updatedAt = offList ? offListScore.dataUpdatedAt : ranking.dataUpdatedAt;
  const { applyApiSchoolScore } = useMockGame();
  const latest = useRef({ applyApiSchoolScore, getPendingScore, isSending, score });
  latest.current = { applyApiSchoolScore, getPendingScore, isSending, score };
  useEffect(() => {
    const { score: current, isSending: sending, getPendingScore: pending, applyApiSchoolScore: apply } = latest.current;
    if (current === undefined || sending()) return;
    apply(current + pending());
    // The score too: two answers within one millisecond share dataUpdatedAt.
  }, [score, updatedAt]);
  return null;
};
