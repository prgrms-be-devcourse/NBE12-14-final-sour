import { useEffect, useState } from 'react';
import { effectSecondsLeft } from '../api/items';
import type { ItemShopView } from '../hooks/useItemShop';

/** Only this leaf ticks. The whole game and its queries do not re-render once per second. */
export function ActiveItemEffect({ shop, title }: { shop: ItemShopView; title?: string }) {
  const [now, setNow] = useState(() => performance.now());
  useEffect(() => {
    setNow(performance.now());
    if (!shop.effect?.effect) return;
    const timer = window.setInterval(() => setNow(performance.now()), 1000);
    return () => window.clearInterval(timer);
  }, [shop.effect]);
  const seconds = effectSecondsLeft(shop.effect, now);
  const effect = shop.effect?.effect;
  return (
    <section aria-label={title ?? '서버 적용 효과'} className="rpg-box p-2 font-pixel text-xs text-[#8b5327] flex flex-col gap-1">
      {title && <h2 className="font-black text-[#261608]">{title}</h2>}
      {shop.effectNotice && <p role="status">{shop.effectNotice}</p>}
      {effect && seconds > 0 ? (
        <p>{effect.name ?? '아이템 효과'} · 서버 배율 ×{effect.multiplier} · <span role="timer" aria-live="off">{seconds}초 남음</span></p>
      ) : !shop.effectNotice && <p>{effect ? '효과 시간이 끝났습니다. 서버 상태를 다시 확인할 수 있습니다.' : '활성 효과 없음'}</p>}
      <p className="text-[10px]">최종 배율과 점수는 클릭 전송 시점에 서버가 확정합니다.</p>
      <button type="button" onClick={shop.reloadEffect} className="pixel-plaque plaque-parchment min-h-12 px-2 font-bold cursor-pointer">적용 효과 다시 조회</button>
    </section>
  );
}
