import { useEffect, useId, useRef, useState, type SubmitEvent } from 'react';
import {
  SUPPORT_ALREADY_ANSWERED_CODE, SUPPORT_ANSWER_LIMITS, SUPPORT_NOT_FOUND_CODE, useAdminSupportDetail, useAdminSupports,
  useAnswerAdminSupport, useRecheckRoleOnForbidden, validateSupportAnswer, type AdminSupportFilter, type SupportAnswerRequest,
} from '../api/admin';
import { errorMessage, fieldErrorMap, isApiError } from '../api/errors';
import { safeSupportAttachment, type SupportStatus } from '../api/supports';
import { ADMIN_BOX, ADMIN_BUTTON, ADMIN_INPUT, AdminForbiddenNotice } from './adminUi';

// Admin inquiry list, detail and answer (SCRUM-173, plan .agents/01-PLAN/261119-1038-scrum173_admin_supports.md).

const STATUS: Record<SupportStatus, string> = { RECEIVED: '답변 대기', ANSWERED: '답변 완료' };
const FILTERS: ReadonlyArray<{ value: AdminSupportFilter; label: string }> = [
  { value: 'RECEIVED', label: '답변 대기' },
  { value: 'ANSWERED', label: '답변 완료' },
  { value: 'ALL', label: '전체' },
];
const format = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' });
const date = (value: string) => <time dateTime={value}>{format.format(new Date(value))} (KST)</time>;
const sameCode = (error: unknown, code: string) => isApiError(error) && error.code === code;

export interface AdminSupportListState {
  filter: AdminSupportFilter;
  page: number;
}

export function AdminSupportList({ adminId, state, onChange, onOpen, onGoToLogin }: {
  adminId: number; state: AdminSupportListState; onChange: (state: AdminSupportListState) => void;
  onOpen: (supportId: number) => void; onGoToLogin: () => void;
}) {
  const { filter, page } = state;
  const query = useAdminSupports(adminId, filter, page);
  const forbidden = useRecheckRoleOnForbidden(query.error);
  if (forbidden) return <AdminForbiddenNotice onGoToLogin={onGoToLogin} />;
  const data = query.data;
  return <>
    <div role="group" aria-label="문의 상태" className="flex gap-2">
      {FILTERS.map(({ value, label }) => (
        <button key={value} type="button" aria-pressed={filter === value} onClick={() => onChange({ filter: value, page: 0 })}
          className={`${ADMIN_BUTTON} flex-1 ${filter === value ? 'plaque-yellow' : ''}`}>{label}</button>
      ))}
    </div>
    <button type="button" className={ADMIN_BUTTON} onClick={() => void query.refetch()} disabled={query.isFetching}>문의 목록 새로고침</button>
    <div aria-busy={query.isFetching} className="text-xs">
      {query.isPending ? <p role="status">문의 목록을 불러오는 중…</p> : query.isError ? (
        <div className={ADMIN_BOX}>
          <p role="alert">문의 목록을 불러오지 못했어요. {errorMessage(query.error)}</p>
          <button type="button" className={ADMIN_BUTTON} onClick={() => void query.refetch()}>문의 목록 다시 조회</button>
        </div>
      ) : query.data.items.length === 0 ? (
        <p className={ADMIN_BOX}>{page === 0 ? '조건에 맞는 문의가 없어요.' : '이 페이지에 문의가 없어요. 이전 페이지를 확인해 주세요.'}</p>
      ) : (
        <ol aria-label="관리자 문의 목록" className="list-decimal pl-5 space-y-3" start={query.data.page * query.data.size + 1}>
          {query.data.items.map((item) => (
            <li key={item.supportId} data-testid="admin-support-row" className={ADMIN_BOX}>
              <button type="button" className="min-h-12 w-full text-left font-bold break-words cursor-pointer" onClick={() => onOpen(item.supportId)}>{item.title}</button>
              <p>{STATUS[item.status]} · {item.isMember ? '회원' : '비회원'}</p>
              <p className="text-[11px]">{date(item.createdAt)}</p>
            </li>
          ))}
        </ol>
      )}
    </div>
    <p role="status" className="text-xs">{page + 1}페이지{data && !query.isError ? ` · 전체 ${data.totalCount.toLocaleString()}건` : ''}{query.isFetching ? ' · 조회 중…' : ''}</p>
    <nav aria-label="문의 목록 페이지" className="flex justify-between gap-3 mt-auto">
      <button type="button" className={ADMIN_BUTTON} disabled={page === 0 || query.isFetching} onClick={() => onChange({ filter, page: page - 1 })}>이전 페이지</button>
      <button type="button" className={ADMIN_BUTTON} disabled={query.isFetching || query.isError || !data?.hasNext} onClick={() => onChange({ filter, page: page + 1 })}>다음 페이지</button>
    </nav>
  </>;
}

