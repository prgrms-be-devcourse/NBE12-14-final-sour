import { useEffect, useId, useRef, useState, type SubmitEvent } from 'react';
import {
  USER_NOT_FOUND_CODE, parseUserStatusTarget, useAbuseRankings, useChangeUserStatus, useRecheckRoleOnForbidden,
  type AdminUserStatus,
} from '../api/admin';
import { errorMessage, isApiError } from '../api/errors';
import { ADMIN_BOX, ADMIN_BUTTON, ADMIN_INPUT, AdminForbiddenNotice } from './adminUi';

// Member status and abuse ranking (SCRUM-174, plan .agents/01-PLAN/261119-1054-scrum174_admin_users_abuse.md). No admin
// API reads a member's current status, so the screen shows only the result of each change made here.

const STATUS_OPTIONS: ReadonlyArray<{ value: AdminUserStatus; label: string; verb: string; done: string; effect: string }> = [
  { value: 'SUSPENDED', label: '정지', verb: '정지하기', done: '정지됨', effect: '그 회원은 바로 로그아웃되고, 해제할 때까지 다시 로그인할 수 없어요.' },
  { value: 'BANNED', label: '차단 (영구)', verb: '차단하기', done: '차단됨', effect: '그 회원은 바로 로그아웃되고, 해제할 때까지 다시 로그인할 수 없어요. 기간 없는 영구 제한이에요.' },
  { value: 'ACTIVE', label: '해제', verb: '해제하기', done: '정상(해제됨)', effect: '정지·차단을 풀어요. 회원은 다시 로그인하면 돼요.' },
];
const option = (status: AdminUserStatus) => STATUS_OPTIONS.find(({ value }) => value === status)!;
const who = (userId: number, nickname?: string | null) => `회원 #${userId}${nickname ? ` (${nickname})` : ''}`;

export interface AdminUserTarget {
  userId: number;
  nickname: string | null;
}

/** Key it by the target: a prefilled ID from the abuse ranking starts a fresh form. */
export function AdminUserStatusPanel({ adminId, target, onGoToLogin }: { adminId: number; target?: AdminUserTarget; onGoToLogin: () => void }) {
  const id = useId();
  const [input, setInput] = useState(target ? String(target.userId) : '');
  const [status, setStatus] = useState<AdminUserStatus | null>(null);
  const [confirming, setConfirming] = useState<{ userId: number; status: AdminUserStatus } | null>(null);
  const [problem, setProblem] = useState<{ field: 'userId' | 'status'; message: string } | null>(null);
  const [history, setHistory] = useState<Array<{ userId: number; nickname: string | null; status: AdminUserStatus }>>([]);
  const change = useChangeUserStatus();
  const forbidden = useRecheckRoleOnForbidden(change.error);
  const alert = useRef<HTMLParagraphElement>(null);
  const confirmBox = useRef<HTMLDivElement>(null);
  useEffect(() => { if (problem || change.error) alert.current?.focus(); }, [problem, change.error]);
  useEffect(() => { if (confirming) confirmBox.current?.focus(); }, [confirming]);
  if (forbidden) return <AdminForbiddenNotice onGoToLogin={onGoToLogin} />;

  const nicknameFor = (userId: number) => target?.userId === userId ? target.nickname : null;
  const next = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    const parsed = parseUserStatusTarget(input, adminId);
    if ('problem' in parsed) { setProblem({ field: 'userId', message: parsed.problem }); return; }
    if (status === null) { setProblem({ field: 'status', message: '바꿀 상태를 골라 주세요.' }); return; }
    setProblem(null);
    change.reset();
    setConfirming({ userId: parsed.userId, status });
  };
  const apply = () => {
    if (!confirming || change.isPending) return;
    change.mutate(confirming, {
      onSuccess: (result) => {
        setHistory((list) => [{ userId: result.userId, nickname: nicknameFor(result.userId), status: result.status }, ...list]);
        setConfirming(null);
        setStatus(null);
      },
    });
  };
  const failure = change.error ? (isApiError(change.error) && change.error.code === USER_NOT_FOUND_CODE
    ? '회원을 찾을 수 없어요. 없거나 탈퇴한 회원이에요.' : `상태를 바꾸지 못했어요. ${errorMessage(change.error)}`) : null;
  const latest = history[0];

  return <>
    {latest && !confirming && <p role="status" data-testid="admin-user-status-done" className={`${ADMIN_BOX} text-xs text-[#14532d]`}>
      {who(latest.userId, latest.nickname)}: {option(latest.status).done}
    </p>}
    <form noValidate onSubmit={next} aria-label="회원 상태 변경" className={`${ADMIN_BOX} text-xs`}>
      <p>관리자 화면에서는 회원의 현재 상태를 조회할 수 없어요. 회원 ID를 확인한 뒤 바꿔 주세요.</p>
      <label htmlFor={`${id}-user`} className="font-bold">회원 ID</label>
      <input id={`${id}-user`} name="userId" inputMode="numeric" autoComplete="off" value={input} disabled={change.isPending || confirming !== null}
        onChange={(event) => { setInput(event.target.value); setProblem(null); }} aria-invalid={problem?.field === 'userId'}
        aria-describedby={problem?.field === 'userId' ? `${id}-problem` : undefined} className={ADMIN_INPUT} />
      {target && String(target.userId) === input.trim() && <p className="text-[11px]">어뷰징 순위에서 고른 회원: {who(target.userId, target.nickname)}</p>}
      <fieldset className="flex flex-col gap-1.5" disabled={change.isPending || confirming !== null}>
        <legend className="font-bold mb-1">바꿀 상태</legend>
        {STATUS_OPTIONS.map(({ value, label }) => (
          <label key={value} className="flex items-center gap-2 min-h-11 cursor-pointer">
            <input type="radio" name="status" value={value} checked={status === value} className="w-5 h-5"
              onChange={() => { setStatus(value); setProblem(null); }} />
            {label}
          </label>
        ))}
      </fieldset>
      {problem && <p id={`${id}-problem`} ref={alert} role="alert" tabIndex={-1} className="text-[#7f1d1d]">{problem.message}</p>}
      {!confirming && <button type="submit" className={`${ADMIN_BUTTON} plaque-yellow`}>다음</button>}
    </form>
    {confirming && (
      <div ref={confirmBox} tabIndex={-1} role="group" aria-label="상태 변경 확인" className={`${ADMIN_BOX} text-xs`}>
        <p className="font-black text-sm">{who(confirming.userId, nicknameFor(confirming.userId))}을(를) {option(confirming.status).label}할까요?</p>
        <p>{option(confirming.status).effect}</p>
        {failure && <p ref={alert} role="alert" tabIndex={-1} className="text-[#7f1d1d]">{failure}</p>}
        <div className="flex gap-2">
          <button type="button" className={`${ADMIN_BUTTON} plaque-yellow flex-1`} disabled={change.isPending} onClick={apply}>
            {change.isPending ? '바꾸는 중…' : option(confirming.status).verb}
          </button>
          <button type="button" className={`${ADMIN_BUTTON} flex-1`} disabled={change.isPending} onClick={() => { setConfirming(null); change.reset(); }}>취소</button>
        </div>
      </div>
    )}
    {history.length > 0 && (
      <section aria-label="이 화면에서 바꾼 기록" className={`${ADMIN_BOX} text-xs`}>
        <h2 className="font-black text-sm">이 화면에서 바꾼 기록</h2>
        <ol className="list-disc pl-5">
          {history.map((entry, index) => <li key={index}>{who(entry.userId, entry.nickname)}: {option(entry.status).done}</li>)}
        </ol>
        <p className="text-[11px]">화면을 나가면 기록은 사라져요.</p>
      </section>
    )}
  </>;
}

