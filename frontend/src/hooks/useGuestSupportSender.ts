// How the guest inquiry form sends (SCRUM-164): the mock server of SCRUM-122, or POST /api/v1/supports.
// Plan: .agents/01-PLAN/261118-2225-scrum164_guest_support_api.md.

import { useCallback, useMemo, useRef, useState } from 'react';
import { useApiClient } from '../api/ApiProvider';
import { errorMessage } from '../api/errors';
import { selectByMode } from '../api/mode';
import { classifyGuestSupportError, createGuestSupport } from '../api/supports';
import { useMockRequest } from './useMockRequest';
import { judgeGuestSupport, SUPPORT_MESSAGES, type SupportForm } from '../utils/mockSupport';

export type GuestSupportResult =
  | { kind: 'accepted' }
  | { kind: 'verification-required' }
  /** Cloudflare refused the token. A token is single-use: the form asks for a new one, never resends it. */
  | { kind: 'verification-failed'; text: string }
  | { kind: 'too-many'; text: string }
  /** No answer or another error; the form can send again. */
  | { kind: 'failed'; text: string };

export interface GuestSupportSender {
  /** `token` undefined sends no `turnstileToken` field. Returns a cancel function; a cancelled send never calls `done`. */
  send: (request: SupportForm, token: string | undefined, done: (result: GuestSupportResult) => void) => () => void;
  /** The mock's stand-in for the server's per-IP count. Null with the API: the server counts. */
  demo: { sentCount: number; reset: () => void } | null;
}

export function useMockGuestSupportSender(): GuestSupportSender {
  const { run } = useMockRequest();
  const sentRef = useRef(0);
  const [sentCount, setSentCount] = useState(0);
  const send = useCallback<GuestSupportSender['send']>((_request, token, done) => {
    const cancel = run('support-create', (ok) => {
      if (!ok) return done({ kind: 'failed', text: SUPPORT_MESSAGES.network });
      const verdict = judgeGuestSupport(sentRef.current, token);
      if (verdict === 'verification-required') return done({ kind: 'verification-required' });
      if (verdict === 'too-many') return done({ kind: 'too-many', text: SUPPORT_MESSAGES.tooMany });
      sentRef.current += 1;
      setSentCount(sentRef.current);
      done({ kind: 'accepted' });
    });
    return () => cancel?.();
  }, [run]);
  const reset = useCallback(() => {
    sentRef.current = 0;
    setSentCount(0);
  }, []);
  return useMemo(() => ({ send, demo: { sentCount, reset } }), [send, sentCount, reset]);
}

export function useApiGuestSupportSender(): GuestSupportSender {
  const client = useApiClient();
  const send = useCallback<GuestSupportSender['send']>((request, token, done) => {
    const controller = new AbortController();
    createGuestSupport(client, { ...request, turnstileToken: token }, controller.signal).then(
      () => { if (!controller.signal.aborted) done({ kind: 'accepted' }); },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        const kind = classifyGuestSupportError(error);
        if (kind === 'verification-required') done({ kind });
        // The form's own line names the limit; the server's 429 text is generic.
        else if (kind === 'too-many') done({ kind, text: SUPPORT_MESSAGES.tooMany });
        else if (kind === 'verification-failed') done({ kind, text: errorMessage(error) });
        else done({ kind: 'failed', text: errorMessage(error) });
      },
    );
    return () => controller.abort();
  }, [client]);
  return useMemo(() => ({ send, demo: null }), [send]);
}

export const useGuestSupportSender = selectByMode({ api: useApiGuestSupportSender, mock: useMockGuestSupportSender });
