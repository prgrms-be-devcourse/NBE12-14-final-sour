import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import {
  completeProfile,
  editEmail,
  expireCodeForDemo,
  INITIAL_AUTH_STATE,
  randomAuthCode,
  requestCode,
  resendCode,
  restartAuth,
  verifyCode,
  type AuthResult,
  type MockAuthState,
} from '../utils/mockAuth.ts';

// Auth state lives above the app (next to the game state, M4-B) so closing and reopening the onboarding
// screen does not reset attempts, codes or resend waits.
function useAuthController(generateCode: () => string) {
  const [state, setState] = useState<MockAuthState>(INITIAL_AUTH_STATE);
  const stateRef = useRef(state);
  const [now, setNow] = useState(Date.now);

  const apply = useCallback((next: MockAuthState) => {
    stateRef.current = next;
    setState(next);
  }, []);
  // Each action reads the latest state, so two quick submits are judged one after the other.
  const run = useCallback((action: (current: MockAuthState, timestamp: number) => AuthResult) => {
    const timestamp = Date.now();
    setNow(timestamp);
    const result = action(stateRef.current, timestamp);
    if (result.state !== stateRef.current) apply(result.state);
    return result;
  }, [apply]);

  // Countdown for the code's validity and the resend wait, same 250ms pace as the buff timer.
  const ticking = state.step === 'code';
  useEffect(() => {
    if (!ticking) return;
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, [ticking]);

  return {
    state,
    now,
    requestCode: useCallback((email: string) => run((s, t) => requestCode(s, email, t, generateCode)), [run, generateCode]),
    resend: useCallback(() => run((s, t) => resendCode(s, t, generateCode)), [run, generateCode]),
    verify: useCallback((code: string) => run((s, t) => verifyCode(s, code, t)), [run]),
    complete: useCallback((input: { nickname: string; departmentId: string }) => run((s) => completeProfile(s, input)), [run]),
    editEmail: useCallback(() => apply(editEmail(stateRef.current)), [apply]),
    restart: useCallback(() => apply(restartAuth(stateRef.current)), [apply]),
    expireForDemo: useCallback(() => {
      const timestamp = Date.now();
      setNow(timestamp);
      apply(expireCodeForDemo(stateRef.current, timestamp));
    }, [apply]),
  };
}

export type MockAuthController = ReturnType<typeof useAuthController>;

const MockAuthContext = createContext<MockAuthController | null>(null);

export function MockAuthProvider({ children, generateCode = randomAuthCode }: { children: React.ReactNode; generateCode?: () => string }) {
  const [codeSource] = useState(() => generateCode);
  const auth = useAuthController(codeSource);
  return <MockAuthContext.Provider value={auth}>{children}</MockAuthContext.Provider>;
}

export function useMockAuth(): MockAuthController {
  const auth = useContext(MockAuthContext);
  if (!auth) throw new Error('MockAuthProvider 안에서 useMockAuth를 사용하세요.');
  return auth;
}
