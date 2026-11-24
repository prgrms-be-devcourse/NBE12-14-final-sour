import { useEffect, useId, useRef, useState, type SubmitEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useApiClient, useAuthSession } from '../api/ApiProvider';
import { errorMessage, fieldErrorMap, isApiError } from '../api/errors';
import { getMySupports, getSupportDetail, safeSupportAttachment, supportKeys, type MemberSupportRequest, type SupportStatus } from '../api/supports';
import { useMyInfo, type MyInfo } from '../api/users';
import { useMemberSupportSender } from '../hooks/useMemberSupportSender';
import { SUPPORT_FORM_LIMITS, validateSupportForm } from '../utils/mockSupport';
import { LoadNotice } from './LoadNotice';
import { PhoneShell } from './PhoneShell';

const BUTTON = 'pixel-plaque plaque-wood min-h-12 min-w-12 px-3 py-2 text-xs font-bold cursor-pointer disabled:opacity-50 disabled:cursor-default';
const INPUT = 'w-full min-h-12 border-2 border-[#261608] bg-white px-2 py-2 text-base focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#261608] disabled:bg-stone-100';
const BOX = 'rpg-box p-3 border-2 border-[#261608] bg-[#fdf5df] shadow-[2px_2px_0px_#261608]';
const STATUS: Record<SupportStatus, string> = { RECEIVED: '접수 · 답변 대기', ANSWERED: '답변 완료' };
const format = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' });
const date = (value: string) => <time dateTime={value}>{format.format(new Date(value))} (KST)</time>;
type View = { kind: 'list' } | { kind: 'write' } | { kind: 'detail'; id: number };

export function SupportMemberScreenPhone({ onBack, onGoToLogin }: { onBack: () => void; onGoToLogin: () => void }) {
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  if (signedIn && !me.data) return <LoadNotice status={me.isError ? 'failed' : 'loading'} name="내 정보" failureDetail={errorMessage(me.error)}
    onRetry={() => void me.refetch()} onBack={onBack} backLabel="내 정보로" />;
  return <PhoneShell>
    {signedIn && me.data ? <SupportWorkspace key={me.data.id} me={me.data} onBack={onBack} /> : <div className="p-4 font-pixel text-[#261608]">
      <h1 className="text-sm font-black mb-3">내 문의</h1>
      <p role="status" className="text-xs mb-3">회원 문의를 이용하려면 로그인해 주세요.</p>
      <button type="button" className={BUTTON} onClick={onGoToLogin}>로그인</button>
      <button type="button" className={BUTTON} onClick={onBack}>내 정보로</button>
    </div>}
  </PhoneShell>;
}

function SupportWorkspace({ me, onBack }: { me: MyInfo; onBack: () => void }) {
  const [view, setView] = useState<View>({ kind: 'list' });
  const [page, setPage] = useState(0);
  const [form, setForm] = useState<MemberSupportRequest>(() => ({ recipientEmail: me.email, title: '', content: '' }));
  const sender = useMemberSupportSender(me.id);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus(); }, [view]);
  const goList = () => setView({ kind: 'list' });
  return <div className="flex-1 min-h-0 flex flex-col font-pixel text-[#261608]">
    <header className="bg-[#faecc7] border-b-2 border-[#261608] p-2 flex items-center gap-3 shrink-0">
      <button type="button" className={BUTTON} onClick={view.kind === 'list' ? onBack : goList}>
        {view.kind === 'list' ? '내 정보로' : '목록으로'}
      </button>
      <h1 ref={heading} tabIndex={-1} className="text-sm font-black">{view.kind === 'list' ? '내 문의' : view.kind === 'write' ? '회원 문의 등록' : '문의 상세'}</h1>
    </header>
    <div className="flex-1 min-h-0 overflow-y-auto p-3 flex flex-col gap-3 text-xs">
      {sender.created && view.kind === 'list' && <p role="status" className={`${BOX} text-[#14532d]`}>문의 #{sender.created.supportId}가 접수되었습니다. 목록에서 내용을 확인해 주세요.</p>}
      {sender.phase === 'uncertain' && <p role="alert" className={`${BOX} text-[#7f1d1d]`}>접수 여부를 확인할 수 없습니다. 이미 접수되었을 수 있어 자동으로 다시 보내지 않습니다. 내 문의 목록을 먼저 확인해 주세요.</p>}
      {view.kind === 'list' ? <>
        <button type="button" className={`${BUTTON} plaque-yellow`} onClick={() => setView({ kind: 'write' })}>새 문의 작성</button>
        <SupportList userId={me.id} page={page} onPage={setPage} onOpen={(id) => setView({ kind: 'detail', id })} />
      </> : view.kind === 'write' ? <SupportMemberForm form={form} onChange={setForm} sender={sender}
        onSent={() => { setForm((current) => ({ ...current, title: '', content: '' })); setPage(0); goList(); }} />
        : <SupportDetailView userId={me.id} supportId={view.id} />}
    </div>
  </div>;
}

