import { useState, type ReactNode } from 'react';
import { ALLIANCE_MAX_SCHOOLS, ALLIANCE_STATUS_LABEL, MATCH_STATUS_LABEL, type AllianceApplicationStatus } from '../api/alliances';
import { useAllianceApplications, useAllianceDirectory, useAllianceMatches, useAllianceSchools, type AllianceSummary } from '../api/allianceLists';
import { errorMessage, isApiError } from '../api/errors';
import { LoadNotice } from './LoadNotice';

const PANEL = 'rpg-box p-3 bg-[#fdf5df] border-2 border-[#261608] flex flex-col gap-2 text-xs';
const BUTTON = 'pixel-plaque plaque-wood min-h-11 px-3 py-2 text-xs font-bold disabled:opacity-50';
const date = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'short', timeStyle: 'short' });
const applicationLabel: Record<AllianceApplicationStatus, string> = { PENDING: '승인 대기', ACCEPTED: '승인', REJECTED: '거절', LEFT: '소속 종료' };
const resultLabel = { WIN: '승리', LOSE: '패배', DRAW: '무승부' } as const;

interface Pages<T> {
  isPending: boolean; isError: boolean; isFetchNextPageError: boolean; isFetching: boolean; hasNextPage: boolean;
  error: unknown; data?: { pages: { items: T[] }[] };
  refetch: () => Promise<unknown>; fetchNextPage: () => Promise<unknown>;
}

function PageRows<T extends { id: number }>({ name, query, empty, filter, row }: {
  name: string; query: Pages<T>; empty: string; filter?: (item: T) => boolean; row: (item: T) => ReactNode;
}) {
  const status = isApiError(query.error) ? query.error.status : null;
  const denied = status === 401 || status === 403 || status === 404;
  if (query.isPending || (query.isError && (!query.isFetchNextPageError || denied))) {
    return <LoadNotice status={query.isPending ? 'loading' : 'failed'} name={name} failureDetail={errorMessage(query.error)}
      onRetry={() => void query.refetch()} variant="panel" />;
  }
  const items = [...new Map(query.data?.pages.flatMap((page) => page.items.map((item) => [item.id, item] as const))).values()];
  const visible = filter ? items.filter(filter) : items;
  return <>
    {visible.length ? <ul role="list" className="flex flex-col gap-2" aria-label={name}>
      {visible.map((item) => <li key={item.id} className="border-b border-[#b9a076] pb-2 break-words">{row(item)}</li>)}
    </ul> : <p>{query.hasNextPage ? '이 페이지에는 해당 항목이 없어요. 더 찾아볼 수 있어요.' : empty}</p>}
    {query.isFetchNextPageError && <p role="alert">다음 페이지를 가져오지 못했어요. {errorMessage(query.error)}</p>}
    {query.hasNextPage && <button type="button" className={`${BUTTON} self-start`} disabled={query.isFetching}
      onClick={() => void query.fetchNextPage()}>{name} 더 보기</button>}
  </>;
}

export function AllianceDirectory({ userId, mode, ownId, disabled, onSelect }: {
  userId: number; mode: 'recruiting' | 'opponents' | 'history'; ownId?: number; disabled?: boolean;
  onSelect: (alliance: AllianceSummary) => void;
}) {
  const query = useAllianceDirectory(userId, mode);
  const name = mode === 'recruiting' ? '연합 목록' : mode === 'opponents' ? '상대 연합 목록' : '이전 소속 연합 목록';
  return <div className={PANEL}>
    <h3 className="text-sm font-black">{name}</h3>
    <PageRows name={name} query={query} empty="표시할 연합이 없어요."
      filter={mode === 'opponents' ? (item) => item.id !== ownId && item.memberCount === ALLIANCE_MAX_SCHOOLS : undefined}
      row={(item) => <>
        <button type="button" className={`${BUTTON} max-w-full break-words text-left`} disabled={disabled} onClick={() => onSelect(item)}>
          {item.name} {mode === 'opponents' ? '선택' : mode === 'history' ? '종료 대결 보기' : '상세 보기'}
        </button>
        <p>{ALLIANCE_STATUS_LABEL[item.status]} · 학교 {item.memberCount}/{ALLIANCE_MAX_SCHOOLS} · {date.format(new Date(item.expiredAt))} 만료</p>
      </>} />
  </div>;
}

