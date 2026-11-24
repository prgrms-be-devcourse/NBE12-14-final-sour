import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useApiClient, useAuthSession } from '../api/ApiProvider';
import { useClickerLevels, upgradeClicker, type ClickerLevel } from '../api/clickers';
import { errorMessage, isApiError } from '../api/errors';
import { selectByMode } from '../api/mode';
import { userKeys, useMyInfo, type MyInfo } from '../api/users';
import type { ClickUpgradeGate } from './useCampusClicks';
import { useMockGame } from './useMockGame';

interface ClickerView {
  level: number | null;
  power: number;
  normalPower: number;
  criticalPower: number;
  upgradeCost: number | null;
  nextClicker: { level: number; power: number } | null;
  api: null | {
    levels: readonly ClickerLevel[];
    loadNotice: string | null;
    reload: () => void;
    pending: boolean;
    canUpgrade: boolean;
    disabledReason: string | null;
    error: string | null;
    status: string | null;
    upgrade: () => void;
    reconcile: (() => void) | null;
  };
}

type UpgradePhase = 'idle' | 'upgrading' | 'uncertain' | 'reconciling';
interface Attempt {
  userId: number;
  initialLevel: number;
  needsObservedUpgrade: boolean;
  abort: AbortController;
  release: () => void;
}
const ClickerContext = createContext<ClickerView | null>(null);

function useMockClicker(): ClickerView {
  const game = useMockGame();
  return {
    level: game.clicker.level, power: game.clicker.power,
    normalPower: game.normalPower, criticalPower: game.criticalPower,
    upgradeCost: game.clicker.upgradeCost, nextClicker: game.nextClicker, api: null,
  };
}

