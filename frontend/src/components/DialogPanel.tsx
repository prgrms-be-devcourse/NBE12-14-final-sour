import React from 'react';
import { Award } from 'lucide-react';
import { useMockGame } from '../hooks/useMockGame';
import { useClicker } from '../hooks/useClicker';
import { useItemShop } from '../hooks/useItemShop';
import { ActiveItemEffect } from './ActiveItemEffect';
import { ClickerArt } from './CampusItemArt.tsx';

interface DialogPanelProps {
  myContribution: number;
  clickerLevel: number | null;
}

/** Contribution and clicker boxes. Shown in the campus stats sheet (T5); the main screen no longer has this panel. */
export const DialogPanel: React.FC<DialogPanelProps> = ({ myContribution, clickerLevel }) => {
  const { activeBuff, remainingSeconds } = useMockGame();
  const clicker = useClicker();
  const itemShop = useItemShop();
  const { nextClicker, normalPower } = clicker;
  const cost = clicker.upgradeCost;
  const progress = cost === null || cost === 0 ? 100 : Math.min(100, Math.floor(myContribution / cost * 100));
  const costLabel = cost === null ? '추가 강화 없음' : `강화 비용 ${cost.toLocaleString('ko-KR')}점`;
  const buffStatus = activeBuff ? `${activeBuff.name} · ${remainingSeconds}초 남음${activeBuff.singleUse ? ' · 다음 클릭 1회' : ''}` : '활성 버프 없음';

  return (
    <div className="w-full flex flex-col gap-2 mt-2">
      <div className="rpg-box p-2 flex items-center gap-2 shadow-[2px_2px_0px_#261608]">
        <Award aria-hidden="true" className="w-7 h-7 text-[#8b5327] shrink-0" />
        <div>
          <p className="font-pixel text-[10px] text-[#8b5327]">내 누적 기여 점수 · 구매/강화 시 차감</p>
          <p className="font-pixel text-xs font-bold text-[#261608]">
            보유 기여도 <span className="font-press-start text-xs bg-[#fef08a] px-1">{myContribution.toLocaleString('ko-KR')}</span>점
          </p>
        </div>
      </div>
      <div className="rpg-box p-2 flex flex-col gap-1.5 shadow-[2px_2px_0px_#261608]">
        <div className={`flex items-center justify-between font-pixel text-xs font-bold${clicker.api ? ' flex-wrap gap-1' : ''}`}>
          <span className={clicker.api ? 'flex items-center gap-1' : undefined}>
            {clicker.api ? <ClickerArt level={clickerLevel} /> : '🖋️ '}클리커 Lv.{clickerLevel ?? '—'}
          </span>
          <span className="bg-[#fef08a] px-1 border border-[#261608]">클릭당 {normalPower}점</span>
        </div>
        <div className="game-clicker-details font-pixel text-[10px] text-[#8b5327]">
          <p className="game-clicker-stat">
            기본 세기 <strong className="game-clicker-stat-num">{clicker.power}</strong>점
          </p>
          <span className="game-clicker-sep" aria-hidden="true"> · </span>
          <p className="game-clicker-stat">
            {clicker.api ? '서버 기준' : <>치명타 <strong className="game-clicker-stat-num">2</strong>배</>}
          </p>
          {!clicker.api && <>
            <span className="game-clicker-sep" aria-hidden="true"> · </span>
            <p className="game-clicker-note">강화는 상점에서</p>
          </>}
        </div>
        {!clicker.api && <>
          <div className="font-pixel text-[10px] text-[#8b5327] flex justify-between gap-1">
            <span>{nextClicker ? `다음 Lv.${nextClicker.level} · 클릭당 ${nextClicker.power}점` : '최고 단계 달성'}</span>
            <span>{costLabel}</span>
          </div>
          <div className="h-2.5 bg-[#faecc7] border-2 border-[#261608]" role="progressbar" aria-label="다음 강화 비용 대비 보유 기여도" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}>
            <div className="h-full bg-[#facc15]" style={{ width: `${progress}%` }} />
          </div>
        </>}
        <p className="font-pixel text-[10px] text-[#8b5327]" role="status">
          {clicker.api ? '클릭 세기는 서버 응답 기준입니다.' : buffStatus}
        </p>
      </div>
      {itemShop && <ActiveItemEffect shop={itemShop} />}
    </div>
  );
};