/** Key it by `supportId`: the draft and the answer result belong to one inquiry. */
export function AdminSupportDetailView({ adminId, supportId, onBackToList, onGoToLogin }: {
  adminId: number; supportId: number; onBackToList: () => void; onGoToLogin: () => void;
}) {
  const query = useAdminSupportDetail(adminId, supportId);
  const answer = useAnswerAdminSupport(adminId, supportId);
  const [form, setForm] = useState<SupportAnswerRequest>({ answerTitle: '', answerContent: '' });
  const forbidden = useRecheckRoleOnForbidden(query.error ?? answer.error);
  if (forbidden) return <AdminForbiddenNotice onGoToLogin={onGoToLogin} />;
  if (query.isPending) return <p role="status" className="text-xs">문의 상세를 불러오는 중…</p>;
  if (query.isError) {
    const missing = sameCode(query.error, SUPPORT_NOT_FOUND_CODE);
    return <div className={`${ADMIN_BOX} text-xs`}>
      <p role="alert">{missing ? '문의를 찾을 수 없어요.' : `문의 상세를 불러오지 못했어요. ${errorMessage(query.error)}`}</p>
      {missing ? <button type="button" className={ADMIN_BUTTON} onClick={onBackToList}>문의 목록으로</button>
        : <button type="button" className={ADMIN_BUTTON} onClick={() => void query.refetch()} disabled={query.isFetching}>문의 상세 다시 조회</button>}
    </div>;
  }
  const detail = query.data;
  const attachment = safeSupportAttachment(detail.attachFileUrl);
  return <>
    {answer.isSuccess && <p role="status" data-testid="admin-answer-done" className={`${ADMIN_BOX} text-xs text-[#14532d]`}>
      답변을 등록했어요. 상태가 답변 완료로 바뀌었고, 답변 메일은 곧 발송돼요.
    </p>}
    {answer.isError && detail.answer && <p role="alert" className={`${ADMIN_BOX} text-xs text-[#7f1d1d]`}>
      {sameCode(answer.error, SUPPORT_ALREADY_ANSWERED_CODE) ? '다른 관리자가 먼저 답변했어요. 등록된 답변을 확인해 주세요.'
        : '답변 결과를 받지 못했지만 이 문의는 이미 답변 완료 상태예요. 등록된 답변을 확인해 주세요.'}
    </p>}
    <button type="button" className={ADMIN_BUTTON} onClick={() => void query.refetch()} disabled={query.isFetching}>문의 상세 새로고침</button>
    <article className={`${ADMIN_BOX} text-xs break-words`} aria-busy={query.isFetching}>
      <h2 className="font-black text-sm">{detail.title}</h2>
      <p><span data-testid="admin-support-status">{STATUS[detail.status]}</span> · {detail.isMember ? '회원 문의' : '비회원 문의'}</p>
      <p className="text-[11px]">{date(detail.createdAt)}</p>
      <p>답변 받을 이메일: <span data-testid="admin-support-email">{detail.recipientEmail}</span></p>
      <p data-testid="admin-support-content" className="whitespace-pre-wrap">{detail.content}</p>
      {attachment ? <a href={attachment} target="_blank" rel="noopener noreferrer" className="underline min-h-12 inline-flex items-center">첨부 파일 열기 (새 탭)</a>
        : detail.attachFileUrl && <p>첨부 파일 주소를 확인할 수 없어요.</p>}
    </article>
    {detail.answer ? (
      <section aria-label="등록된 답변" className={`${ADMIN_BOX} text-xs break-words`}>
        <h2 className="font-black text-sm">답변</h2>
        <h3 className="font-bold">{detail.answer.title}</h3>
        <p data-testid="admin-support-answer" className="whitespace-pre-wrap">{detail.answer.content}</p>
      </section>
    ) : <AnswerForm recipientEmail={detail.recipientEmail} form={form} onChange={setForm} answer={answer} />}
  </>;
}