/** One controller at the app root: tab changes cannot abandon an in-flight, non-idempotent upgrade. */
export function useApiClickerController(gate: ClickUpgradeGate): ClickerView {
  const client = useApiClient();
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  const levels = useClickerLevels();
  const queryClient = useQueryClient();
  const [phase, setPhase] = useState<UpgradePhase>('idle');
  const phaseRef = useRef<UpgradePhase>('idle');
  const attempt = useRef<Attempt | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const userId = signedIn ? me.data?.id : undefined;

  useEffect(() => {
    const cancel = () => {
      attempt.current?.abort.abort();
      attempt.current?.release();
      attempt.current = null;
      phaseRef.current = 'idle';
    };
    setPhase('idle');
    setError(null);
    setStatus(null);
    const unsubscribe = client.session.subscribe(() => {
      if (client.session.getState().status === 'signed-in') return; // Token refresh is not a new game session.
      cancel();
      setPhase('idle');
      setError(null);
      setStatus(null);
    });
    return () => { unsubscribe(); cancel(); };
  }, [client, userId]);

  const active = (work: Attempt) => attempt.current === work && !work.abort.signal.aborted
    && client.session.getState().status === 'signed-in'
    && queryClient.getQueryData<MyInfo>(userKeys.me)?.id === work.userId;
  const changePhase = (value: UpgradePhase) => { phaseRef.current = value; setPhase(value); };
  const finish = (work: Attempt) => { attempt.current = null; work.release(); changePhase('idle'); };

  const runUpgrade = async () => {
    if (phaseRef.current !== 'idle' || !signedIn || loadNotice || !levels.data?.length) return;
    const current = queryClient.getQueryData<MyInfo>(userKeys.me);
    if (!current || current.id !== userId || current.clicker.nextLevelRequiredScore === null) return;
    if (current.totalContribScore < current.clicker.nextLevelRequiredScore) {
      setError('업그레이드에 필요한 기여도가 부족합니다.');
      return;
    }
    const release = gate.beginUpgrade();
    if (!release) { setError('미확정 클릭을 모두 전송한 뒤 강화할 수 있습니다.'); return; }
    const work: Attempt = { userId: current.id, initialLevel: current.clicker.level, needsObservedUpgrade: false, abort: new AbortController(), release };
    attempt.current = work;
    changePhase('upgrading');
    setError(null);
    setStatus(null);
    try {
      await queryClient.cancelQueries({ queryKey: userKeys.me, exact: true });
      if (!active(work)) return;
      const result = await upgradeClicker(client, work.abort.signal);
      if (!active(work)) return;
      // A focus refetch started during the POST must not later overwrite its newer result.
      await queryClient.cancelQueries({ queryKey: userKeys.me, exact: true });
      if (!active(work)) return;
      queryClient.setQueryData<MyInfo>(userKeys.me, (profile) => profile?.id === work.userId ? {
        ...profile,
        totalContribScore: result.myContribScore,
        clicker: { level: result.level, clickPower: result.clickPower, nextLevelRequiredScore: result.nextLevelRequiredScore },
      } : profile);
      setStatus(`클리커 Lv.${result.level} 강화 완료! 클릭당 ${result.clickPower}점입니다.`);
      finish(work);
    } catch (failure) {
      if (!active(work)) return;
      setError(errorMessage(failure));
      // No submission ID exists for upgrades. Never offer blind POST replay after an uncertain result/conflict.
      work.needsObservedUpgrade = !isApiError(failure) || failure.kind === 'network' || failure.kind === 'invalid-response'
        || (failure.status !== null && failure.status >= 500);
      const uncertain = work.needsObservedUpgrade || (isApiError(failure)
        && (failure.status === 409 || failure.code === 'CLICKER_MAX_LEVEL'));
      if (uncertain) changePhase('uncertain');
      else finish(work);
    }
  };

  const reconcile = async () => {
    const work = attempt.current;
    if (!work || phaseRef.current !== 'uncertain' || !active(work)) return;
    changePhase('reconciling');
    try {
      await queryClient.cancelQueries({ queryKey: userKeys.me, exact: true });
      if (!active(work)) return;
      const profile = await client.get<MyInfo>('/users/me', { signal: work.abort.signal });
      if (!active(work)) return;
      if (profile.id !== work.userId) throw new Error('Unexpected profile');
      queryClient.setQueryData(userKeys.me, profile);
      if (work.needsObservedUpgrade && profile.clicker.level <= work.initialLevel) {
        // A GET returning the old level cannot prove the original POST has stopped processing.
        // Keep the lock; replaying a non-idempotent upgrade could otherwise buy two levels.
        setError('아직 새 레벨이 확인되지 않았습니다. 원래 요청이 처리 중일 수 있어 클릭과 강화를 계속 보류합니다. 잠시 후 서버 상태를 다시 확인해 주세요.');
        changePhase('uncertain');
        return;
      }
      setError(null);
      setStatus('서버 상태를 다시 불러왔습니다. 현재 레벨과 잔액을 확인한 뒤 강화해 주세요.');
      finish(work);
    } catch (failure) {
      if (!active(work)) return;
      setError(`서버 상태를 확인하지 못했습니다. ${errorMessage(failure)}`);
      changePhase('uncertain');
    }
  };

  const current = signedIn ? me.data : undefined;
  const next = levels.data?.find((row) => row.level === (current?.clicker.level ?? 0) + 1);
  const cost = current?.clicker.nextLevelRequiredScore ?? null;
  const loadNotice = !signedIn ? '클리커 정보를 보려면 로그인해 주세요.'
    : me.isError ? `내 정보를 불러오지 못했습니다. ${errorMessage(me.error)}`
    : levels.isError ? `클리커 레벨표를 불러오지 못했습니다. ${errorMessage(levels.error)}`
    : !current || levels.isPending ? '클리커 정보를 불러오는 중…'
    : !levels.data?.length ? '클리커 레벨표가 비어 있습니다.'
    : cost !== null && !next ? '다음 클리커 레벨 정보를 확인할 수 없습니다.' : null;
  const disabledReason = loadNotice ?? (phase === 'uncertain' ? '다시 강화하기 전에 서버 상태를 확인해 주세요.'
    : phase !== 'idle' ? '강화 결과를 확인하는 중…'
    : cost === null ? '최고 단계입니다.'
    : gate.upgradeBlocked ? '미확정 클릭을 모두 전송한 뒤 강화할 수 있습니다.'
    : current && current.totalContribScore < cost ? '업그레이드에 필요한 기여도가 부족합니다.' : null);
  return {
    level: current?.clicker.level ?? null, power: current?.clicker.clickPower ?? 0,
    normalPower: current?.clicker.clickPower ?? 0, criticalPower: current?.clicker.clickPower ?? 0,
    upgradeCost: cost, nextClicker: cost !== null && next ? { level: next.level, power: next.clickPower } : null,
    api: {
      levels: levels.data ?? [], loadNotice,
      reload: () => { void levels.refetch(); if (signedIn) void me.refetch(); },
      pending: phase === 'upgrading' || phase === 'reconciling', canUpgrade: disabledReason === null,
      disabledReason, error, status,
      upgrade: () => { void runUpgrade(); }, reconcile: phase === 'uncertain' ? () => { void reconcile(); } : null,
    },
  };
}

export const useClickerController = selectByMode({ api: useApiClickerController, mock: useMockClicker });

export function ClickerProvider({ value, children }: { value: ClickerView; children: ReactNode }) {
  return <ClickerContext.Provider value={value}>{children}</ClickerContext.Provider>;
}

function useApiClicker(): ClickerView {
  const value = useContext(ClickerContext);
  if (!value) throw new Error('API 클리커는 앱 루트의 ClickerProvider 안에서 사용하세요.');
  return value;
}
export const useClicker = selectByMode({ api: useApiClicker, mock: useMockClicker });
