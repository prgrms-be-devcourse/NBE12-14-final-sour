import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient, useAuthSession } from '../api/ApiProvider';
import { getActiveItemEffect, itemKeys, purchaseItem, useItemCatalog, type EffectSnapshot, type ItemPurchaseResponse, type ShopItem } from '../api/items';
import { errorMessage, isApiError } from '../api/errors';
import { selectByMode } from '../api/mode';
import { userKeys, useMyInfo, type MyInfo } from '../api/users';
import type { ClickUpgradeGate } from './useCampusClicks';

type Phase = 'idle' | 'buying' | 'uncertain' | 'checking';
interface Attempt { userId: number; itemId: number; previousExpiry: string | null; abort: AbortController; release: () => void }
export interface ItemShopView {
  items: readonly ShopItem[];
  catalogNotice: string | null;
  reloadCatalog: () => void;
  effect: EffectSnapshot | undefined;
  effectNotice: string | null;
  reloadEffect: () => void;
  balance: number | null;
  pending: boolean;
  error: string | null;
  status: string | null;
  receipt: ItemPurchaseResponse | null;
  disabledReason: (item: ShopItem) => string | null;
  purchase: (itemId: number) => void;
  reconcile: (() => void) | null;
}
const ItemShopContext = createContext<ItemShopView | null>(null);