type Sender = ReturnType<typeof useMemberSupportSender>;
function SupportMemberForm({ form, onChange, sender, onSent }: {
  form: MemberSupportRequest; onChange: (value: MemberSupportRequest) => void; sender: Sender; onSent: () => void;
}) {
  const id = useId();
  const [problem, setProblem] = useState<string | null>(null);
  const summary = useRef<HTMLParagraphElement>(null);
  const fields = fieldErrorMap(sender.error);
  const failure = isApiError(sender.error) && sender.error.status === 429
    ? '회원은 1시간에 5건까지 문의할 수 있어요. 잠시 후 다시 시도해 주세요.' : sender.error ? errorMessage(sender.error) : null;
  useEffect(() => { if (problem || sender.error) summary.current?.focus(); }, [problem, sender.error]);
  const submit = async (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (sender.phase !== 'idle') return;
    const issue = validateSupportForm(form); setProblem(issue);
    if (issue) return;
    if (await sender.send(form)) onSent();
  };
  const update = (key: keyof MemberSupportRequest, value: string) => {
    onChange({ ...form, [key]: value }); setProblem(null); sender.clearValidationError();
  };
  return <form action="/api/v1/supports" method="post" noValidate onSubmit={(event) => void submit(event)}
    aria-label="회원 문의 작성" aria-busy={sender.phase === 'sending'} className={`${BOX} flex flex-col gap-3`}>
    <p>답변은 내 문의 상세와 받을 이메일에서 확인할 수 있어요. 회원별 1시간에 5건까지 접수할 수 있어요.</p>
    <label htmlFor={`${id}-email`} className="font-bold">받을 이메일 (필수)</label>
    <input id={`${id}-email`} name="recipientEmail" type="email" autoComplete="email" autoCapitalize="none" autoCorrect="off" spellCheck={false}
      value={form.recipientEmail} onChange={(event) => update('recipientEmail', event.target.value)} required maxLength={SUPPORT_FORM_LIMITS.recipientEmail}
      disabled={sender.phase === 'sending'} aria-invalid={fields.recipientEmail !== undefined} aria-describedby={fields.recipientEmail ? `${id}-error` : undefined} className={INPUT} />
    <label htmlFor={`${id}-title`} className="font-bold">제목 (필수 · 100자 이하)</label>
    <input id={`${id}-title`} name="title" value={form.title} onChange={(event) => update('title', event.target.value)} required maxLength={SUPPORT_FORM_LIMITS.title}
      disabled={sender.phase === 'sending'} aria-invalid={fields.title !== undefined} aria-describedby={fields.title ? `${id}-error` : undefined} className={INPUT} />
    <label htmlFor={`${id}-content`} className="font-bold">본문 (필수 · 5,000자 이하)</label>
    <textarea id={`${id}-content`} name="content" rows={6} value={form.content} onChange={(event) => update('content', event.target.value)} required maxLength={SUPPORT_FORM_LIMITS.content}
      disabled={sender.phase === 'sending'} aria-invalid={fields.content !== undefined} aria-describedby={fields.content ? `${id}-error` : undefined} className={`${INPUT} resize-y`} />
    <p className="text-[11px] text-right">{form.content.length.toLocaleString()} / 5,000</p>
    <p className="text-[11px]">첨부 업로드는 준비 중입니다. 화면을 나가도 이미 전송된 문의는 서버에 접수될 수 있어요.</p>
    {(problem || failure) && <p id={`${id}-error`} ref={summary} role="alert" tabIndex={-1} className="text-[#7f1d1d] whitespace-pre-wrap break-words">
      {problem ?? [failure, ...Object.values(fields)].join('\n')}
    </p>}
    {sender.phase === 'uncertain' && <button type="button" className={BUTTON} onClick={sender.prepareRetry}>목록에서 확인했고 다시 보내기 준비</button>}
    <button type="submit" className={`${BUTTON} plaque-yellow`} disabled={sender.phase !== 'idle'}>
      {sender.phase === 'sending' ? '문의 보내는 중…' : '문의 등록'}
    </button>
  </form>;
}