export function AllianceSchoolList({ userId, allianceId }: { userId: number; allianceId: number }) {
  const query = useAllianceSchools(userId, allianceId);
  if (query.isPending || query.isError) return <LoadNotice status={query.isError ? 'failed' : 'loading'} name="소속 학교"
    failureDetail={errorMessage(query.error)} onRetry={() => void query.refetch()} variant="panel" />;
  return query.data.length ? <ul role="list" className="flex flex-wrap gap-2" aria-label="소속 학교">
    {query.data.map((school) => <li key={school.id} className="flex items-center gap-1 break-words">
      {school.logoUrl && <img className="size-5 object-contain" width={20} height={20} src={school.logoUrl} alt="" />}
      {school.name}
    </li>)}
  </ul> : <p>현재 소속 학교가 없어요.</p>;
}

export function AllianceApplicationList({ userId, visible, allianceId, busy, onDecide }: {
  userId: number; visible: boolean; allianceId?: number; busy?: boolean; onDecide?: (id: number, decision: 'approve' | 'reject') => void;
}) {
  const query = useAllianceApplications(userId, visible, allianceId);
  const sent = allianceId === undefined;
  const name = sent ? '우리 학교가 보낸 가입 신청' : '받은 가입 신청';
  return <section aria-label={name} className={PANEL}>
    <h3 className="text-sm font-black">{name}</h3>
    <PageRows name={name} query={query} empty="가입 신청이 없어요." row={(item) => <>
      <p className="font-bold">{sent ? item.alliance.name : item.university.name} · {applicationLabel[item.status]}</p>
      <p>{date.format(new Date(item.createdAt))} 신청</p>
      {item.status === 'PENDING' && onDecide && <div className="flex flex-wrap gap-2">
        <button type="button" className={`${BUTTON} plaque-yellow`} disabled={busy} aria-label={`${item.university.name} 가입 신청 승인`}
          onClick={() => onDecide(item.id, 'approve')}>승인</button>
        <button type="button" className={BUTTON} disabled={busy} aria-label={`${item.university.name} 가입 신청 거절`}
          onClick={() => onDecide(item.id, 'reject')}>거절</button>
      </div>}
    </>} />
  </section>;
}

export function AllianceMatchList({ userId, allianceId, visible, history = false, selected, onSelect }: {
  userId: number; allianceId: number; visible: boolean; history?: boolean; selected: number | null; onSelect: (id: number) => void;
}) {
  const query = useAllianceMatches(userId, allianceId, history, visible);
  const name = history ? '종료 대결 이력' : '대결 목록';
  return <div className={PANEL}>
    <h3 className="text-sm font-black">{name}</h3>
    <PageRows name={name} query={query} empty="표시할 대결이 없어요." row={(item) => <>
      <p className="font-bold">{item.opponent.name} · {item.direction === 'INCOMING' ? '받은 대결' : '보낸 대결'} · {MATCH_STATUS_LABEL[item.status]}</p>
      {item.scheduledStartAt && <p>예약 {date.format(new Date(item.scheduledStartAt))}</p>}
      {item.endedAt && <p>{item.status === 'FINISHED' ? '종료' : '종료 예정'} {date.format(new Date(item.endedAt))}</p>}
      {item.result && <p>{resultLabel[item.result]} · {item.myScore} : {item.opponentScore}</p>}
      <button type="button" className={BUTTON} aria-pressed={selected === item.id} onClick={() => onSelect(item.id)}
        aria-label={`${item.opponent.name} 대결 #${item.id} 상세 보기`}>대결 #{item.id} 상세 보기</button>
    </>} />
  </div>;
}

export function AllianceHistory({ userId, visible, detail }: {
  userId: number; visible: boolean; detail: (alliance: AllianceSummary, id: number, close: () => void) => ReactNode;
}) {
  const [alliance, setAlliance] = useState<AllianceSummary | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  return <div className="flex flex-col gap-3 text-xs">
    <AllianceDirectory userId={userId} mode="history" onSelect={(item) => { setAlliance(item); setSelected(null); }} />
    {alliance && <>
      <p className="font-bold break-words">{alliance.name} 대결 이력</p>
      <AllianceMatchList userId={userId} allianceId={alliance.id} visible={visible} history selected={selected} onSelect={setSelected} />
      {selected !== null && detail(alliance, selected, () => setSelected(null))}
    </>}
  </div>;
}
