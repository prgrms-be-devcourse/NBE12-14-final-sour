import { useState, type SubmitEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useApiClient } from '../api/ApiProvider';
import { passwordProblem, PASSWORD_RULE_TEXT } from '../api/auth';
import { errorMessage, fieldErrorMap, isApiError } from '../api/errors';
import { changePassword, withdraw } from '../api/users';
import { soundManager } from '../utils/sound';

// Password change and withdrawal on my account screen (SCRUM-167, plan
// .agents/01-PLAN/261118-2247-scrum167_password_withdrawal.md). Both end the session on success; the app's session
// watcher then opens the login screen with the reason (session.close).

const INPUT_CLASS = 'w-full bg-[#ffffff] border-2 border-[#261608] px-2.5 py-1.5 text-xs font-mono text-[#261608] focus:outline-none focus:bg-[#fef9c3] shadow-[inset_1px_1px_0px_#261608] disabled:bg-stone-100 disabled:text-stone-500';
const BOX_CLASS = 'rpg-box p-3 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col gap-2';
const LABEL_CLASS = 'text-[10px] font-bold flex flex-col gap-1';

/** ErrorCode.USER_INVALID_PASSWORD: the password to confirm with is wrong. */
const INVALID_PASSWORD = 'USER_INVALID_PASSWORD';

function failureText(failure: unknown, wrongPassword: string, fields: string[]): string {
  if (isApiError(failure) && failure.code === INVALID_PASSWORD) return wrongPassword;
  const map = fieldErrorMap(failure);
  return fields.map((field) => map[field]).find(Boolean) ?? errorMessage(failure);
}

function ErrorLine({ id, text }: { id: string; text: string | null }) {
  return text ? <p data-testid={id} role="alert" className="text-[11px] font-bold text-[#7f1d1d]">{text}</p> : null;
}

export function PasswordChangeForm() {
  const client = useApiClient();
  const change = useMutation({ mutationFn: (request: { currentPassword: string; newPassword: string }) => changePassword(client, request) });
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const edit = (set: (value: string) => void) => (event: { target: { value: string } }) => { set(event.target.value); setError(null); };

  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (change.isPending) return;
    const problem = !current ? '현재 비밀번호를 입력해 주세요.' : passwordProblem(next, confirm);
    if (problem) {
      setError(problem);
      return;
    }
    soundManager.playTabSound();
    change.mutate({ currentPassword: current, newPassword: next }, {
      onError: (failure) => setError(failureText(failure, '현재 비밀번호가 올바르지 않아요.', ['currentPassword', 'newPassword'])),
    });
  };

  return (
    <form onSubmit={submit} noValidate aria-label="비밀번호 바꾸기" className={BOX_CLASS}>
      <h2 className="text-xs font-black">비밀번호 바꾸기</h2>
      <label className={LABEL_CLASS}>
        현재 비밀번호
        <input type="password" value={current} onChange={edit(setCurrent)} disabled={change.isPending} maxLength={64} autoComplete="current-password" className={INPUT_CLASS} />
      </label>
      <label className={LABEL_CLASS}>
        새 비밀번호
        <input type="password" value={next} onChange={edit(setNext)} disabled={change.isPending} maxLength={64} autoComplete="new-password" className={INPUT_CLASS} />
      </label>
      <label className={LABEL_CLASS}>
        새 비밀번호 확인
        <input type="password" value={confirm} onChange={edit(setConfirm)} disabled={change.isPending} maxLength={64} autoComplete="new-password" className={INPUT_CLASS} />
      </label>
      <p className="text-[9px] text-[#8b5327] leading-tight">{PASSWORD_RULE_TEXT}. 바꾸면 이 기기도 로그아웃되고, 새 비밀번호로 다시 로그인해요.</p>
      <button type="submit" disabled={change.isPending}
        className="pixel-plaque plaque-yellow px-3 py-1.5 text-xs font-bold cursor-pointer disabled:opacity-60 disabled:cursor-default">
        {change.isPending ? '바꾸는 중…' : '비밀번호 바꾸기'}
      </button>
      <ErrorLine id="password-change-error" text={error} />
    </form>
  );
}

export function WithdrawalForm() {
  const client = useApiClient();
  const leave = useMutation({ mutationFn: (password: string) => withdraw(client, password) });
  const [password, setPassword] = useState('');
  const [agreed, setAgreed] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (leave.isPending || !agreed) return;
    if (!password) {
      setError('비밀번호를 입력해 주세요.');
      return;
    }
    soundManager.playTabSound();
    leave.mutate(password, {
      onError: (failure) => setError(failureText(failure, '비밀번호가 올바르지 않아요.', ['password'])),
    });
  };

  return (
    <form onSubmit={submit} noValidate aria-label="회원 탈퇴" className={`${BOX_CLASS} border-[#991b1b]`}>
      <h2 className="text-xs font-black text-[#991b1b]">회원 탈퇴</h2>
      <p className="text-[10px] text-[#7f1d1d] leading-tight">탈퇴하면 이 계정으로 다시 로그인할 수 없어요. 기여도와 기록은 되돌릴 수 없어요.</p>
      <label className={LABEL_CLASS}>
        탈퇴 확인용 비밀번호
        <input type="password" value={password} onChange={(event) => { setPassword(event.target.value); setError(null); }}
          disabled={leave.isPending} maxLength={64} autoComplete="current-password" className={INPUT_CLASS} />
      </label>
      {/* my-3: on touch phones the label's 44px tap area must not cover the password field or the button. */}
      <label className="my-3 text-[10px] font-bold flex items-center gap-1.5">
        <input type="checkbox" checked={agreed} onChange={(event) => setAgreed(event.target.checked)} disabled={leave.isPending} />
        되돌릴 수 없다는 것을 확인했어요
      </label>
      <button type="submit" disabled={leave.isPending || !agreed}
        className="pixel-plaque plaque-parchment px-3 py-1.5 text-xs font-bold text-[#991b1b] cursor-pointer disabled:opacity-60 disabled:cursor-default">
        {leave.isPending ? '탈퇴하는 중…' : '탈퇴하기'}
      </button>
      <ErrorLine id="withdrawal-error" text={error} />
    </form>
  );
}