type Answer = ReturnType<typeof useAnswerAdminSupport>;
function AnswerForm({ recipientEmail, form, onChange, answer }: {
  recipientEmail: string; form: SupportAnswerRequest; onChange: (form: SupportAnswerRequest) => void; answer: Answer;
}) {
  const id = useId();
  const [problem, setProblem] = useState<string | null>(null);
  const summary = useRef<HTMLParagraphElement>(null);
  const fields = fieldErrorMap(answer.error);
  const failure = answer.error ? errorMessage(answer.error) : null;
  useEffect(() => { if (problem || answer.error) summary.current?.focus(); }, [problem, answer.error]);
  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (answer.isPending) return;
    const issue = validateSupportAnswer(form);
    setProblem(issue);
    if (!issue) answer.mutate(form);
  };
  const update = (key: keyof SupportAnswerRequest, value: string) => {
    onChange({ ...form, [key]: value });
    setProblem(null);
    if (answer.isError) answer.reset();
  };
  const sending = answer.isPending;
  const error = problem || failure ? `${id}-error` : undefined;
  return <form noValidate onSubmit={submit} aria-label="문의 답변 작성" aria-busy={sending} className={`${ADMIN_BOX} text-xs`}>
    <p>등록하면 {recipientEmail}(으)로 답변 메일을 보내요. 등록한 답변은 고칠 수 없어요.</p>
    <label htmlFor={`${id}-title`} className="font-bold">답변 제목 (필수 · 100자 이하)</label>
    <input id={`${id}-title`} name="answerTitle" value={form.answerTitle} onChange={(event) => update('answerTitle', event.target.value)}
      required maxLength={SUPPORT_ANSWER_LIMITS.answerTitle} disabled={sending}
      aria-invalid={fields.answerTitle !== undefined} aria-describedby={error} className={ADMIN_INPUT} />
    <label htmlFor={`${id}-content`} className="font-bold">답변 내용 (필수 · 5,000자 이하)</label>
    <textarea id={`${id}-content`} name="answerContent" rows={6} value={form.answerContent} onChange={(event) => update('answerContent', event.target.value)}
      required maxLength={SUPPORT_ANSWER_LIMITS.answerContent} disabled={sending}
      aria-invalid={fields.answerContent !== undefined} aria-describedby={error} className={`${ADMIN_INPUT} resize-y`} />
    <p className="text-[11px] text-right">{form.answerContent.length.toLocaleString()} / 5,000</p>
    {error && <p id={error} ref={summary} role="alert" tabIndex={-1} className="text-[#7f1d1d] whitespace-pre-wrap break-words">
      {problem ?? [failure, ...Object.values(fields)].join('\n')}
    </p>}
    <button type="submit" className={`${ADMIN_BUTTON} plaque-yellow`} disabled={sending}>{sending ? '답변 등록 중…' : '답변 등록'}</button>
  </form>;
}
