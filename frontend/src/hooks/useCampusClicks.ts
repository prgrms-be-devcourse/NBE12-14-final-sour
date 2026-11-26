import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useApiClient, useAuthSession } from '../api/ApiProvider';
import { submitClicks, type ClickResponse } from '../api/clicks';
import { isClickLimitError, requiresClickVerification } from '../api/abuse';
import { errorMessage } from '../api/errors';
import { selectByMode } from '../api/mode';
import { rankingKeys } from '../api/rankings';
import { userKeys, useMyInfo, type MyInfo } from '../api/users';
import { createClickBatcher, type ClickBatchSnapshot } from '../utils/clickBatcher';
import { createClickLocation, type ClickLocationSnapshot } from '../utils/clickLocation';
import { createOtherClicksEstimator, createOtherClicksFeed, type OtherClicksFeed } from '../utils/otherClicksFeed';
import { useMockGame } from './useMockGame';

export interface ClickUpgradeGate {
  upgradeBlocked: boolean;
  beginUpgrade: () => (() => void) | null;
}

export interface LibraryClickControl {
  location: ClickLocationSnapshot;
  request: () => void;
  clear: () => void;
  lastResult: Pick<ClickResponse, 'libraryBonus' | 'multiplier'> | null;
}
interface CampusClicks extends ClickUpgradeGate {
  library: LibraryClickControl | null;
  tap: (isCrit: boolean) => number | null;
  notice: string | null;
  getPendingScore: () => number;
  /** True while a batch is on its way: the server may or may not count it yet (SCRUM-188). */
  isSending: () => boolean;
  retryLimited: (() => void) | null;
  botCheck: { open: boolean; onOpen: () => void; onClose: () => void; onVerified: () => void } | null;
  /** API mode: other students' click estimates from click responses (SCRUM-159). Null keeps the mock polling. */
  otherClicksFeed: OtherClicksFeed | null;
}
const noPendingScore = () => 0;
const notSending = () => false;
const EMPTY_BATCH: ClickBatchSnapshot = { pendingClicks: 0, pendingScore: 0, sending: false, error: null, recoverable: false, restoring: false, locked: false };

function useMockCampusClicks(): CampusClicks {
  const { tap } = useMockGame();
  return { tap, notice: null, getPendingScore: noPendingScore, isSending: notSending, retryLimited: null, botCheck: null, upgradeBlocked: false, beginUpgrade: () => null, otherClicksFeed: null, library: null };
}

