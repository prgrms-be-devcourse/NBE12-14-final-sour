import { useRef, type ReactNode } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useApiClient } from '../api/ApiProvider';
import { logout } from '../api/auth';
import { errorMessage } from '../api/errors';

type ProfileAction = 'my_info' | 'member_support';

interface GameProfileBarProps {
  mode: 'api' | 'mock';
  nickname: string;
  onNavigate: (action: ProfileAction) => void;
  onCampus: () => void;
  tools?: ReactNode;
}

function MemberProfile({ nickname, onNavigate }: Pick<GameProfileBarProps, 'nickname' | 'onNavigate'>) {
  const details = useRef<HTMLDetailsElement>(null);
  const summary = useRef<HTMLElement>(null);
  const client = useApiClient();
  const signOut = useMutation({ mutationFn: () => logout(client) });
  const close = () => {
    if (details.current) details.current.open = false;
  };
  const navigate = (action: ProfileAction) => {
    close();
    summary.current?.focus();
    onNavigate(action);
  };

  return (
    <details ref={details} className="game-profile" onBlur={(event) => {
      if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget)) close();
    }} onKeyDown={(event) => {
      if (event.key === 'Escape' && details.current?.open) {
        event.stopPropagation();
        close();
        summary.current?.focus();
      }
    }}>
      <summary ref={summary} className="game-profile-trigger">
        <span className="game-profile-label">프로필 ·</span>{' '}
        <span className="game-profile-name">{nickname}</span>
      </summary>
      <div className="game-profile-actions">
        <button type="button" onClick={() => navigate('my_info')}>
          <span className="game-profile-icon" aria-hidden="true">👤</span>내 정보
        </button>
        <button type="button" onClick={() => navigate('member_support')}>
          <span className="game-profile-icon" aria-hidden="true">💬</span>문의
        </button>
        <button type="button" aria-disabled={signOut.isPending || undefined} onClick={(event) => {
          event.currentTarget.focus();
          if (!signOut.isPending) signOut.mutate();
        }}>
          <span className="game-profile-icon" aria-hidden="true">🚪</span>{signOut.isPending ? '로그아웃 중…' : '로그아웃'}
        </button>
        {signOut.isError && <p role="alert" className="p-2 text-red-800">로그아웃하지 못했어요. {errorMessage(signOut.error)}</p>}
      </div>
    </details>
  );
}

export function GameProfileBar({ mode, nickname, onNavigate, onCampus, tools }: GameProfileBarProps) {
  return (
    <header className="game-top-bar game-width">
      <button type="button" onClick={onCampus} className="game-brand" aria-label="대대대 내 캠퍼스">
        <span className="game-wordmark" aria-hidden="true">대</span>
        <span className="font-black">대대대 <span className="game-brand-caption hidden sm:inline text-xs text-[#775430]">캠퍼스 대항전</span></span>
      </button>
      {(tools || mode === 'api') && <div className="ms-auto flex min-w-0 items-center gap-1 sm:gap-2">
        {tools}
        {mode === 'api' && <MemberProfile nickname={nickname} onNavigate={onNavigate} />}
      </div>}
    </header>
  );
}