function SupportList({ userId, page, onPage, onOpen }: { userId: number; page: number; onPage: (page: number) => void; onOpen: (id: number) => void }) {
  const client = useApiClient();
  const query = useQuery({ queryKey: supportKeys.list(userId, page), queryFn: ({ signal }) => getMySupports(client, page, signal), staleTime: 0 });
  const data = query.data;
  return <>
    <button type="button" className={BUTTON} onClick={() => void query.refetch()} disabled={query.isFetching}>문의 목록 새로고침</button>
    <div aria-busy={query.isFetching}>
      {query.isPending ? <p>문의 목록을 불러오는 중…</p> : query.isError ? <div className={BOX}>
        <p role="alert">문의 목록을 불러오지 못했습니다. {errorMessage(query.error)}</p>
        <button type="button" className={BUTTON} onClick={() => void query.refetch()}>문의 목록 다시 조회</button>
      </div> : data && (data.items.length === 0 ? <p>{page === 0 ? '아직 등록한 문의가 없습니다.' : '이 페이지에 문의가 없습니다. 이전 페이지를 확인해 주세요.'}</p>
        : <ol aria-label="내 문의 목록" className="list-decimal pl-5 space-y-3" start={data.page * data.size + 1}>
          {data.items.map((item) => <li key={item.supportId} data-testid="member-support-row" className={BOX}>
            <button type="button" className="min-h-12 w-full text-left font-bold break-words cursor-pointer" onClick={() => onOpen(item.supportId)}>{item.title}</button>
            <p>{STATUS[item.status]}</p><p className="text-[11px]">{date(item.createdAt)}</p>
          </li>)}
        </ol>)}
    </div>
    <p role="status">{page + 1}페이지{data && !query.isError ? ` · 전체 ${data.totalCount.toLocaleString()}건` : ''}{query.isFetching ? ' · 조회 중…' : ''}</p>
    <nav aria-label="문의 목록 페이지" className="flex justify-between gap-3 mt-auto">
      <button type="button" className={BUTTON} disabled={page === 0 || query.isFetching} onClick={() => onPage(page - 1)}>이전 페이지</button>
      <button type="button" className={BUTTON} disabled={query.isFetching || query.isError || !data?.hasNext} onClick={() => onPage(page + 1)}>다음 페이지</button>
    </nav>
  </>;
}

function SupportDetailView({ userId, supportId }: { userId: number; supportId: number }) {
  const client = useApiClient();
  const query = useQuery({ queryKey: supportKeys.detail(userId, supportId), queryFn: ({ signal }) => getSupportDetail(client, supportId, signal), staleTime: 0 });
  if (query.isPending) return <p>문의 상세를 불러오는 중…</p>;
  if (query.isError) {
    const code = isApiError(query.error) ? query.error.code : null;
    const message = code === 'SUPPORT_NOT_OWNER' ? '내 문의만 확인할 수 있습니다.' : code === 'SUPPORT_NOT_FOUND' ? '문의를 찾을 수 없습니다.' : '문의 상세를 불러오지 못했습니다.';
    return <div className={BOX}><p role="alert">{message} {errorMessage(query.error)}</p>
      <button type="button" className={BUTTON} onClick={() => void query.refetch()} disabled={query.isFetching}>문의 상세 다시 조회</button></div>;
  }
  const detail = query.data;
  const attachment = safeSupportAttachment(detail.attachFileUrl);
  return <>
    <button type="button" className={BUTTON} onClick={() => void query.refetch()} disabled={query.isFetching}>문의 상세 새로고침</button>
    <article className={`${BOX} flex flex-col gap-2 break-words`} aria-busy={query.isFetching}>
      <h2 className="font-black text-sm">{detail.title}</h2>
      <p>{STATUS[detail.status]}</p><p className="text-[11px]">{date(detail.createdAt)}</p>
      <p data-testid="member-support-content" className="whitespace-pre-wrap">{detail.content}</p>
      {attachment ? <a href={attachment} target="_blank" rel="noopener noreferrer" className="underline min-h-12 inline-flex items-center">첨부 파일 열기 (새 탭)</a>
        : detail.attachFileUrl && <p>첨부 파일 주소를 확인할 수 없습니다.</p>}
    </article>
    <section className={`${BOX} flex flex-col gap-2 break-words`} aria-label="문의 답변">
      <h2 className="font-black text-sm">답변</h2>
      {detail.answer ? <><h3 className="font-bold">{detail.answer.title}</h3><p data-testid="member-support-answer" className="whitespace-pre-wrap">{detail.answer.content}</p></>
        : <p>아직 답변이 등록되지 않았습니다.</p>}
    </section>
  </>;
}
