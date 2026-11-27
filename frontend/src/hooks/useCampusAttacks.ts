import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useApiClient, useAuthSession } from '../api/ApiProvider';
import { submitAttack, type AttackResponse } from '../api/attacks';
import { submitAssist, type AssistResponse } from '../api/assists';
import { allianceKeys } from '../api/alliances';
import { isClickLimitError, requiresClickVerification } from '../api/abuse';
import { errorMessage, isApiError } from '../api/errors';
import { selectByMode } from '../api/mode';
import { universityKeys, type UniversityDetail } from '../api/universities';
import { userKeys, useMyInfo, type MyInfo } from '../api/users';
import { createClickBatcher, type ClickBatchSnapshot, type TapPoints } from '../utils/clickBatcher';
import type { TapOutcome } from '../types';
import type { ClickUpgradeGate } from './useCampusClicks';

/** An attack on another school, or an assist to a school of my current alliance (SCRUM-187). */
export type CampusActionKind = 'attack' | 'assist';
export type CampusActionResult = ({ kind: 'attack' } & AttackResponse) | ({ kind: 'assist' } & AssistResponse);
export const CAMPUS_ACTION_LABEL: Record<CampusActionKind, string> = { attack: '공격', assist: '지원' };

export interface CampusAttacks {
  ready: boolean;
  myUniversityId: number | null;
  pendingTargetId: number | null;
  /** The kind of the queued batch; one target and one kind at a time. */
  pendingKind: CampusActionKind | null;
  pendingClicks: number;
  blocked: boolean;
  /** The tap's damage as the server applies it (crit, rate, ×0.3 or ×0.33 for a rival), or null when refused. */
  tap: (targetId: number, kind?: CampusActionKind) => TapOutcome | null;
  result: CampusActionResult | null;
  notice: string | null;
  retryLimited: (() => void) | null;
  botCheck: { open: boolean; onOpen: () => void; onClose: () => void; onVerified: () => void } | null;
}
const EMPTY: ClickBatchSnapshot = { pendingClicks: 0, pendingScore: 0, sending: false, error: null, recoverable: false, restoring: false, locked: false };
type AttackBatcher = ReturnType<typeof createClickBatcher<CampusActionResult>>;
const isAllyAttackRejection = (error: unknown) => isApiError(error) && error.kind === 'http'
  && error.status === 400 && error.code === 'ATTACK_ALLY_UNIVERSITY';
/** Server ratios on the base score (×100 here): AttackService 3/10 or 33/100 for a rival, AssistService 3/10 to the ally. */
const actionTapPoints = (kind: CampusActionKind, rival: boolean): TapPoints => (scaled) =>
  (kind === 'attack' && rival ? Math.floor((scaled * 33) / 10000) : Math.floor((scaled * 3) / 1000));
const isNonAllyAssistRejection = (error: unknown) => isApiError(error) && error.kind === 'http'
  && error.status === 400 && error.code === 'ASSIST_NOT_ALLY';

/**
 * App-root lifetime: navigation does not discard attacks or change a queued batch's target. Scores change only on the
 * server's answer; each tap only shows its click damage from the slots the last answer decided (ClickActionGuard).
 * Assists (SCRUM-187) share the queue, limits and recovery; a batch keeps the kind it was queued with.
 */
