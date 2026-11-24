import { useState, type SubmitEvent } from 'react';
import { ChevronLeft } from 'lucide-react';
import { useAuthSession } from '../api/ApiProvider';
import { nicknameProblem, NICKNAME_RULE, SIGNUP_ERROR_CODES } from '../api/auth';
import { errorMessage, fieldErrorMap, isApiError } from '../api/errors';
import { useIsAdmin, useMyInfo, useUpdateNickname, type MyInfo } from '../api/users';
import { soundManager } from '../utils/sound';
import { PasswordChangeForm, WithdrawalForm } from './AccountSecurityForms';
import { LoadNotice } from './LoadNotice';
import { PhoneShell } from './PhoneShell';
import { ImageFallback } from './SharedImage';

// My account in API mode (SCRUM-166, plan .agents/01-PLAN/261118-2239-scrum166_my_info_profile.md): the profile from
// GET /users/me, the nickname change, and the password change and withdrawal (SCRUM-167). The mock has no accounts, so this screen exists only with the backend.

const INPUT_CLASS = 'w-full bg-[#ffffff] border-2 border-[#261608] px-2.5 py-1.5 text-xs text-[#261608] focus:outline-none focus:bg-[#fef9c3] shadow-[inset_1px_1px_0px_#261608] disabled:bg-stone-100 disabled:text-stone-500';

interface MyInfoScreenPhoneProps {
  onBack: () => void;
  /** Signed out: the screen offers the login screen. */
  onGoToLogin: () => void;
  onOpenPurchaseHistory?: () => void;
  onOpenSupports?: () => void;
  /** Shown only when the profile's role is ADMIN (SCRUM-172). */
  onOpenAdmin?: () => void;
}

