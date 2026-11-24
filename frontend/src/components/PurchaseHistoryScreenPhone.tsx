import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ChevronLeft } from 'lucide-react';
import { useApiClient, useAuthSession } from '../api/ApiProvider';
import { errorMessage } from '../api/errors';
import { getPurchaseHistory, itemKeys, type PurchaseHistoryItem } from '../api/items';
import { useMyInfo } from '../api/users';
import { LoadNotice } from './LoadNotice';
import { PhoneShell } from './PhoneShell';

const BUTTON = 'pixel-plaque plaque-wood min-h-11 min-w-11 px-3 py-2 text-xs font-bold cursor-pointer disabled:opacity-50 disabled:cursor-default';
const dateFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' });

export function PurchaseHistoryScreenPhone({ onBack, onGoToLogin }: { onBack: () => void; onGoToLogin: () => void }) {
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  if (signedIn && !me.data) return <LoadNotice status={me.isError ? 'failed' : 'loading'} name="내 정보"
    failureDetail={errorMessage(me.error)} onRetry={() => void me.refetch()} onBack={onBack} backLabel="내 정보로" />;
  return (
    <PhoneShell>
      <header className="bg-[#faecc7] border-b-2 border-[#261608] p-2 flex items-center gap-3 shrink-0 font-pixel text-[#261608]">
        <button type="button" onClick={onBack} className={BUTTON} aria-label="내 정보로 돌아가기">
          <ChevronLeft className="w-4 h-4 inline" aria-hidden="true" /> 뒤로
        </button>
        <h1 className="text-sm font-black">아이템 구매 내역</h1>
      </header>
      {signedIn && me.data ? <HistoryPages key={me.data.id} userId={me.data.id} /> : (
        <div className="flex-1 p-4 font-pixel text-[#261608]">
          <p role="status" className="text-xs mb-3">구매 내역을 보려면 로그인해 주세요.</p>
          <button type="button" className={BUTTON} onClick={onGoToLogin}>로그인</button>
        </div>
      )}
    </PhoneShell>
  );
}

function HistoryPages({ userId }: { userId: number }) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const scroll = useRef<HTMLDivElement>(null);
  const history = useQuery({
    queryKey: itemKeys.historyPage(userId, page),
    queryFn: ({ signal }) => getPurchaseHistory(client, page, signal),
    // A new visit always checks the server, including purchases from another tab. No cross-member placeholders.
    staleTime: 0,
  });
  const data = history.data;
  const navigate = (next: number) => { setPage(next); if (scroll.current) scroll.current.scrollTop = 0; };
  const latest = () => {
    // Offset pages all shift after a purchase; do not keep the old later pages fresh.
    void queryClient.invalidateQueries({ queryKey: itemKeys.history(userId), refetchType: 'none' });
    navigate(0);
    if (page === 0) void history.refetch();
  };
  return (
    <div className="flex-1 min-h-0 flex flex-col font-pixel text-[#261608]">
      <div className="p-3 flex items-center justify-between gap-2 shrink-0 border-b border-[#8b5327]">
        <p className="text-[11px]">최신 구매순 · 한국 시간(KST)</p>
        <button type="button" className={BUTTON} onClick={latest} disabled={history.isFetching}>최신 내역</button>
      </div>
      <div ref={scroll} className="flex-1 min-h-0 overflow-y-auto p-3" aria-busy={history.isFetching}>
        {history.isPending ? <p className="text-xs">구매 내역을 불러오는 중…</p>
          : history.isError ? <div className="flex flex-col gap-3">
            <p role="alert" className="text-xs text-[#7f1d1d]">구매 내역을 불러오지 못했습니다. {errorMessage(history.error)}</p>
            <button type="button" className={BUTTON} onClick={() => void history.refetch()} disabled={history.isFetching}>구매 내역 다시 조회</button>
          </div>
          : data && (data.items.length === 0 ? <p className="text-xs">{page === 0 ? '아직 구매한 아이템이 없습니다.' : '이 페이지에 구매 내역이 없습니다. 최신 내역을 확인해 주세요.'}</p>
            : <ol aria-label="구매 내역" className="list-decimal pl-5 space-y-3">
              {data.items.map((row) => <HistoryRow key={row.userItemId} row={row} />)}
            </ol>)}
      </div>
      <footer className="shrink-0 p-3 border-t-2 border-[#261608] bg-[#faecc7] flex flex-col gap-2">
        <p role="status" className="text-[11px] text-center">
          {page + 1}페이지{data && !history.isError ? ` · 전체 ${data.totalCount.toLocaleString()}건` : ''}{history.isFetching ? ' · 조회 중…' : ''}
        </p>
        <nav aria-label="구매 내역 페이지" className="flex justify-between gap-3">
          <button type="button" className={BUTTON} onClick={() => navigate(page - 1)} disabled={page === 0 || history.isFetching}>이전 페이지</button>
          <button type="button" className={BUTTON} onClick={() => navigate(page + 1)} disabled={history.isFetching || history.isError || !data?.hasNext}>다음 페이지</button>
        </nav>
      </footer>
    </div>
  );
}

function HistoryRow({ row }: { row: PurchaseHistoryItem }) {
  const unit = row.item?.payType === 'CONTRIBUTION' ? '점 (기여도)' : row.item?.payType === 'CREDIT' ? '크레딧' : '· 결제 수단 확인 불가';
  return (
    <li data-testid="purchase-history-row" className="rpg-box p-3 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] text-[11px] break-words">
      <h2 className="text-xs font-black mb-2">{row.item?.name ?? '상품 정보 없음'}</h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-1">
        <dt>결제 금액</dt><dd>{row.price.toLocaleString()} {unit}</dd>
        <dt>구매 시각</dt><dd><time dateTime={row.createdAt}>{dateFormat.format(new Date(row.createdAt))}</time></dd>
        <dt>만료 시각</dt><dd>{row.expiredAt === null ? '만료일 없음' : <time dateTime={row.expiredAt}>{dateFormat.format(new Date(row.expiredAt))}</time>}</dd>
      </dl>
    </li>
  );
}
