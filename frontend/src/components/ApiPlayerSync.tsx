import React, { useEffect, useRef } from 'react';
import { accountFromMyInfo, useMyInfo } from '../api/users';
import { useMockGame } from '../hooks/useMockGame';

/**
 * Mounted once at the app root in API mode (SCRUM-153): while signed in, the game's player, contribution and clicker
 * level come from GET /users/me instead of the mock defaults. Applied when the profile changes, so a refetch with the
 * same values leaves the game alone.
 */
export const ApiPlayerSync: React.FC<{ getPendingScore?: () => number }> = ({ getPendingScore }) => {
  const { data } = useMyInfo();
  const { applyAccount } = useMockGame();
  const applyRef = useRef(applyAccount);
  const pendingRef = useRef(getPendingScore);
  applyRef.current = applyAccount;
  pendingRef.current = getPendingScore;
  useEffect(() => {
    if (data) applyRef.current({ ...accountFromMyInfo(data), contribution: data.totalContribScore + (pendingRef.current?.() ?? 0) });
  }, [data]);
  return null;
};