export function AdminAbuseRanking({ adminId, page, onPage, onChangeStatus, onGoToLogin }: {
  adminId: number; page: number; onPage: (page: number) => void; onChangeStatus: (target: AdminUserTarget) => void; onGoToLogin: () => void;
}) {
  const query = useAbuseRankings(adminId, page);
  const forbidden = useRecheckRoleOnForbidden(query.error);
  if (forbidden) return <AdminForbiddenNotice onGoToLogin={onGoToLogin} />;
  const data = query.data;
  return <>
    <p className="text-xs">최근 3일(오늘 포함, 한국 시간) 동안 클릭 제한을 넘긴 횟수가 많은 순서예요.</p>
    <button type="button" className={ADMIN_BUTTON} onClick={() => void query.refetch()} disabled={query.isFetching}>순위 새로고침</button>
    <div aria-busy={query.isFetching} className="text-xs">
      {query.isPending ? <p role="status">어뷰징 순위를 불러오는 중…</p> : query.isError ? (
        <div className={ADMIN_BOX}>
          <p role="alert">어뷰징 순위를 불러오지 못했어요. {errorMessage(query.error)}</p>
          <button type="button" className={ADMIN_BUTTON} onClick={() => void query.refetch()}>어뷰징 순위 다시 조회</button>
        </div>
      ) : query.data.content.length === 0 ? (
        <p className={ADMIN_BOX}>{page === 0 ? '최근 3일 동안 기록된 부정행위가 없어요.' : '이 페이지에 순위가 없어요. 이전 페이지를 확인해 주세요.'}</p>
      ) : (
        <ol aria-label="어뷰징 순위" className="flex flex-col gap-2">
          {query.data.content.map((item) => (
            <li key={item.userId} data-testid="admin-abuse-row" className={`${ADMIN_BOX} flex-row items-center justify-between gap-2`}>
              <div className="min-w-0 break-words">
                <p className="font-bold">{item.rank}위 · {item.nickname ?? '알 수 없는 회원'}</p>
                <p className="text-[11px]">회원 #{item.userId} · 부정행위 {item.abuseCount.toLocaleString()}회</p>
              </div>
              <button type="button" className={`${ADMIN_BUTTON} shrink-0`} aria-label={`${who(item.userId, item.nickname)} 상태 변경`}
                onClick={() => onChangeStatus({ userId: item.userId, nickname: item.nickname })}>상태 변경</button>
            </li>
          ))}
        </ol>
      )}
    </div>
    <p role="status" className="text-xs">{page + 1}페이지{data && !query.isError ? ` · 전체 ${data.totalElements.toLocaleString()}명` : ''}{query.isFetching ? ' · 조회 중…' : ''}</p>
    <nav aria-label="어뷰징 순위 페이지" className="flex justify-between gap-3 mt-auto">
      <button type="button" className={ADMIN_BUTTON} disabled={page === 0 || query.isFetching} onClick={() => onPage(page - 1)}>이전 페이지</button>
      <button type="button" className={ADMIN_BUTTON} disabled={query.isFetching || query.isError || !data?.hasNext} onClick={() => onPage(page + 1)}>다음 페이지</button>
    </nav>
  </>;
}
