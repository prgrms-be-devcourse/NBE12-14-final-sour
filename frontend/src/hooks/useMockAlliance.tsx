import type { SchoolId } from '../data/schoolIds.ts';
import React, { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import {
  acceptAllianceRequest,
  applyAllianceRequest,
  cancelAllianceRequest,
  demoReceiveAllianceProposal,
  getAllianceForSchool,
  getLatestExpiredAllianceForSchool,
  INITIAL_ALLIANCE_STATE,
  rejectAllianceRequest,
  type Alliance,
  type AllianceActionResult,
  type AllianceRequest,
  type AllianceState,
} from '../data/allianceModel.ts';
import { DemoClockProvider, useDemoClock } from './useDemoClock.tsx';
import { useMockGame } from './useMockGame.tsx';

interface AllianceContextValue {
  state: AllianceState;
  mySchoolId: SchoolId;
  /** 가상 시계 기준 현재 시각 (렌더 시점) */
  effectiveNow: number;
  /** 가상 시계 오프셋(ms) */
  clockOffsetMs: number;
  /** 지금 활성인 내 학교 연합. 만료되면 null */
  currentAlliance: Alliance | null;
  /** 내 학교가 소속됐다가 가장 최근 만료된 연합 (안내용) */
  latestExpiredAlliance: Alliance | null;
  sentRequests: readonly AllianceRequest[];
  receivedRequests: readonly AllianceRequest[];
  pendingRequests: readonly AllianceRequest[];
  simulateRequestFailure: boolean;
  simulateLoadFailure: boolean;
  applyRequest: (targetSchoolId: SchoolId, durationHours?: number) => AllianceActionResult;
  cancelRequest: (requestId: string) => AllianceActionResult;
  /** 내 학교가 받은 제안 수락. 리더가 없으므로 내 학교 학생 누구든 수락할 수 있다. */
  acceptRequest: (requestId: string) => AllianceActionResult;
  /** 내 학교가 받은 제안 거절 */
  rejectRequest: (requestId: string) => AllianceActionResult;
  /** [시연] 내가 보낸 제안을 상대 학교 학생이 수락한 상황 */
  demoAcceptRequest: (requestId: string) => AllianceActionResult;
  /** [시연] 내가 보낸 제안을 상대 학교 학생이 거절한 상황 */
  demoRejectRequest: (requestId: string) => AllianceActionResult;
  /** [시연] 다른 학교가 내 학교에 연합을 제안한 상황 */
  demoReceiveProposal: () => AllianceActionResult;
  fastForwardClock: (hours: number) => void;
  resetClock: () => void;
  toggleSimulateRequestFailure: () => void;
  toggleSimulateLoadFailure: () => void;
  retryLoad: () => void;
}

const AllianceContext = createContext<AllianceContextValue | null>(null);

/**
 * 연합 상태와 공용 가상 시계를 함께 제공한다. 연합 만료와 대결 진행이 같은 시각으로 판정되도록
 * BattleProvider는 이 안쪽에서 useDemoClock()으로 같은 시계를 쓴다.
 */
export function AllianceProvider({
  children,
  initialState = INITIAL_ALLIANCE_STATE,
  initialClockOffsetMs = 0,
}: {
  children?: React.ReactNode;
  initialState?: AllianceState;
  initialClockOffsetMs?: number;
}) {
  return (
    <DemoClockProvider initialOffsetMs={initialClockOffsetMs}>
      <AllianceStateProvider initialState={initialState}>{children}</AllianceStateProvider>
    </DemoClockProvider>
  );
}

function AllianceStateProvider({ children, initialState }: { children?: React.ReactNode; initialState: AllianceState }) {
  const { player } = useMockGame();
  const mySchoolId = player.schoolId;
  const clock = useDemoClock();

  const [state, setState] = useState<AllianceState>(initialState);
  const stateRef = useRef<AllianceState>(initialState);
  stateRef.current = state;

  // 오프셋이 바뀌면 리렌더되므로 렌더 시점 시각으로 만료를 판정한다.
  const effectiveNow = Date.now() + clock.offsetMs;
  const currentAlliance = getAllianceForSchool(state.alliances, mySchoolId, effectiveNow) ?? null;
  const latestExpiredAlliance = currentAlliance
    ? null
    : (getLatestExpiredAllianceForSchool(state.alliances, mySchoolId, effectiveNow) ?? null);

  const sentRequests = useMemo(() => {
    return state.requests.filter((r) => r.requesterSchoolId === mySchoolId);
  }, [state.requests, mySchoolId]);

  const receivedRequests = useMemo(() => {
    return state.requests.filter((r) => r.recipientSchoolId === mySchoolId);
  }, [state.requests, mySchoolId]);

  const pendingRequests = useMemo(() => {
    return state.requests.filter(
      (r) => r.status === 'pending' && (r.requesterSchoolId === mySchoolId || r.recipientSchoolId === mySchoolId),
    );
  }, [state.requests, mySchoolId]);

  // Synchronous commit via stateRef:
  // Same-tick calls (double click, immediate sequential calls before re-render)
  // always validate against the latest synchronously committed state.
  const commit = useCallback(
    (actionFn: (current: AllianceState) => { nextState: AllianceState; result: AllianceActionResult }): AllianceActionResult => {
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

  const { now } = clock;

  const applyRequest = useCallback(
    (targetSchoolId: SchoolId, durationHours?: number): AllianceActionResult => {
      return commit((current) => applyAllianceRequest(current, mySchoolId, targetSchoolId, now(), durationHours));
    },
    [commit, mySchoolId, now],
  );

  const cancelRequest = useCallback(
    (requestId: string): AllianceActionResult => {
      return commit((current) => cancelAllianceRequest(current, requestId, now()));
    },
    [commit, now],
  );

  const findRequest = (requestId: string) => stateRef.current.requests.find((r) => r.id === requestId);

  const acceptRequest = useCallback(
    (requestId: string): AllianceActionResult => {
      const target = findRequest(requestId);
      if (target && target.recipientSchoolId !== mySchoolId) {
        return { success: false, error: '우리 학교가 받은 제안만 수락할 수 있습니다.' };
      }
      return commit((current) => acceptAllianceRequest(current, requestId, now()));
    },
    [commit, mySchoolId, now],
  );

  const rejectRequest = useCallback(
    (requestId: string): AllianceActionResult => {
      const target = findRequest(requestId);
      if (target && target.recipientSchoolId !== mySchoolId) {
        return { success: false, error: '우리 학교가 받은 제안만 거절할 수 있습니다.' };
      }
      return commit((current) => rejectAllianceRequest(current, requestId, now()));
    },
    [commit, mySchoolId, now],
  );

  const demoAcceptRequest = useCallback(
    (requestId: string): AllianceActionResult => {
      return commit((current) => acceptAllianceRequest(current, requestId, now()));
    },
    [commit, now],
  );

  const demoRejectRequest = useCallback(
    (requestId: string): AllianceActionResult => {
      return commit((current) => rejectAllianceRequest(current, requestId, now()));
    },
    [commit, now],
  );

  const demoReceiveProposal = useCallback((): AllianceActionResult => {
    return commit((current) => demoReceiveAllianceProposal(current, mySchoolId, now()));
  }, [commit, mySchoolId, now]);

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

  const value: AllianceContextValue = {
    state,
    mySchoolId,
    effectiveNow,
    clockOffsetMs: clock.offsetMs,
    currentAlliance,
    latestExpiredAlliance,
    sentRequests,
    receivedRequests,
    pendingRequests,
    simulateRequestFailure: state.simulateRequestFailure,
    simulateLoadFailure: state.simulateLoadFailure,
    applyRequest,
    cancelRequest,
    acceptRequest,
    rejectRequest,
    demoAcceptRequest,
    demoRejectRequest,
    demoReceiveProposal,
    fastForwardClock: clock.fastForward,
    resetClock: clock.reset,
    toggleSimulateRequestFailure,
    toggleSimulateLoadFailure,
    retryLoad,
  };

  return <AllianceContext.Provider value={value}>{children}</AllianceContext.Provider>;
}

export function useAlliance() {
  const ctx = useContext(AllianceContext);
  if (!ctx) {
    throw new Error('AllianceProvider is required to use useAlliance');
  }
  return ctx;
}

export const useMockAlliance = useAlliance;