/** Mounted at the app root, not on the campus screen: changing tabs must not cancel a collected batch. */
export function useApiCampusClicks(): CampusClicks {
  const client = useApiClient();
  const signedIn = useAuthSession().status === 'signed-in';
  const { data: me } = useMyInfo();
  const queryClient = useQueryClient();
  const { applyApiTap, applyApiScores, player } = useMockGame();
  const batcher = useRef<ReturnType<typeof createClickBatcher> | null>(null);
  const [snapshot, setSnapshot] = useState(EMPTY_BATCH);
  const [cancelledClicks, setCancelledClicks] = useState(0);
  const [queueGeneration, setQueueGeneration] = useState(0);
  const [botCheckOpen, setBotCheckOpen] = useState(false);
  const [recoveryNotice, setRecoveryNotice] = useState<string | null>(null);
  const [otherClicksFeed] = useState(createOtherClicksFeed);
  const userId = signedIn ? me?.id : undefined;
  const location = useMemo(() => createClickLocation(), [client, userId]);
  const locationSnapshot = useSyncExternalStore(location.subscribe, location.getSnapshot, location.getSnapshot);
  const [libraryResult, setLibraryResult] = useState<LibraryClickControl['lastResult']>(null);
  useEffect(() => () => location.clear(), [location]);

  useEffect(() => {
    setLibraryResult(null);
    if (userId === undefined) return;
    // One per queue: own batches of a previous member or session must not be subtracted from this one's counts.
    const otherClicks = createOtherClicksEstimator();
    const controller = createClickBatcher({
      send: (body) => submitClicks(client, body),
      getCoordinates: location.getCoordinates,
      onChange: setSnapshot,
      onAccepted(response, pendingScore, { request, sentAt }) {
        setRecoveryNotice(null);
        // A school ranking requested before this answer reads an older total; drop it so it cannot land after (SCRUM-188).
        void queryClient.cancelQueries({ queryKey: rankingKeys.mySchool(userId) });
        setLibraryResult({ libraryBonus: response.libraryBonus, multiplier: response.multiplier });
        otherClicksFeed.publish(otherClicks.accept(response.schoolClicksLastSecond, request.clickCount, sentAt));
        applyApiScores({
          contribution: response.myContribScore + pendingScore,
          schoolScore: response.university.totalScore + pendingScore,
          departmentScore: response.department.totalScore + pendingScore,
        });
        // Keep the profile fresh too. ApiPlayerSync adds the still-unconfirmed optimistic offset when applying it.
        queryClient.setQueryData<MyInfo>(userKeys.me, (current) => current?.id === userId
          ? { ...current, totalContribScore: response.myContribScore }
          : current);
      },
    });
    batcher.current = controller;
    setSnapshot(EMPTY_BATCH);
    setBotCheckOpen(false);
    setRecoveryNotice(null);
    // Session loss invalidates the queue synchronously, before React can render a new signed-in member.
    const unsubscribe = client.session.subscribe(() => {
      if (client.session.getState().status === 'signed-in') return;
      controller.dispose();
      location.clear();
      setLibraryResult(null);
      if (batcher.current === controller) batcher.current = null;
      setCancelledClicks(controller.getSnapshot().pendingClicks);
      setBotCheckOpen(false);
      setQueueGeneration((generation) => generation + 1);
      setSnapshot(EMPTY_BATCH);
    });
    return () => {
      unsubscribe();
      controller.dispose();
      if (batcher.current === controller) batcher.current = null;
    };
  }, [client, queryClient, userId, applyApiScores, queueGeneration, otherClicksFeed, location]);

  useEffect(() => {
    if (signedIn && requiresClickVerification(snapshot.error)) setBotCheckOpen(true);
  }, [signedIn, snapshot.error]);

  const hasPendingClicks = snapshot.pendingClicks > 0;
  useEffect(() => {
    if (!hasPendingClicks) return;
    const warnBeforeLeaving = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', warnBeforeLeaving);
    return () => window.removeEventListener('beforeunload', warnBeforeLeaving);
  }, [hasPendingClicks]);

  const getPendingScore = useCallback(() => batcher.current?.getSnapshot().pendingScore ?? 0, []);
  const isSending = useCallback(() => batcher.current?.getSnapshot().sending ?? false, []);
  const ready = signedIn && me !== undefined && player.userId === String(me.id);
  const tap = useCallback((_isCrit: boolean): number | null => {
    if (!ready || !me || !batcher.current) return null;
    if (requiresClickVerification(batcher.current.getSnapshot().error)) {
      setBotCheckOpen(true);
      return null;
    }
    // The click endpoint has no critical-hit field; its score uses the signed-in member's actual click power.
    const current = queryClient.getQueryData<MyInfo>(userKeys.me);
    if (!current || current.id !== me.id) return null;
    const points = current.clicker.clickPower;
    if (!batcher.current.enqueue(points)) return null;
    setCancelledClicks(0);
    applyApiTap(points);
    return points;
  }, [ready, me, applyApiTap, queryClient]);

  const beginUpgrade = useCallback(() => batcher.current?.acquireIdleLock() ?? null, []);
  const retryLimited = useCallback(() => {
    const rejected = batcher.current?.discardRateLimitedBatch();
    if (!rejected) return;
    applyApiTap(-rejected.score);
    setRecoveryNotice(`서버가 거절한 ${rejected.clicks}회는 적립되지 않았습니다. 남은 대기 클릭은 유지하며 다시 클릭할 수 있습니다.`);
  }, [applyApiTap]);
  const openBotCheck = useCallback(() => setBotCheckOpen(true), []);
  const closeBotCheck = useCallback(() => setBotCheckOpen(false), []);
  const completeBotCheck = useCallback(() => {
    const resumed = batcher.current?.resumeAfterVerification();
    setBotCheckOpen(false);
    if (!resumed) setRecoveryNotice('봇 확인은 완료했지만 이전 제출의 처리 여부가 불명확해 재전송하지 않았습니다. 새로고침해 서버 점수를 확인해 주세요.');
  }, []);
  const canRetryLimited = signedIn && snapshot.recoverable && isClickLimitError(snapshot.error);
  const needsVerification = signedIn && requiresClickVerification(snapshot.error);
  const getNotice = (): string | null => {
    if (snapshot.restoring) return `점수 복구 중이에요. 자동으로 다시 보냅니다. 미확정 ${snapshot.pendingClicks}회`;
    if (snapshot.error) {
      let guidance = '중복 적립 방지를 위해 추가 클릭과 재전송을 중단했습니다.';
      if (canRetryLimited) guidance = '잠시 기다린 뒤 아래 버튼을 누르면 거절된 배치의 점수를 되돌리고 남은 대기 클릭을 재개합니다.';
      else if (needsVerification && snapshot.recoverable) guidance = '자동 입력 방지 확인을 마치면 같은 배치로 다시 전송합니다.';
      const prefix = recoveryNotice ? `${recoveryNotice} ` : '';
      return `${prefix}${errorMessage(snapshot.error)} 미확정 ${snapshot.pendingClicks}회 전송을 보류했습니다. ${guidance} 새로고침하면 미확정 클릭은 사라지며 서버에 반영된 점수만 남습니다.`;
    }
    if (cancelledClicks > 0) return `세션 종료로 미확정 ${cancelledClicks}회 전송을 중단했습니다. 이미 전송한 요청은 반영되었을 수 있으므로 다시 로그인해 점수를 확인해 주세요.`;
    if (signedIn) {
      if (ready) return snapshot.locked ? '구매·강화·공격 결과를 확인하는 동안 클릭을 잠시 멈춥니다.' : recoveryNotice;
      return '내 정보를 불러온 뒤 클릭할 수 있습니다.';
    }
    return '클릭 점수를 적립하려면 로그인해 주세요.';
  };
  return {
    tap, notice: getNotice(), getPendingScore, isSending,
    library: userId === undefined ? null : { location: locationSnapshot, request: location.request, clear: location.clear, lastResult: libraryResult },
    upgradeBlocked: snapshot.pendingClicks > 0 || snapshot.locked,
    beginUpgrade,
    retryLimited: canRetryLimited ? retryLimited : null,
    botCheck: needsVerification ? { open: botCheckOpen, onOpen: openBotCheck, onClose: closeBotCheck, onVerified: completeBotCheck } : null,
    otherClicksFeed,
  };
}

export const useCampusClicks = selectByMode({ api: useApiCampusClicks, mock: useMockCampusClicks });
