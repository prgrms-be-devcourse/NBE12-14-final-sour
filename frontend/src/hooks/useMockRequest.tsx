import React, { createContext, useCallback, useContext, useEffect, useId, useMemo, useRef, useState } from 'react';
import { requestWaitMs, settleRequest, type MockRequestMode } from '../utils/mockRequest.ts';

// Shared demo mode and pending requests (M5-B). Lives next to the game state so a pending request survives
// screen changes. Decisions are made when a request settles, in the mode current at that time.

function useRequestController(initialMode: MockRequestMode) {
  const [mode, setModeState] = useState<MockRequestMode>(initialMode);
  const modeRef = useRef(mode);
  const pendingRef = useRef(new Set<string>());
  const [pending, setPending] = useState<ReadonlySet<string>>(() => new Set());

  const setMode = useCallback((next: MockRequestMode) => {
    modeRef.current = next;
    setModeState(next);
  }, []);
  const mark = useCallback((key: string, on: boolean) => {
    if (on) pendingRef.current.add(key);
    else pendingRef.current.delete(key);
    setPending(new Set(pendingRef.current));
  }, []);

  /**
   * Send one request. Returns null when the same key is still pending (the duplicate is refused), otherwise
   * a cancel function. `onSettled(true)` is where the caller applies the change; it runs at once in normal mode.
   */
  const run = useCallback((key: string, onSettled: (ok: boolean) => void): (() => void) | null => {
    if (pendingRef.current.has(key)) return null;
    const wait = requestWaitMs(modeRef.current);
    if (wait === 0) {
      onSettled(true);
      return () => {};
    }
    mark(key, true);
    const timer = setTimeout(() => {
      mark(key, false);
      const result = settleRequest(modeRef.current);
      if (result.nextMode !== modeRef.current) setMode(result.nextMode);
      onSettled(result.ok);
    }, wait);
    return () => {
      clearTimeout(timer);
      if (pendingRef.current.has(key)) mark(key, false);
    };
  }, [mark, setMode]);

  const isPending = useCallback((key: string) => pending.has(key), [pending]);
  return useMemo(() => ({ mode, setMode, run, isPending }), [mode, setMode, run, isPending]);
}

export type MockRequestController = ReturnType<typeof useRequestController>;

const MockRequestContext = createContext<MockRequestController | null>(null);

export function MockRequestProvider({ children, initialMode = 'normal' }: { children: React.ReactNode; initialMode?: MockRequestMode }) {
  const controller = useRequestController(initialMode);
  return <MockRequestContext.Provider value={controller}>{children}</MockRequestContext.Provider>;
}

export function useMockRequest(): MockRequestController {
  const controller = useContext(MockRequestContext);
  if (!controller) throw new Error('MockRequestProvider 안에서 useMockRequest를 사용하세요.');
  return controller;
}

export type MockLoadStatus = 'loading' | 'ready' | 'failed';

/**
 * A screen's data query. In normal mode the screen shows at once, as before M5. In the other modes it loads,
 * then shows the data or a failure with a retry.
 */
export function useMockLoad(name: string) {
  const request = useMockRequest();
  const key = `${name}:${useId()}`;
  const [status, setStatus] = useState<MockLoadStatus>(() => (request.mode === 'normal' ? 'ready' : 'loading'));
  const { run } = request;
  useEffect(() => {
    if (status !== 'loading') return;
    const cancel = run(key, (ok) => setStatus(ok ? 'ready' : 'failed'));
    return cancel ?? undefined;
  }, [status, key, run]);
  const retry = useCallback(() => setStatus('loading'), []);
  return { status, retry };
}
