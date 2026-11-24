import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../api/ApiProvider';
import { isApiError } from '../api/errors';
import { createMemberSupport, supportKeys, type MemberSupportRequest, type SupportCreated } from '../api/supports';
import { userKeys, type MyInfo } from '../api/users';

type Phase = 'idle' | 'sending' | 'uncertain';

/** Workspace lifetime: switching between form/list/detail cannot resubmit an in-flight inquiry. */
export function useMemberSupportSender(userId: number) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  const attempt = useRef<AbortController | null>(null);
  const phaseRef = useRef<Phase>('idle');
  const [phase, setPhase] = useState<Phase>('idle');
  const [error, setError] = useState<unknown>(null);
  const [created, setCreated] = useState<SupportCreated | null>(null);
  useEffect(() => {
    const cancel = () => { attempt.current?.abort(); attempt.current = null; phaseRef.current = 'idle'; };
    const unsubscribe = client.session.subscribe(() => {
      if (client.session.getState().status === 'signed-in') return;
      cancel(); setPhase('idle'); setError(null); setCreated(null);
    });
    return () => { unsubscribe(); cancel(); };
  }, [client, userId]);
  const send = async (request: MemberSupportRequest): Promise<SupportCreated | null> => {
    if (phaseRef.current !== 'idle' || client.session.getState().status !== 'signed-in'
      || queryClient.getQueryData<MyInfo>(userKeys.me)?.id !== userId) return null;
    const work = new AbortController(); attempt.current = work;
    phaseRef.current = 'sending'; setPhase('sending'); setError(null); setCreated(null);
    const active = () => attempt.current === work && !work.signal.aborted
      && client.session.getState().status === 'signed-in' && queryClient.getQueryData<MyInfo>(userKeys.me)?.id === userId;
    try {
      const result = await createMemberSupport(client, request, work.signal);
      if (!active()) return null;
      setCreated(result); phaseRef.current = 'idle'; setPhase('idle'); attempt.current = null;
      // Also cancel the very first GET with no cached data; invalidateQueries alone reuses it.
      const queryKey = supportKeys.lists(userId);
      void queryClient.cancelQueries({ queryKey }).then(() => queryClient.invalidateQueries({ queryKey }));
      return result;
    } catch (failure) {
      if (!active()) return null;
      setError(failure); attempt.current = null;
      const uncertain = !isApiError(failure) || failure.kind === 'network' || failure.kind === 'invalid-response'
        || (failure.status !== null && failure.status >= 500);
      phaseRef.current = uncertain ? 'uncertain' : 'idle'; setPhase(phaseRef.current);
      return null;
    }
  };
  return {
    send, phase, error, created,
    clearValidationError: () => { if (phaseRef.current === 'idle') setError(null); },
    /** Explicit acknowledgement only unlocks editing/submission; it sends no request itself. */
    prepareRetry: () => {
      if (phaseRef.current !== 'uncertain') return;
      phaseRef.current = 'idle'; setPhase('idle'); setError(null);
    },
  };
}
