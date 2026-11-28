import { useEffect, useId, useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { LockKeyhole, Package } from 'lucide-react';
import type { ShopItem } from '../api/items';
import { drinkArt } from '../data/campusItemArt.ts';
import type { ItemShopView } from '../hooks/useItemShop';
import { ActiveItemEffect } from './ActiveItemEffect';
import { CampusItemSprite } from './CampusItemArt.tsx';

function PurchaseDialog({ item, shop, onClose }: { item: ShopItem; shop: ItemShopView; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const helpId = useId();
  useEffect(() => {
    const dialog = ref.current!;
    dialog.showModal();
    cancelRef.current?.focus();
    return () => dialog.close();
  }, []);
  const reason = shop.disabledReason(item);
  const submit = (event: SubmitEvent<HTMLFormElement>) => { event.preventDefault(); shop.purchase(item.id); };
  return (
    <dialog ref={ref} aria-labelledby={titleId} aria-describedby={helpId} onClose={onClose} className="item-purchase-dialog rpg-box p-4 bg-[#fffbeb] text-[#261608] font-pixel">
      <h2 id={titleId} className="text-sm font-black">구매 확인 · {item.name}</h2>
      <p className="mt-2 text-xs">{item.content}</p>
      <p id={helpId} className="my-3 text-xs font-bold">
        비용 {item.price.toLocaleString('ko-KR')}점 · 보유 기여도 {shop.balance?.toLocaleString('ko-KR') ?? '—'}점.
        {shop.balance !== null && shop.balance >= item.price && ` 구매 후 ${(shop.balance - item.price).toLocaleString('ko-KR')}점.`}
        {' '}기존 효과는 교체되며 클리커 레벨과 학교·학과 점수는 유지됩니다.
      </p>
      {reason && <p role="status" className="text-xs mb-2">{reason}</p>}
      {shop.error && <p role="alert" className="text-xs text-[#b91c1c] mb-2">{shop.error}</p>}
      {shop.reconcile && <button type="button" onClick={shop.reconcile} className="pixel-plaque plaque-yellow min-h-12 px-3 mb-2 text-xs font-bold cursor-pointer">구매 결과 서버 확인</button>}
      <div className="grid grid-cols-2 gap-2">
        <form method="dialog"><button ref={cancelRef} type="submit" className="pixel-plaque plaque-parchment min-h-12 w-full px-2 text-xs font-bold cursor-pointer">취소</button></form>
        <form method="post" action={`/api/v1/items/${item.id}/purchase`} onSubmit={submit} aria-busy={shop.pending}>
          <button type="submit" disabled={reason !== null} className="pixel-plaque plaque-green min-h-12 w-full px-2 text-xs font-bold cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed">
            {shop.pending ? '요청 중…' : `${item.price.toLocaleString('ko-KR')}점 구매 및 사용`}
          </button>
        </form>
      </div>
      {shop.pending && <p role="status" className="mt-2 text-xs">닫아도 전송된 구매는 취소되지 않습니다. 결과를 확인하는 동안 클릭·구매·강화를 잠시 멈춥니다.</p>}
    </dialog>
  );
}

/** API-only shelf. The existing mock shelf and image-change preview remain untouched in mock mode. */
export function ApiItemShop({ shop, onConfirmationChange }: { shop: ItemShopView; onConfirmationChange?: (confirming: boolean) => void }) {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const selected = shop.items.find((item) => item.id === selectedId) ?? null;
  const confirming = selected !== null;
  useLayoutEffect(() => {
    if (!confirming) return;
    onConfirmationChange?.(true);
    return () => onConfirmationChange?.(false);
  }, [confirming, onConfirmationChange]);
  useEffect(() => { if (shop.receipt) setSelectedId(null); }, [shop.receipt]);
  useEffect(() => {
    if (selectedId !== null && !shop.items.some((item) => item.id === selectedId)) setSelectedId(null);
  }, [selectedId, shop.items]);
  return (
    <section aria-label="서버 아이템 상점" className="flex flex-col gap-3">
      <ActiveItemEffect shop={shop} title="활성 효과 상세" />
      <h2 className="font-pixel text-xs font-black text-[#fef3c7]">아이템 진열대</h2>
      {shop.catalogNotice && <div className="rpg-box p-2 text-xs font-pixel text-[#8b5327]">
        <p role="status">{shop.catalogNotice}</p>
        <button type="button" onClick={shop.reloadCatalog} className="pixel-plaque plaque-parchment min-h-12 px-3 mt-2 font-bold cursor-pointer">아이템 목록 다시 조회</button>
      </div>}
      {shop.status && <p role="status" className="rpg-box p-2 text-xs font-pixel text-[#8b5327]">{shop.status}</p>}
      {!selected && shop.error && <p role="alert" className="rpg-box p-2 text-xs font-pixel text-[#b91c1c]">{shop.error}</p>}
      {!selected && shop.reconcile && <button type="button" onClick={shop.reconcile} className="pixel-plaque plaque-yellow min-h-12 px-3 text-xs font-pixel font-bold cursor-pointer">구매 결과 서버 확인</button>}
      <div className="grid grid-cols-2 gap-2">
        {shop.items.map((item) => {
          const unavailable = item.payType !== 'CONTRIBUTION' || item.type !== 'MULTIPLIER';
          const art = drinkArt(item);
          const Fallback = unavailable ? LockKeyhole : Package;
          return <button key={item.id} type="button" disabled={unavailable} onClick={() => setSelectedId(item.id)}
            aria-label={`${item.name} ${unavailable ? '구매 준비 중' : '구매 선택'}`}
            className="game-shelf-card text-left bg-[#fdf5df] border-3 border-[#261608] shadow-[2px_2px_0px_#261608] p-2 font-pixel cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed min-h-24">
            <span className="contents game-shelf-card-body">
              <span className="game-shelf-api-icon inline-grid place-items-center">
                {art ? <CampusItemSprite src={art} size={24} /> : <Fallback size={24} aria-hidden="true" className="text-[#8b5327]" />}
              </span>
              <span className="contents game-shelf-card-copy">
                <span className="game-shelf-card-name block text-xs font-black break-words">{item.name}</span>
                <span className="game-shelf-card-description block text-[10px] text-[#8b5327] my-1 break-words">{item.content}</span>
              </span>
            </span>
            <span className="contents game-shelf-api-details">
              <span className="block text-[10px] font-bold">{item.price.toLocaleString('ko-KR')}{item.payType === 'CREDIT' ? ' 크레딧' : '점'}</span>
              {item.durationSeconds !== null && <span className="block text-[10px]">지속 {item.durationSeconds}초</span>}
              {unavailable && <span className="block text-[10px]">{item.payType === 'CREDIT' ? '결제 준비 중' : '지원 준비 중'}</span>}
            </span>
          </button>;
        })}
      </div>
      {selected && <PurchaseDialog item={selected} shop={shop} onClose={() => setSelectedId(null)} />}
    </section>
  );
}