export function MyInfoScreenPhone({ onBack, onGoToLogin, onOpenPurchaseHistory, onOpenSupports, onOpenAdmin }: MyInfoScreenPhoneProps) {
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  const isAdmin = useIsAdmin();

  if (signedIn && !me.data) {
    return (
      <LoadNotice
        status={me.isError ? 'failed' : 'loading'}
        name="내 정보"
        failureDetail={errorMessage(me.error)}
        onRetry={() => void me.refetch()}
        onBack={onBack}
        backLabel="뒤로"
      />
    );
  }

  return (
    <PhoneShell>
      <div className="w-full bg-[#faecc7] border-b-2 border-[#261608] px-3 py-2 flex items-center gap-2 shrink-0 z-30">
        <button
          type="button"
          onClick={() => { soundManager.playTabSound(); onBack(); }}
          aria-label="뒤로가기"
          className="pixel-plaque plaque-wood px-1.5 py-1 text-xs font-pixel font-bold flex items-center gap-0.5 cursor-pointer"
        >
          <ChevronLeft className="w-3.5 h-3.5" />
          <span>뒤로</span>
        </button>
        <h1 className="font-pixel text-sm font-black text-[#261608]">내 정보</h1>
      </div>

      <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-3 font-pixel text-[#261608]">
        {signedIn && me.data ? (
          <>
            <ProfileCard info={me.data} />
            {onOpenPurchaseHistory && <button type="button" onClick={onOpenPurchaseHistory}
              className="pixel-plaque plaque-yellow min-h-11 px-3 py-2 text-xs font-bold cursor-pointer">아이템 구매 내역</button>}
            {onOpenSupports && <button type="button" onClick={onOpenSupports}
              className="pixel-plaque plaque-yellow min-h-12 px-3 py-2 text-xs font-bold cursor-pointer">회원 문의 · 내 문의</button>}
            {isAdmin && onOpenAdmin && <button type="button" onClick={() => { soundManager.playTabSound(); onOpenAdmin(); }}
              className="pixel-plaque plaque-green min-h-12 px-3 py-2 text-xs font-bold cursor-pointer">관리자 메뉴</button>}
            {/* A new member (another login) starts a new form. */}
            <NicknameForm key={me.data.id} current={me.data.nickname} />
            <PasswordChangeForm key={`password-${me.data.id}`} />
            <WithdrawalForm key={`withdrawal-${me.data.id}`} />
          </>
        ) : (
          <div role="status" className="rpg-box p-4 bg-[#fdf5df] border-2 border-[#261608] flex flex-col items-center gap-2 text-center">
            <p className="text-sm font-bold">로그인하면 내 정보를 볼 수 있어요</p>
            <button type="button" onClick={onGoToLogin} className="pixel-plaque plaque-yellow px-3 py-1.5 text-xs font-bold cursor-pointer">로그인</button>
          </div>
        )}
      </div>
    </PhoneShell>
  );
}

function ProfileCard({ info }: { info: MyInfo }) {
  const rows: Array<[string, string, string]> = [
    ['학교 이메일', info.email, 'my-info-email'],
    ['학교', info.university.name, 'my-info-university'],
    ['학과', info.department.name, 'my-info-department'],
    ['클리커', `Lv.${info.clicker.level} · 클릭당 ${info.clicker.clickPower}점`, 'my-info-clicker'],
    ['기여도', `${info.totalContribScore.toLocaleString()}점`, 'my-info-contribution'],
    ['공격 점수', `${info.totalAttackScore.toLocaleString()}점`, 'my-info-attack'],
  ];
  return (
    <section aria-label="내 프로필" className="rpg-box p-3 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col gap-2.5">
      <div className="flex items-center gap-3">
        <div className="w-14 h-14 shrink-0 border-2 border-[#261608] bg-[#faecc7] overflow-hidden flex items-center justify-center text-lg font-black">
          <ImageFallback url={info.profileImageUrl ?? undefined} name={info.nickname} fallback={info.nickname.slice(0, 1)} className="w-full h-full" />
        </div>
        <div className="min-w-0">
          <p data-testid="my-info-nickname" className="text-base font-black truncate">{info.nickname}</p>
          {info.role === 'ADMIN' && <p className="text-[10px] font-bold text-[#1d4ed8]">관리자</p>}
        </div>
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[11px]">
        {rows.map(([label, value, testId]) => (
          <div key={label} className="contents">
            <dt className="text-[#8b5327] font-bold">{label}</dt>
            <dd data-testid={testId} className="break-all">{value}</dd>
          </div>
        ))}
      </dl>
      <p className="text-[10px] text-[#8b5327] leading-tight">학과는 바꿀 수 없어요. 프로필 이미지는 상점의 개인 로고 변경권으로 바꿀 수 있어요.</p>
    </section>
  );
}

type Notice = { kind: 'success' | 'error'; text: string };

function NicknameForm({ current }: { current: string }) {
  const update = useUpdateNickname();
  /** Null shows the saved nickname. */
  const [draft, setDraft] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const value = draft ?? current;
  const unchanged = value === current;

  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (update.isPending || unchanged) return;
    const problem = nicknameProblem(value);
    if (problem) {
      setNotice({ kind: 'error', text: problem });
      return;
    }
    soundManager.playTabSound();
    setNotice(null);
    update.mutate(value, {
      onSuccess: () => {
        setDraft(null);
        setNotice({ kind: 'success', text: '닉네임을 바꿨어요.' });
      },
      onError: (failure) => {
        const duplicated = isApiError(failure) && failure.code === SIGNUP_ERROR_CODES.nicknameDuplicated;
        setNotice({ kind: 'error', text: duplicated ? '이미 사용 중인 닉네임이에요. 다른 닉네임을 입력해 주세요.' : fieldErrorMap(failure).nickname ?? errorMessage(failure) });
      },
    });
  };

  return (
    <form onSubmit={submit} noValidate aria-label="닉네임 바꾸기" className="rpg-box p-3 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col gap-2">
      <label className="text-[10px] font-bold flex flex-col gap-1">
        닉네임
        <input
          type="text"
          value={value}
          onChange={(event) => { setDraft(event.target.value); setNotice(null); }}
          disabled={update.isPending}
          maxLength={NICKNAME_RULE.max}
          autoComplete="nickname"
          aria-invalid={notice?.kind === 'error'}
          aria-describedby="my-info-nickname-rule"
          className={INPUT_CLASS}
        />
      </label>
      <p id="my-info-nickname-rule" className="text-[9px] text-[#8b5327]">앞뒤 공백 없이 {NICKNAME_RULE.min}~{NICKNAME_RULE.max}자</p>
      <button type="submit" disabled={update.isPending || unchanged}
        className="pixel-plaque plaque-yellow px-3 py-1.5 text-xs font-bold cursor-pointer disabled:opacity-60 disabled:cursor-default">
        {update.isPending ? '저장하는 중…' : '닉네임 저장'}
      </button>
      {notice?.kind === 'success' && <p data-testid="my-info-result" role="status" className="text-[11px] font-bold text-[#14532d]">{notice.text}</p>}
      {notice?.kind === 'error' && <p data-testid="my-info-error" role="alert" className="text-[11px] font-bold text-[#7f1d1d]">{notice.text}</p>}
    </form>
  );
}