/** One root controller shares the click/upgrade wallet lock and survives shop tab navigation. */
export function useApiItemShopController(gate: ClickUpgradeGate): ItemShopView {
  const client = useApiClient();
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  const userId = signedIn ? me.data?.id : undefined;
  const catalog = useItemCatalog();
  const queryClient = useQueryClient();
  const [generation, setGeneration] = useState(0);
  const activeKey = itemKeys.active(userId, generation);
  const effects = useQuery({
    queryKey: activeKey,
    enabled: userId !== undefined,
    queryFn: async ({ signal }): Promise<EffectSnapshot> => ({ effect: await getActiveItemEffect(client, signal), receivedAt: performance.now() }),
    staleTime: 0,
    // Stop polling at null. Focus refetch and elapsed-time rendering cover background-tab throttling.
    refetchInterval: (query) => query.state.data?.effect
      ? Math.min(30_000, Math.max(1000, query.state.data.effect.remainingSeconds * 1000 + 100)) : false,
  });
  const [phase, setPhase] = useState<Phase>('idle');
  const phaseRef = useRef<Phase>('idle');
  const attempt = useRef<Attempt | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [receipt, setReceipt] = useState<ItemPurchaseResponse | null>(null);
  useEffect(() => {
    const cancel = () => {
      attempt.current?.abort.abort(); attempt.current?.release(); attempt.current = null; phaseRef.current = 'idle';
    };
    setPhase('idle'); setError(null); setStatus(null); setReceipt(null);
    const unsubscribe = client.session.subscribe(() => {
      if (client.session.getState().status === 'signed-in') return;
      cancel(); setPhase('idle'); setError(null); setStatus(null); setReceipt(null);
      void queryClient.cancelQueries({ queryKey: itemKeys.active(userId) });
      for (const query of queryClient.getQueryCache().findAll({ queryKey: itemKeys.active(userId) })) query.reset();
      setGeneration((value) => value + 1);
    });
    return () => { unsubscribe(); cancel(); void queryClient.cancelQueries({ queryKey: itemKeys.active(userId) }); };
  }, [client, queryClient, userId]);
  const changePhase = (value: Phase) => { phaseRef.current = value; setPhase(value); };
  const active = (work: Attempt) => attempt.current === work && !work.abort.signal.aborted
    && client.session.getState().status === 'signed-in' && queryClient.getQueryData<MyInfo>(userKeys.me)?.id === work.userId;
  const finish = (work: Attempt) => { attempt.current = null; work.release(); changePhase('idle'); };
  const refreshHistory = (memberId: number) => {
    const queryKey = itemKeys.history(memberId);
    // invalidateQueries alone reuses an initial fetch with no cached data. Cancel that stale snapshot too.
    void queryClient.cancelQueries({ queryKey }).then(() => queryClient.invalidateQueries({ queryKey }));
  };
  const catalogNotice = catalog.isError ? `아이템 목록을 불러오지 못했습니다. ${errorMessage(catalog.error)}`
    : catalog.isPending ? '아이템 목록을 불러오는 중…' : !catalog.data?.length ? '판매 중인 아이템이 없습니다.' : null;
  const effectNotice = !signedIn ? '적용 효과를 보려면 로그인해 주세요.'
    : effects.isError ? `적용 효과를 확인하지 못했습니다. ${errorMessage(effects.error)}`
    : userId === undefined || effects.isPending || effects.isFetching ? '적용 효과를 확인하는 중…' : null;
  const disabledReason = (item: ShopItem): string | null => item.payType === 'CREDIT' ? '결제 준비 중 · 크레딧 상품은 아직 구매할 수 없습니다.'
    : item.type !== 'MULTIPLIER' || item.payType !== 'CONTRIBUTION' ? '아직 지원하지 않는 상품입니다.'
    : !signedIn ? '아이템을 구매하려면 로그인해 주세요.'
    : me.isError || !me.data ? '내 정보를 확인한 뒤 구매할 수 있습니다.'
    : effects.data === undefined || effects.isError || effects.isFetching ? '적용 효과를 확인한 뒤 구매할 수 있습니다.'
    : catalogNotice ?? (phase === 'uncertain' ? '구매 결과를 먼저 서버에서 확인해 주세요.'
      : phase !== 'idle' ? '구매 결과를 확인하는 중…' : gate.upgradeBlocked ? '미확정 클릭 또는 구매·강화가 끝난 뒤 구매할 수 있습니다.'
      : me.data.totalContribScore < item.price ? '아이템 구매에 필요한 기여도가 부족합니다.' : null);

  const buy = async (itemId: number) => {
    if (phaseRef.current !== 'idle' || !signedIn || catalogNotice) return;
    const item = catalog.data?.find((row) => row.id === itemId);
    const profile = queryClient.getQueryData<MyInfo>(userKeys.me);
    const baseline = queryClient.getQueryData<EffectSnapshot>(activeKey);
    if (!baseline || effects.isError || effects.isFetching) return;
    if (!item || item.payType !== 'CONTRIBUTION' || item.type !== 'MULTIPLIER' || !profile || profile.id !== userId) return;
    if (profile.totalContribScore < item.price) { setError('아이템 구매에 필요한 기여도가 부족합니다.'); return; }
    const release = gate.beginUpgrade();
    if (!release) { setError('미확정 클릭 또는 구매·강화가 끝난 뒤 구매할 수 있습니다.'); return; }
    const work: Attempt = { userId: profile.id, itemId, previousExpiry: baseline.effect?.expiredAt ?? null, abort: new AbortController(), release };
    attempt.current = work; changePhase('buying'); setError(null); setStatus(null); setReceipt(null);
    try {
      await Promise.all([queryClient.cancelQueries({ queryKey: userKeys.me, exact: true }), queryClient.cancelQueries({ queryKey: activeKey, exact: true })]);
      if (!active(work)) return;
      const result = await purchaseItem(client, itemId, work.abort.signal);
      if (!active(work)) return;
      await Promise.all([queryClient.cancelQueries({ queryKey: userKeys.me, exact: true }), queryClient.cancelQueries({ queryKey: activeKey, exact: true })]);
      if (!active(work)) return;
      queryClient.setQueryData<MyInfo>(userKeys.me, (current) => current?.id === work.userId ? { ...current, totalContribScore: result.myContribScore } : current);
      setReceipt(result); setStatus(`${result.item.name} 구매 완료. 적용 효과는 서버 조회 기준입니다.`);
      finish(work);
      // A failed GET must never turn a confirmed purchase into a retryable POST.
      void queryClient.invalidateQueries({ queryKey: activeKey, exact: true });
      refreshHistory(work.userId);
    } catch (failure) {
      if (!active(work)) return;
      setError(errorMessage(failure));
      const uncertain = !isApiError(failure) || failure.kind === 'network' || failure.kind === 'invalid-response'
        || (failure.status !== null && failure.status >= 500);
      if (uncertain) changePhase('uncertain'); else finish(work);
    }
  };
  const reconcile = async () => {
    const work = attempt.current;
    if (!work || phaseRef.current !== 'uncertain' || !active(work)) return;
    changePhase('checking');
    try {
      await Promise.all([queryClient.cancelQueries({ queryKey: userKeys.me, exact: true }), queryClient.cancelQueries({ queryKey: activeKey, exact: true })]);
      if (!active(work)) return;
      const [profile, effect] = await Promise.all([
        client.get<MyInfo>('/users/me', { signal: work.abort.signal }), getActiveItemEffect(client, work.abort.signal),
      ]);
      if (!active(work)) return;
      if (profile.id !== work.userId) throw new Error('Unexpected profile');
      queryClient.setQueryData(userKeys.me, profile);
      queryClient.setQueryData<EffectSnapshot>(activeKey, { effect, receivedAt: performance.now() });
      if (effect?.itemId !== work.itemId || effect.remainingSeconds <= 0 || effect.expiredAt === work.previousExpiry) {
        setError('새 적용 효과가 확인되지 않았습니다. 원래 구매가 처리 중일 수 있어 클릭·구매·강화를 계속 보류합니다. 잠시 후 다시 조회해 주세요.');
        changePhase('uncertain'); return;
      }
      setError(null); setStatus('서버의 새 적용 효과와 기여도를 확인했습니다. 구매 요청은 다시 보내지 않았습니다.'); finish(work);
      refreshHistory(work.userId);
    } catch (failure) {
      if (!active(work)) return;
      setError(`구매 결과를 확인하지 못했습니다. ${errorMessage(failure)}`); changePhase('uncertain');
    }
  };
  return {
    items: catalog.data ?? [], catalogNotice, reloadCatalog: () => { void catalog.refetch(); },
    effect: signedIn ? effects.data : undefined, effectNotice, reloadEffect: () => { if (userId !== undefined) void effects.refetch(); },
    balance: signedIn ? me.data?.totalContribScore ?? null : null,
    pending: phase === 'buying' || phase === 'checking', error, status, receipt, disabledReason,
    purchase: (id) => { void buy(id); }, reconcile: phase === 'uncertain' ? () => { void reconcile(); } : null,
  };
}
export const useItemShopController = selectByMode({ api: useApiItemShopController, mock: (_gate: ClickUpgradeGate): ItemShopView | null => null });
export function ItemShopProvider({ value, children }: { value: ItemShopView | null; children: ReactNode }) {
  return <ItemShopContext.Provider value={value}>{children}</ItemShopContext.Provider>;
}
export function useItemShop(): ItemShopView | null { return useContext(ItemShopContext); }
