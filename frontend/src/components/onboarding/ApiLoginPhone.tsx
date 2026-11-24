import React, { useContext, useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { PhoneShellVariantContext } from '../PhoneShell.tsx';
import { useApiClient, useAuthSession } from '../../api/ApiProvider';
import { login, logout, useSessionRestore } from '../../api/auth';
import { errorMessage, fieldErrorMap } from '../../api/errors';
import { userKeys, useMyInfo } from '../../api/users';
import { itemKeys } from '../../api/items';
import { supportKeys } from '../../api/supports';
import { adminKeys } from '../../api/admin';
import { allianceKeys } from '../../api/alliances';
import { LoginStep, PhoneFrame, SignedInStep } from './OnboardingSteps.tsx';

// Login, logout and the session restore in API mode (SCRUM-152). The access token stays in memory (SCRUM-148); after
// a reload the refresh cookie brings the session back through POST /auth/reissue.

interface ApiLoginPhoneProps {
  onGoToSignup: () => void;
  onGoToCampus: () => void;
  /** Signed in: opens my account screen (SCRUM-166). */
  onOpenMyInfo?: () => void;
}

/** The login form when signed out, the account with a logout button when signed in. */
export const ApiLoginPhone: React.FC<ApiLoginPhoneProps> = ({ onGoToSignup, onGoToCampus, onOpenMyInfo }) => {
  const client = useApiClient();
  const session = useAuthSession();
  const restore = useSessionRestore();
  const me = useMyInfo();
  const fullShell = useContext(PhoneShellVariantContext) === 'full';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [nickname, setNickname] = useState<string | null>(null);
  const signIn = useMutation({ mutationFn: (input: { email: string; password: string }) => login(client, input.email, input.password) });
  const signOut = useMutation({ mutationFn: () => logout(client) });

  let content: React.ReactNode;
  if (session.status === 'signed-in') {
    content = (
      <SignedInStep
        nickname={me.data?.nickname ?? nickname}
        onGoToCampus={onGoToCampus}
        onOpenMyInfo={onOpenMyInfo}
        error={error ?? (me.isError ? `내 정보를 불러오지 못했습니다. ${errorMessage(me.error)}` : null)}
        pending={signOut.isPending}
        onLogout={() => {
          setError(null);
          signOut.mutate(undefined, {
            onSuccess: () => {
              setNickname(null);
              setPassword('');
            },
            // A session ended by the server (e.g. replaced by another login) is signed out already; the login form
            // shows that reason, so no logout error is added.
            onError: (failure) => {
              if (client.session.getState().status === 'signed-in') setError(`로그아웃하지 못했습니다. ${errorMessage(failure)}`);
            },
          });
        }}
      />
    );
  } else if (restore.isPending) {
    content = <p role="status" className="my-auto text-center font-pixel text-xs text-[#8b5327]">로그인 상태를 확인하는 중…</p>;
  } else {
    const notice = session.ended?.message
      ?? (restore.isError ? `로그인 상태를 확인하지 못했습니다. ${errorMessage(restore.error)}` : null);
    content = (
      <LoginStep
        email={email}
        password={password}
        onEmailChange={(value) => { setEmail(value); setError(null); }}
        onPasswordChange={(value) => { setPassword(value); setError(null); }}
        notice={notice}
        error={error}
        pending={signIn.isPending}
        onGoToSignup={onGoToSignup}
        onLogin={() => {
          const trimmed = email.trim();
          if (!trimmed || !password) {
            setError('이메일과 비밀번호를 입력해 주세요.');
            return;
          }
          signIn.mutate({ email: trimmed, password }, {
            onSuccess: ({ user }) => {
              setNickname(user.nickname);
              setPassword('');
              setError(null);
            },
            onError: (failure) => {
              const fields = fieldErrorMap(failure);
              setError(fields.email ?? fields.password ?? errorMessage(failure));
            },
          });
        }}
      />
    );
  }

  if (fullShell) return <PhoneFrame label="로그인">{content}</PhoneFrame>;
  return (
    <div className="w-full flex flex-col items-center py-2">
      <p className="mb-2 font-pixel text-xs text-[#8b5327] font-bold">로그인 · 서버 연동</p>
      <PhoneFrame label="로그인">{content}</PhoneFrame>
    </div>
  );
};

/**
 * Mounted once at the app root in API mode. Restores the session and reports involuntary endings to the app.
 * All sign-outs clear private queries and the admin-only client overrides, even when the game is unmounted.
 */
export const ApiSessionWatcher: React.FC<{ onEnded: () => void }> = ({ onEnded }) => {
  useSessionRestore();
  const client = useApiClient();
  const session = useAuthSession();
  const queryClient = useQueryClient();
  // Subscribe synchronously: a logout and login may happen before React renders the signed-out state.
  // Reset cancels and clears private queries without refetching them while signed out.
  useEffect(() => {
    let wasSignedOut = false;
    const syncPrivateQueries = () => {
      if (client.session.getState().status === 'signed-out') {
        wasSignedOut = true;
        client.faults.reset();
        for (const queryKey of [userKeys.me, allianceKeys.all, itemKeys.histories, supportKeys.all, adminKeys.all]) {
          for (const query of queryClient.getQueryCache().findAll({ queryKey })) query.reset();
        }
      } else if (wasSignedOut) {
        wasSignedOut = false;
        // If React skipped the signed-out render, the existing enabled profile observer needs a new fetch.
        void queryClient.invalidateQueries({ queryKey: userKeys.me, exact: true });
      }
    };
    syncPrivateQueries();
    return client.session.subscribe(syncPrivateQueries);
  }, [client, queryClient]);
  const onEndedRef = useRef(onEnded);
  onEndedRef.current = onEnded;
  // Runs when the state changes, not on every render: the user may leave the login screen while `ended` stays set.
  useEffect(() => {
    if (session.status === 'signed-out' && session.ended) onEndedRef.current();
  }, [session]);
  return null;
};

/** Label of the account button in the view menu. */
export const ApiAccountNavLabel: React.FC = () => {
  const session = useAuthSession();
  return <span>{session.status === 'signed-in' ? '🔑 내 계정 (로그인됨)' : '🔑 로그인'}</span>;
};
