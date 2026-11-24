// 대결 상태 관리 컨텍스트 및 훅 (N4 대결 신청 및 현황)
import React, { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import {
  applyBattleRequest,
  cancelBattleRequest,
  createInitialBattleState,
  acceptBattleRequest,
  demoCancelConfirmedBattle,
  demoReceiveBattleRequest,
  rejectBattleRequest,
  getEffectiveBattleStatus,
  getOccupiedBattleForAlliance,
  isAllianceOccupied,
  type Battle,
  type BattleActionResult,
  type BattleConfig,
  type BattleState,
} from '../data/battleModel.ts';
import { MOCK_SHOP_CONFIG } from '../data/mockShopConfig.ts';
import { useDemoClock } from './useDemoClock.tsx';
import { useAlliance } from './useMockAlliance.tsx';

interface BattleContextValue {
  state: BattleState;
  config: BattleConfig;
  effectiveNow: number;
  /** 공용 가상 시계 오프셋(ms). 연합 만료와 같은 시계다. */
  clockOffsetMs: number;
  myAllianceId: string | null;
  myAllianceBattles: readonly Battle[];
  pendingBattles: readonly Battle[];
  activeBattles: readonly Battle[];
  finishedBattles: readonly Battle[];
  isMyAllianceOccupied: boolean;
  myOccupiedBattle: Battle | undefined;
  applyRequest: (targetAllianceId: string, durationHours: number, requestedStartTime: number) => BattleActionResult;
  cancelRequest: (battleId: string) => BattleActionResult;
  /** 내 연합이 받은 대결 신청 수락. 리더가 없으므로 내 연합 학교 학생 누구든 수락할 수 있다. */
  acceptRequest: (battleId: string) => BattleActionResult;
  /** 내 연합이 받은 대결 신청 거절 */
  rejectRequest: (battleId: string) => BattleActionResult;
  /** [시연] 상대 연합 명의의 수락 (보낸 신청 포함 어느 대기 신청이든) */
  demoAcceptRequest: (battleId: string) => BattleActionResult;
  /** [시연] 상대 연합 명의의 거절 */
  demoRejectRequest: (battleId: string) => BattleActionResult;
  /** [시연] 다른 연합이 내 연합에 대결을 신청한 상황 */
  demoReceiveRequest: () => BattleActionResult;
  demoCancelConfirmedRequest: (battleId: string) => BattleActionResult;
  fastForwardClock: (hours: number) => void;
  resetClock: () => void;
  toggleSimulateRequestFailure: () => void;
  toggleSimulateLoadFailure: () => void;
  retryLoad: () => void;
}

const BattleContext = createContext<BattleContextValue | null>(null);

export function BattleProvider({
  children,
  initialState,
  config = MOCK_SHOP_CONFIG.battle,
}: {
  children?: React.ReactNode;
  initialState?: BattleState;
  config?: BattleConfig;
}) {
  const { currentAlliance, state: allianceState } = useAlliance();
  const myAllianceId = currentAlliance?.id ?? null;
  const { offsetMs: clockOffsetMs, now: clockNow, fastForward: fastForwardClock, reset: resetClock } = useDemoClock();

  const [state, setState] = useState<BattleState>(() => {
    if (initialState) {
      return {
        ...initialState,
        config: config ?? initialState.config ?? MOCK_SHOP_CONFIG.battle,
      };
    }
    return createInitialBattleState(Date.now(), config);
  });
  const stateRef = useRef<BattleState>(state);
  stateRef.current = state;

  const effectiveNow = Date.now() + clockOffsetMs;

  // 동기식 원자적 커밋 함수 (P2와 동일한 stateRef 패턴으로 동일 틱 레이스 차단)
  const commit = useCallback(
    (actionFn: (current: BattleState) => { nextState: BattleState; result: BattleActionResult }): BattleActionResult => {
      const current = stateRef.current;
      const { nextState, result } = actionFn(current);
      if (result.success && nextState !== current) {
        stateRef.current = nextState;
        setState(nextState);
      }
      return result;
    },
    [],
  );

  const applyRequest = useCallback(
    (targetAllianceId: string, durationHours: number, requestedStartTime: number): BattleActionResult => {
      if (!myAllianceId) {
        return { success: false, error: '소속된 연합이 없습니다. 먼저 연합을 결성해 주세요.' };
      }
      const now = clockNow();
      return commit((current) =>
        applyBattleRequest(
          current,
          allianceState.alliances,
          myAllianceId,
          targetAllianceId,
          durationHours,
          requestedStartTime,
          now,
          current.config,
        ),
      );
    },
    [commit, clockNow, myAllianceId, allianceState.alliances],
  );

  const cancelRequest = useCallback(
    (battleId: string): BattleActionResult => {
      const now = clockNow();
      return commit((current) => cancelBattleRequest(current, battleId, now, current.config));
    },
    [commit, clockNow],
  );

  const demoAcceptRequest = useCallback(
    (battleId: string): BattleActionResult => {
      const now = clockNow();
      return commit((current) =>
        acceptBattleRequest(current, allianceState.alliances, battleId, now, current.config),
      );
    },
    [commit, clockNow, allianceState.alliances],
  );

  const demoRejectRequest = useCallback(
    (battleId: string): BattleActionResult => {
      const now = clockNow();
      return commit((current) => rejectBattleRequest(current, battleId, now, current.config));
    },
    [commit, clockNow],
  );

  const isReceivedByMe = (battleId: string) => {
    const target = stateRef.current.battles.find((b) => b.id === battleId);
    return !target || target.recipientAllianceId === myAllianceId;
  };

  const acceptRequest = useCallback(
    (battleId: string): BattleActionResult => {
      if (!isReceivedByMe(battleId)) return { success: false, error: '우리 연합이 받은 대결 신청만 수락할 수 있습니다.' };
      return demoAcceptRequest(battleId);
    },
    [demoAcceptRequest, myAllianceId],
  );

  const rejectRequest = useCallback(
    (battleId: string): BattleActionResult => {
      if (!isReceivedByMe(battleId)) return { success: false, error: '우리 연합이 받은 대결 신청만 거절할 수 있습니다.' };
      return demoRejectRequest(battleId);
    },
    [demoRejectRequest, myAllianceId],
  );

  const demoReceiveRequest = useCallback((): BattleActionResult => {
    if (!myAllianceId) return { success: false, error: '소속된 연합이 없습니다. 먼저 연합을 결성해 주세요.' };
    const now = clockNow();
    return commit((current) => demoReceiveBattleRequest(current, allianceState.alliances, myAllianceId, now, current.config));
  }, [commit, clockNow, myAllianceId, allianceState.alliances]);

  const demoCancelConfirmedRequest = useCallback(
    (battleId: string): BattleActionResult => {
      const now = clockNow();
      return commit((current) => demoCancelConfirmedBattle(current, battleId, now));
    },
    [commit, clockNow],
  );

  const toggleSimulateRequestFailure = useCallback(() => {
    const nextState = { ...stateRef.current, simulateRequestFailure: !stateRef.current.simulateRequestFailure };
    stateRef.current = nextState;
    setState(nextState);
  }, []);

  const toggleSimulateLoadFailure = useCallback(() => {
    const nextState = { ...stateRef.current, simulateLoadFailure: !stateRef.current.simulateLoadFailure };
    stateRef.current = nextState;
    setState(nextState);
  }, []);

  const retryLoad = useCallback(() => {
    const nextState = { ...stateRef.current, simulateLoadFailure: false };
    stateRef.current = nextState;
    setState(nextState);
  }, []);

  const myAllianceBattles = useMemo(() => {
    if (!myAllianceId) return [];
    return state.battles.filter(
      (b) => b.requesterAllianceId === myAllianceId || b.recipientAllianceId === myAllianceId,
    );
  }, [state.battles, myAllianceId]);

  const pendingBattles = useMemo(() => {
    return state.battles.filter((b) => getEffectiveBattleStatus(b, effectiveNow, state.config) === 'pending');
  }, [state.battles, effectiveNow, state.config]);

  const activeBattles = useMemo(() => {
    return state.battles.filter((b) => {
      const s = getEffectiveBattleStatus(b, effectiveNow, state.config);
      return s === 'active' || s === 'scheduled';
    });
  }, [state.battles, effectiveNow, state.config]);

  const finishedBattles = useMemo(() => {
    return state.battles.filter((b) => getEffectiveBattleStatus(b, effectiveNow, state.config) === 'finished');
  }, [state.battles, effectiveNow, state.config]);

  const isMyAllianceOccupied = useMemo(() => {
    if (!myAllianceId) return false;
    return isAllianceOccupied(state.battles, myAllianceId, effectiveNow);
  }, [state.battles, myAllianceId, effectiveNow]);

  const myOccupiedBattle = useMemo(() => {
    if (!myAllianceId) return undefined;
    return getOccupiedBattleForAlliance(state.battles, myAllianceId, effectiveNow);
  }, [state.battles, myAllianceId, effectiveNow]);

  const value = useMemo<BattleContextValue>(() => {
    return {
      state,
      config: state.config,
      effectiveNow,
      clockOffsetMs,
      myAllianceId,
      myAllianceBattles,
      pendingBattles,
      activeBattles,
      finishedBattles,
      isMyAllianceOccupied,
      myOccupiedBattle,
      applyRequest,
      cancelRequest,
      acceptRequest,
      rejectRequest,
      demoAcceptRequest,
      demoRejectRequest,
      demoReceiveRequest,
      demoCancelConfirmedRequest,
      fastForwardClock,
      resetClock,
      toggleSimulateRequestFailure,
      toggleSimulateLoadFailure,
      retryLoad,
    };
  }, [
    state,
    effectiveNow,
    clockOffsetMs,
    myAllianceId,
    myAllianceBattles,
    pendingBattles,
    activeBattles,
    finishedBattles,
    isMyAllianceOccupied,
    myOccupiedBattle,
    applyRequest,
    cancelRequest,
    acceptRequest,
    rejectRequest,
    demoAcceptRequest,
    demoRejectRequest,
    demoReceiveRequest,
    demoCancelConfirmedRequest,
    fastForwardClock,
    resetClock,
    toggleSimulateRequestFailure,
    toggleSimulateLoadFailure,
    retryLoad,
  ]);

  return <BattleContext.Provider value={value}>{children}</BattleContext.Provider>;
}

export function useBattle(): BattleContextValue {
  const ctx = useContext(BattleContext);
  if (!ctx) {
    throw new Error('useBattle must be used within BattleProvider');
  }
  return ctx;
}