export function useApiCampusAttacks(beginAction: ClickUpgradeGate['beginUpgrade']): CampusAttacks {
  const client = useApiClient();
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  const userId = signedIn ? me.data?.id : undefined;
  const queryClient = useQueryClient();
  const queue = useRef<AttackBatcher | null>(null);
  const target = useRef<number | null>(null);
  const kind = useRef<CampusActionKind>('attack');
  const release = useRef<(() => void) | null>(null);
  // Rival groups are server config (RivalChecker): learn them from attack answers, 3/10 until one arrives.
  const rivals = useRef(new Map<number, boolean>());
  const [snapshot, setSnapshot] = useState(EMPTY);
  const [pendingTargetId, setPendingTargetId] = useState<number | null>(null);
  const [pendingKind, setPendingKind] = useState<CampusActionKind | null>(null);
  const [result, setResult] = useState<CampusActionResult | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [botOpen, setBotOpen] = useState(false);
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    if (userId === undefined) return;
    // Also cancel client-side fault delays: otherwise a delayed request could pick up the next account's token.
    const abort = new AbortController();
    const unlock = () => { release.current?.(); release.current = null; target.current = null; setPendingTargetId(null); setPendingKind(null); };
    const controller = createClickBatcher<CampusActionResult>({
      send: async (body) => {
        if (target.current === null) throw new Error(`${CAMPUS_ACTION_LABEL[kind.current]} 대상이 없습니다.`);
        const request = { ...body, targetUniversityId: target.current };
        try {
          return kind.current === 'assist'
            ? { kind: 'assist', ...await submitAssist(client, request, abort.signal) }
            : { kind: 'attack', ...await submitAttack(client, request, abort.signal) };
        } catch (error) {
          if (isAllyAttackRejection(error) && queue.current === controller) {
            void queryClient.invalidateQueries({ queryKey: universityKeys.detail(request.targetUniversityId, userId), exact: true });
          }
          throw error;
        }
      },
      // Attacks and assists each have their own server slots; tap() clears them when the kind changes.
      getSlots: (response) => (Array.isArray(response.criticalIndex) && Array.isArray(response.damageRateIndex) ? response : null),
      onRejected(error, rejectedClicks) {
        if (kind.current === 'assist' && isNonAllyAssistRejection(error)) {
          if (target.current !== null) void queryClient.invalidateQueries({ queryKey: universityKeys.detail(target.current, userId), exact: true });
          void queryClient.invalidateQueries({ queryKey: allianceKeys.mine(userId), exact: true });
          setResult(null);
          setNotice(`${errorMessage(error)} 거절된 지원 ${rejectedClicks}회는 취소했어요. 연합 정보를 다시 확인해 주세요.`);
          return true;
        }
        if (kind.current !== 'attack' || !isAllyAttackRejection(error)) return false;
        setResult(null);
        setNotice(`${errorMessage(error)} 거절된 공격 ${rejectedClicks}회는 취소했어요. 학교 정보를 다시 확인한 뒤 지원을 눌러 주세요.`);
        return true;
      },
      onChange(next) {
        setSnapshot(next);
        if (next.pendingClicks === 0) unlock();
      },
      async onAccepted(response) {
        // Polling started before this attack must not overwrite the accepted score with an older response.
        const key = universityKeys.detail(response.targetUniversity.id, userId);
        await Promise.all([
          queryClient.cancelQueries({ queryKey: key, exact: true }),
          queryClient.cancelQueries({ queryKey: userKeys.me, exact: true }),
        ]);
        if (queue.current !== controller || client.session.getState().status !== 'signed-in') return;
        if (response.kind === 'attack') rivals.current.set(response.targetUniversity.id, response.isRival);
        queryClient.setQueryData<UniversityDetail>(key, (current) => current
          ? { ...current, totalScore: response.targetUniversity.totalScore, rank: response.targetUniversity.rank }
          : current);
        queryClient.setQueryData<MyInfo>(userKeys.me, (current) => current?.id !== userId ? current
          : response.kind === 'assist' ? { ...current, totalContribScore: response.myContribScore }
            : { ...current, totalAttackScore: response.myAttackScore });
        setResult(response);
        setNotice(null);
      },
    });
    queue.current = controller;
    // Rivals depend on the member's own school: never carry them over to another account.
    rivals.current.clear();
    setSnapshot(EMPTY);
    setResult(null);
    setNotice(null);
    setBotOpen(false);
    const unsubscribe = client.session.subscribe(() => {
      if (client.session.getState().status === 'signed-in') return;
      const pending = controller.getSnapshot().pendingClicks;
      controller.dispose();
      abort.abort();
      if (queue.current === controller) queue.current = null;
      unlock();
      setSnapshot(EMPTY);
      setResult(null);
      setBotOpen(false);
      setNotice(pending ? `세션 종료로 미확정 ${CAMPUS_ACTION_LABEL[kind.current]} ${pending}회 전송을 중단했습니다. 이미 보낸 요청은 반영됐을 수 있으니 다시 로그인해 서버 점수를 확인해 주세요.` : null);
      setGeneration((value) => value + 1);
    });
    return () => {
      unsubscribe();
      controller.dispose();
      abort.abort();
      if (queue.current === controller) queue.current = null;
      unlock();
    };
  }, [client, queryClient, userId, generation]);

  const pending = snapshot.pendingClicks > 0;
  useEffect(() => {
    if (!pending) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [pending]);
  const needsBot = signedIn && requiresClickVerification(snapshot.error);
  useEffect(() => { if (needsBot) setBotOpen(true); }, [needsBot]);

  const tap = useCallback((targetId: number, nextKind: CampusActionKind = 'attack') => {
    const current = queryClient.getQueryData<MyInfo>(userKeys.me);
    if (client.session.getState().status !== 'signed-in' || userId === undefined || current?.id !== userId
      || current.university.id === targetId || !queue.current) return null;
    const label = CAMPUS_ACTION_LABEL[nextKind];
    if (target.current !== null && (target.current !== targetId || kind.current !== nextKind)) {
      setNotice(`이전 학교의 ${CAMPUS_ACTION_LABEL[kind.current]} 전송이 끝난 뒤 새 학교를 ${label}할 수 있어요.`);
      return null;
    }
    if (!release.current) {
      release.current = beginAction();
      if (!release.current) {
        setNotice(`내 학교 클릭·구매·강화 전송이 끝난 뒤 ${label}할 수 있어요.`);
        return null;
      }
    }
    // The slots in hand came from the other action's key (attacks:… vs assists:…), not this one.
    if (kind.current !== nextKind) queue.current.clearSlots();
    target.current = targetId;
    kind.current = nextKind;
    setPendingTargetId(targetId);
    setPendingKind(nextKind);
    // Scores still change only on the server's answer: the queue adds no optimistic points to any total.
    const estimate = queue.current.enqueueTap(current.clicker.clickPower, actionTapPoints(nextKind, rivals.current.get(targetId) ?? false));
    if (!estimate) return null;
    setNotice(null);
    return { points: estimate.points, isCrit: estimate.isCrit };
  }, [client, userId, queryClient, beginAction]);

  const retryLimited = useCallback(() => {
    const label = CAMPUS_ACTION_LABEL[kind.current];
    if (queue.current?.discardRateLimitedBatch()) setNotice(`서버가 거절한 ${label}은 제외했어요. 남은 대기 ${label}은 유지합니다.`);
  }, []);
  const verified = useCallback(() => {
    const resumed = queue.current?.resumeAfterVerification();
    setBotOpen(false);
    if (!resumed) setNotice(`이전 ${CAMPUS_ACTION_LABEL[kind.current]}의 처리 여부가 불명확해 재전송하지 않았어요. 새로고침해 서버 점수를 확인해 주세요.`);
  }, []);
  const failure = snapshot.restoring
    ? `점수 복구 중이에요. 자동으로 다시 보냅니다. 미확정 ${snapshot.pendingClicks}회`
    : snapshot.error
    ? `${errorMessage(snapshot.error)} 미확정 ${CAMPUS_ACTION_LABEL[kind.current]} ${snapshot.pendingClicks}회는 보류 중입니다. 처리 여부가 불명확하면 재전송하지 않습니다. 새로고침하면 미확정 ${CAMPUS_ACTION_LABEL[kind.current]}은 사라지고 서버 점수만 남습니다.`
    : null;
  return {
    ready: signedIn && userId !== undefined,
    myUniversityId: signedIn ? me.data?.university.id ?? null : null,
    pendingTargetId, pendingKind, pendingClicks: snapshot.pendingClicks, blocked: snapshot.error !== null || snapshot.restoring, tap,
    result: signedIn ? result : null,
    notice: failure ? `${notice ? `${notice} ` : ''}${failure}` : notice,
    retryLimited: signedIn && snapshot.recoverable && isClickLimitError(snapshot.error) ? retryLimited : null,
    botCheck: needsBot ? { open: botOpen, onOpen: () => setBotOpen(true), onClose: () => setBotOpen(false), onVerified: verified } : null,
  };
}

const useMockCampusAttacks = (_beginAction: ClickUpgradeGate['beginUpgrade']): null => null;
export const useCampusAttacks = selectByMode<(beginAction: ClickUpgradeGate['beginUpgrade']) => CampusAttacks | null>({ api: useApiCampusAttacks, mock: useMockCampusAttacks });
