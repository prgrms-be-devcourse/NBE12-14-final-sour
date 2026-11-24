// 시연용 가상 시계: 연합 만료와 대결 진행을 같은 시각으로 판정하기 위한 공용 시계.
// 실제 시계(Date.now())에 오프셋만 더한다. 게임 점수·버프 타이머에는 영향을 주지 않는다.
import React, { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';

interface DemoClockValue {
  /** 현재 오프셋(ms). 렌더링 표시용. */
  offsetMs: number;
  /** 가상 현재 시각. 같은 tick에 fastForward 직후 호출해도 최신 오프셋을 쓴다. */
  now: () => number;
  fastForward: (hours: number) => void;
  reset: () => void;
}

const DemoClockContext = createContext<DemoClockValue | null>(null);

export function DemoClockProvider({ children, initialOffsetMs = 0 }: { children?: React.ReactNode; initialOffsetMs?: number }) {
  const [offsetMs, setOffsetMs] = useState(initialOffsetMs);
  const offsetRef = useRef(initialOffsetMs);

  const now = useCallback(() => Date.now() + offsetRef.current, []);
  const fastForward = useCallback((hours: number) => {
    offsetRef.current += hours * 3600_000;
    setOffsetMs(offsetRef.current);
  }, []);
  const reset = useCallback(() => {
    offsetRef.current = 0;
    setOffsetMs(0);
  }, []);

  const value = useMemo(() => ({ offsetMs, now, fastForward, reset }), [offsetMs, now, fastForward, reset]);
  return <DemoClockContext.Provider value={value}>{children}</DemoClockContext.Provider>;
}

export function useDemoClock(): DemoClockValue {
  const ctx = useContext(DemoClockContext);
  if (!ctx) throw new Error('DemoClockProvider is required to use useDemoClock (AllianceProvider provides it)');
  return ctx;
}
