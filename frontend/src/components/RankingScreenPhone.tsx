import React, { useState, useEffect } from 'react';
import { PhoneShell } from './PhoneShell';
import { Crown, Sparkles, ArrowUp, ArrowDown, Minus, RefreshCw, ChevronUp, MoveVertical } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { BottomNav, NavTab } from './BottomNav';
import { useMySchool } from '../hooks/useSchoolViews';
import { useDragScroll } from '../hooks/useDragScroll';
import { useRankingBoard, type RankingBoardRow, type RankingTab } from '../hooks/useRankingBoard';
import { RANK_TREND_TONE, RankingRowImage, RankingStatusNotice, rankTrend, rankingCaption } from './RankingParts';

interface RankingScreenPhoneProps {
  currentSchoolScore: number;
  onNavigateTab: (tab: NavTab) => void;
}

export const RankingScreenPhone: React.FC<RankingScreenPhoneProps> = ({
  currentSchoolScore,
  onNavigateTab,
}) => {
  const [activeRankingTab, setActiveRankingTab] = useState<RankingTab>('school');
  const [refreshCountdown, setRefreshCountdown] = useState<number>(5);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);

  // 5-second auto refresh loop
  useEffect(() => {
    const timer = setInterval(() => {
      setRefreshCountdown((prev) => {
        if (prev <= 1) {
          setIsRefreshing(true);
          setTimeout(() => setIsRefreshing(false), 300);
          return 5;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  const mySchool = useMySchool();
  // Mock rows or server pages, by mode (SCRUM-161). The mock keeps its pretend 350 ms load-more delay.
  const board = useRankingBoard(activeRankingTab, currentSchoolScore, { mockLoadDelayMs: 350 });
  const totalCount = board.totalCount;
  const [captionScope, captionOrder] = rankingCaption(activeRankingTab, board, mySchool.shortName);

  // Hook for drag and touch scrolling with momentum: reaching the bottom loads the next page
  const { containerRef, isDragging, scrollTop, scrollToTop, dragHandlers } =
    useDragScroll({
      onScrollBottom: board.loadMore,
      bottomThreshold: 120,
    });

  // Top 3 Podium (a short list can leave places empty)
  const top1: RankingBoardRow | undefined = board.rows[0];
  const top2: RankingBoardRow | undefined = board.rows[1];
  const top3: RankingBoardRow | undefined = board.rows[2];

  // Ranks 4 and below, as loaded
  const listRows = board.rows.slice(3);

  // My school/item: also shown when its page is not loaded yet
  const myItem = board.mine;

  // Sticky top 1st place bar appears when podium is scrolled past (scrollTop > 170px)
  const showStickyTop1Summary = activeRankingTab === 'school' && scrollTop > 175 && !!top1;

  const renderBadge = (item: RankingBoardRow, size: 'sm' | 'md' | 'lg' = 'sm') => {
    const sizeClasses =
      size === 'lg'
        ? 'w-10 h-10 text-sm font-black'
        : size === 'md'
        ? 'w-8 h-8 text-xs font-bold'
        : 'w-6 h-6 text-[10px] font-bold';

    return (
      <div
        className={`${sizeClasses} border-2 border-[#261608] shadow-[1px_1px_0px_#261608] flex items-center justify-center text-white shrink-0 font-pixel`}
        style={{ backgroundColor: item.badgeColor }}
      >
        <RankingRowImage row={item} className="w-full h-full drop-shadow-[1px_1px_0px_#261608]" />
      </div>
    );
  };

  const renderChange = (change: string | null) => {
    // Null: nothing to compare against 00:00 KST (new member, or no copy for today), so no badge.
    if (change === null) return null;
    const trend = rankTrend(change);
    return (
      <span
        className={`text-[8px] font-mono px-1 py-0.2 border border-[#261608] flex items-center gap-0.5 font-bold ${RANK_TREND_TONE[trend]}`}
      >
        {trend === 'up' ? <ArrowUp className="w-2.5 h-2.5" /> : trend === 'down' ? <ArrowDown className="w-2.5 h-2.5" /> : <Minus className="w-2.5 h-2.5" />}
        {change.replace(/[▲▼]/g, '') || '-'}
      </span>
    );
  };

  const renderPodium = (item: RankingBoardRow | undefined, place: 1 | 2 | 3) => {
    if (!item) return <div className="flex flex-col items-center" aria-hidden="true" />;
    const first = place === 1;
    const regionTone = place === 1 ? 'text-[#fef9c3]' : place === 2 ? 'text-[#e0f2fe]' : 'text-[#dcfce7]';
    return (
      <div className={`flex flex-col items-center ${first ? '-mt-2.5' : ''}`}>
        {first && (
          <div className="animate-bounce flex flex-col items-center mb-0.5">
            <Crown className="w-5 h-5 text-[#facc15] fill-[#facc15] drop-shadow-[1px_1px_0px_#261608]" />
          </div>
        )}
        <div className="flex flex-col items-center mb-1">
          {renderBadge(item, first ? 'lg' : 'md')}
          <span className={`font-pixel drop-shadow-[1px_1px_0px_#261608] text-center truncate mt-0.5 ${
            first ? 'text-xs font-black text-[#fef08a] max-w-[100px]' : 'text-[11px] font-bold text-white max-w-[85px]'
          }`}>
            {item.name}
          </span>
          <span className={`text-[8px] font-pixel drop-shadow-[1px_1px_0px_#261608] ${regionTone}`}>
            {item.subtitle}
          </span>
          <span className={`font-press-start text-[#fef08a] drop-shadow-[1px_1px_0px_#261608] mt-0.5 ${first ? 'text-[9px] font-black' : 'text-[8px]'}`}>
            {item.score.toLocaleString('ko-KR')}
          </span>
        </div>

        {/* Podium Block: Gold (tallest), Silver, Bronze. The block shows the server's rank (ties share one). */}
        {place === 1 ? (
          <div className="w-full h-20 bg-[#facc15] border-3 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col items-center justify-center relative">
            <div className="w-6 h-6 bg-[#ca8a04] border-2 border-[#261608] flex items-center justify-center font-press-start text-xs font-black text-[#fef08a] shadow-[1px_1px_0px_#261608]">
              {item.rank}
            </div>
            <span className="font-pixel text-[9px] text-[#261608] font-black mt-0.5">CHAMPION</span>
          </div>
        ) : place === 2 ? (
          <div className="w-full h-15 bg-[#cbd5e1] border-3 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col items-center justify-center relative">
            <div className="w-5 h-5 bg-[#94a3b8] border-2 border-[#261608] flex items-center justify-center font-press-start text-[10px] font-black text-white shadow-[1px_1px_0px_#261608]">
              {item.rank}
            </div>
            <span className="font-pixel text-[8px] text-[#261608] font-bold mt-0.5">SILVER</span>
          </div>
        ) : (
          <div className="w-full h-12 bg-[#d97706] border-3 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col items-center justify-center relative">
            <div className="w-5 h-5 bg-[#92400e] border-2 border-[#261608] flex items-center justify-center font-press-start text-[10px] font-black text-[#fde68a] shadow-[1px_1px_0px_#261608]">
              {item.rank}
            </div>
            <span className="font-pixel text-[8px] text-white font-bold mt-0.5">BRONZE</span>
          </div>
        )}
      </div>
    );
  };

  return (
    <PhoneShell>
      {/* 2. Screen Header Bar: Title & "5초마다 갱신" */}
      <div className="w-full bg-[#fdf5df] border-b-2 border-[#261608] px-3 py-1.5 flex items-center justify-between shrink-0 z-30">
        <div className="flex items-center gap-1.5">
          <span className="font-pixel text-xs font-bold text-[#ffffff] bg-[#e11d48] border border-[#261608] px-1 shadow-[1px_1px_0px_#261608]">
            대대대
          </span>
          <h1 className="font-pixel text-sm font-black text-[#261608]">
            전국 랭킹 대항전
          </h1>
        </div>

        <div className="flex items-center gap-1 text-[10px] font-pixel text-[#8b5327]">
          <RefreshCw
            className={`w-3 h-3 text-[#22c55e] ${isRefreshing || board.refreshing ? 'animate-spin' : ''}`}
          />
          <span className="font-mono font-bold text-[#261608]">5초마다 갱신</span>
          <span className="text-[9px] font-mono text-[#e11d48] bg-[#fef08a] border border-[#261608] px-1 py-0.2">
            {refreshCountdown}s
          </span>
        </div>
      </div>

      <p className="px-2 py-1 font-pixel text-[9px] text-[#8b5327] shrink-0">
        {captionScope}<br />
        {captionOrder}
      </p>
      {/* 3. Sticky Tab Switcher */}
      <div className="w-full px-2 pt-1.5 bg-[#faecc7]/50 shrink-0 z-30">
        <div className="grid grid-cols-3 gap-1 p-1 rpg-box bg-[#fdf5df]">
          <button
            onClick={() => {
              if (isDragging) return;
              setActiveRankingTab('school');
              soundManager.playTabSound();
            }}
            className={`pixel-plaque py-1.5 px-1 text-center font-pixel text-xs font-bold cursor-pointer transition-transform ${
              activeRankingTab === 'school' ? 'plaque-yellow' : 'plaque-parchment text-[#8b5327]'
            }`}
          >
            학교 순위
          </button>
          <button
            onClick={() => {
              if (isDragging) return;
              setActiveRankingTab('dept');
              soundManager.playTabSound();
            }}
            className={`pixel-plaque py-1.5 px-1 text-center font-pixel text-xs font-bold cursor-pointer transition-transform ${
              activeRankingTab === 'dept' ? 'plaque-yellow' : 'plaque-parchment text-[#8b5327]'
            }`}
          >
            교내 학과
          </button>
          <button
            onClick={() => {
              if (isDragging) return;
              setActiveRankingTab('personal');
              soundManager.playTabSound();
            }}
            className={`pixel-plaque py-1.5 px-1 text-center font-pixel text-xs font-bold cursor-pointer transition-transform ${
              activeRankingTab === 'personal' ? 'plaque-yellow' : 'plaque-parchment text-[#8b5327]'
            }`}
          >
            교내 개인
          </button>
        </div>
      </div>

      {/* 4. Sticky Compact 1st Summary: Slides down when TOP 3 Podium scrolls out */}
      {showStickyTop1Summary && top1 && (
        <div className="w-full px-2 py-1 bg-[#faecc7]/95 border-b border-[#261608]/40 shrink-0 z-20 animate-fadeIn">
          <div className="rpg-box px-2 py-1 bg-[#fef08a] border border-[#261608] flex items-center justify-between shadow-[1px_1px_0px_#261608]">
            <div className="flex items-center gap-1.5 min-w-0">
              <span className="text-xs">👑</span>
              <span className="font-press-start text-[9px] text-[#b45309] font-black">
                1
              </span>
              <div className="w-4.5 h-4.5 bg-[#1d4ed8] border border-[#261608] flex items-center justify-center font-pixel text-[8px] text-white font-bold shrink-0">
                <RankingRowImage row={top1} className="w-full h-full" />
              </div>
              <span className="font-pixel text-[11px] font-black text-[#261608] truncate">
                {top1.name}
              </span>
              <span className="text-[8px] font-pixel text-[#8b5327] hidden sm:inline">
                ({top1.subtitle})
              </span>
            </div>

            <div className="flex items-center gap-1 shrink-0">
              <span className="text-[9px] font-pixel text-[#8b5327] font-bold">현재 1위</span>
              <span className="font-press-start text-[9px] font-black text-[#261608]">
                {top1.score.toLocaleString('ko-KR')}
              </span>
            </div>
          </div>
        </div>
      )}

      {/* 5. Mobile Gesture Instruction Banner (Subtle, dispatches touch intent) */}
      <div className="w-full px-2 py-0.5 bg-[#faecc7]/40 border-b border-[#261608]/20 flex items-center justify-between text-[9px] font-pixel text-[#8b5327] shrink-0">
        <span className="flex items-center gap-1">
          <MoveVertical className="w-2.5 h-2.5 text-[#d97706]" />
          <span>마우스 드래그 및 터치 스와이프로 스크롤</span>
        </span>
        <span className="font-bold text-[#b45309]">
          {activeRankingTab === 'school' ? `${board.rows.length} / ${totalCount}개교` : `${totalCount}개 순위`}
        </span>
      </div>

      {/* ========================================================= */}
      {/* 6. MAIN INTEGRATED DRAG-SCROLL VIEWPORT                    */}
      {/* ========================================================= */}
      <div
        ref={containerRef}
        {...dragHandlers}
        className={`flex-1 overflow-y-auto px-2 pt-1 pb-1 bg-[#faecc7]/30 relative scrollbar-none overscroll-contain select-none transition-colors ${
          isDragging ? 'cursor-grabbing' : 'cursor-grab'
        }`}
        style={{
          WebkitOverflowScrolling: 'touch',
          touchAction: 'pan-y',
        }}
      >
        {board.status !== 'ready' ? (
          <RankingStatusNotice board={board} />
        ) : (<>
        {/* ========================================================= */}
        {/* TOP 3 PODIUM ON A GRASSY STAGE (Scrolls with the feed)     */}
        {/* ========================================================= */}
        <div className="w-full border-3 border-[#261608] shadow-[3px_3px_0px_#261608] bg-[#38a134] overflow-hidden shrink-0 relative flex flex-col justify-end pt-3 pb-1 px-2 mb-2">
          {/* Sky Strip & Sun */}
          <div className="absolute inset-x-0 top-0 h-9 bg-[#38bdf8] border-b-2 border-[#261608] flex items-center justify-between px-3">
            <span className="font-pixel text-[9px] text-white/95 font-bold">
              👑 TOP 3 명예의 전당
            </span>
            <div className="w-3.5 h-3.5 bg-[#facc15] border border-[#261608] shadow-[1px_1px_0px_#261608]" />
          </div>

          <span className="absolute bottom-12 left-2 text-[10px] pointer-events-none">🌼</span>
          <span className="absolute bottom-14 right-2 text-[10px] pointer-events-none">🌸</span>

          {/* 3 Podium Pillars: [2nd] [1st] [3rd] */}
          <div className="grid grid-cols-3 gap-1.5 items-end pt-7 relative z-10">
            {renderPodium(top2, 2)}
            {renderPodium(top1, 1)}
            {renderPodium(top3, 3)}
          </div>
        </div>

        {/* ========================================================= */}
        {/* LIST ROWS 4 TO N                                          */}
        {/* ========================================================= */}
        <div className="flex items-center justify-between text-[10px] font-pixel text-[#8b5327] px-1 mb-1 font-bold">
          <span>순위 및 소속</span>
          <span>{board.isSample ? '누적 점수 · 변동' : '누적 점수'}</span>
        </div>

        <div className="flex flex-col gap-1.5 pb-2">
          {listRows.map((row) => {
            const isMy = row.isMine;

            return (
              <div
                key={row.key}
                className={`rpg-box p-1.5 flex items-center justify-between transition-colors shrink-0 ${
                  isMy
                    ? 'bg-[#fefce8] border-3 border-[#facc15] shadow-[0_0_8px_rgba(250,204,21,0.7)]'
                    : 'bg-[#fdf5df] border-2 hover:bg-[#fffbeb]'
                }`}
              >
                {/* Left: Rank, Badge, Name & Subtitle */}
                <div className="flex items-center gap-2 min-w-0">
                  <span
                    className={`font-press-start text-xs font-black w-6 text-center shrink-0 ${
                      isMy ? 'text-[#b45309]' : 'text-[#261608]'
                    }`}
                  >
                    {row.rank}
                  </span>

                  {renderBadge(row, 'sm')}

                  <div className="min-w-0">
                    <div className="flex items-center gap-1.5">
                      <span className="font-pixel text-xs font-bold text-[#261608] truncate">
                        {row.name}
                      </span>
                      {isMy && (
                        <span className="font-pixel text-[8px] text-[#261608] bg-[#facc15] border border-[#261608] px-1 font-black shrink-0">
                          내 소속
                        </span>
                      )}
                    </div>
                    <p className="text-[9px] text-[#8b5327] font-pixel truncate leading-none mt-0.5">
                      {row.subtitle}
                    </p>
                  </div>
                </div>

                {/* Right: Score & Change Indicator */}
                <div className="flex items-center gap-1.5 shrink-0">
                  <span className="font-press-start text-[9px] text-[#261608] font-bold">
                    {row.score.toLocaleString('ko-KR')}
                  </span>
                  {renderChange(row.change)}
                </div>
              </div>
            );
          })}

          {/* ========================================================= */}
          {/* INFINITE SCROLL LOADING ROW                                */}
          {/* ========================================================= */}
          {(activeRankingTab === 'school' || board.hasMore || board.loadingMore) && (
            <div className="mt-1">
              {board.loadingMore ? (
                <div role="status" className="rpg-box p-2.5 bg-[#fdf5df] border-2 border-dashed border-[#8b5327] flex items-center justify-center gap-2">
                  <div className="flex items-center gap-1">
                    <span className="w-1.5 h-1.5 bg-[#d97706] border border-[#261608] animate-pixel-blink" />
                    <span
                      className="w-1.5 h-1.5 bg-[#d97706] border border-[#261608] animate-pixel-blink"
                      style={{ animationDelay: '250ms' }}
                    />
                    <span
                      className="w-1.5 h-1.5 bg-[#d97706] border border-[#261608] animate-pixel-blink"
                      style={{ animationDelay: '500ms' }}
                    />
                  </div>
                  <span className="font-pixel text-[10px] font-bold text-[#8b5327]">
                    순위 데이터를 더 불러오는 중… ({board.rows.length} / {totalCount})
                  </span>
                </div>
              ) : board.hasMore ? (
                <button
                  onClick={() => {
                    if (!isDragging) board.loadMore();
                  }}
                  className="w-full py-2 bg-[#faecc7]/60 hover:bg-[#faecc7] border-2 border-dashed border-[#8b5327]/60 text-center font-pixel text-[10px] text-[#8b5327] cursor-pointer active:translate-y-0.5 transition-all"
                >
                  <span>아래로 스크롤하여 {board.isSample ? '10개교 ' : ''}더 보기 ({board.rows.length} / {totalCount})</span>
                </button>
              ) : (
                <div className="py-2.5 text-center font-pixel text-[10px] text-[#8b5327] border-t border-[#261608]/20">
                  <span>🏁 {activeRankingTab === 'school' ? `전국 ${totalCount}개 모든 대학교` : `${totalCount}개 전체`} 순위를 확인했습니다!</span>
                </div>
              )}
            </div>
          )}
        </div>
        </>)}
      </div>

      {/* ========================================================= */}
      {/* 7. FLOATING "▲ 맨 위로" PIXEL BUTTON                        */}
      {/* ========================================================= */}
      {scrollTop > 220 && (
        <button
          onClick={() => {
            soundManager.playTabSound();
            scrollToTop();
          }}
          className="absolute right-3 bottom-20 z-30 bg-[#facc15] hover:bg-[#fde047] text-[#261608] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] px-2.5 py-1.5 flex items-center gap-1 cursor-pointer font-pixel text-[10px] font-black active:translate-y-0.5 transition-transform animate-fadeIn"
          title="최상단 포디움으로 이동"
        >
          <ChevronUp className="w-3.5 h-3.5 text-[#261608]" />
          <span>▲ 맨 위로</span>
        </button>
      )}

      {/* ========================================================= */}
      {/* 8. SMART PINNED "MY SCHOOL" BAR                           */}
      {/* Shown dynamically whenever row 7 scrolled away from view  */}
      {/* ========================================================= */}
      {board.status === 'ready' && myItem && (
        <div className="w-full shrink-0 px-2 pt-1 pb-1 bg-[#fdf5df] border-t-2 border-[#261608] z-20 animate-fadeIn">
          <div className="text-[9px] font-pixel text-[#8b5327] font-bold px-1 mb-0.5 flex items-center justify-between">
            <span className="flex items-center gap-1 text-[#261608]">
              <Sparkles className="w-3 h-3 text-[#facc15]" /> 내 소속 실시간 순위
            </span>
            <span className="text-[#22c55e] text-[8px]">{board.isSample ? '실시간 반영 중' : '5초마다 갱신'}</span>
          </div>

          <div
            className="p-1.5 border-3 border-[#facc15] bg-[#fef08a] shadow-[0_0_0_2px_#261608,2px_2px_0px_#261608] flex items-center justify-between transition-all"
            style={{
              boxShadow: '0 0 0 2px #261608, 2px 2px 0px #261608, 0 0 8px rgba(250, 204, 21, 0.85)',
            }}
          >
            <div className="flex items-center gap-2 min-w-0">
              <span className="font-press-start text-xs font-black text-[#b45309] w-6 text-center shrink-0">
                {myItem.rank}
              </span>

              {renderBadge(myItem, 'sm')}

              <div className="min-w-0">
                <div className="flex items-center gap-1">
                  <span className="font-pixel text-xs font-black text-[#261608] truncate">
                    {myItem.name}
                  </span>
                  <span className="font-pixel text-[8px] text-[#261608] bg-[#facc15] border border-[#261608] px-1 font-black shrink-0">
                    내 소속
                  </span>
                </div>
                <span className="text-[8px] text-[#8b5327] font-pixel truncate">
                  {myItem.subtitle}
                </span>
              </div>
            </div>

            <div className="flex items-center gap-1.5 shrink-0">
              <span className="font-press-start text-[9px] text-[#261608] font-black">
                {myItem.score.toLocaleString('ko-KR')}
              </span>
              {myItem.change !== null && (
                <span data-testid="my-rank-change" data-trend={rankTrend(myItem.change)}
                  className={`text-[8px] font-mono px-1 py-0.2 border border-[#261608] flex items-center gap-0.5 font-bold ${RANK_TREND_TONE[rankTrend(myItem.change)]}`}>
                  {myItem.change}
                </span>
              )}
            </div>
          </div>
        </div>
      )}

      {/* 9. Bottom Navigation Bar with '순위' active */}
      <div className="w-full px-2 pb-2 bg-[#fdf5df] border-t-2 border-[#261608] shrink-0">
        <BottomNav
          activeTab="rankings"
          onChangeTab={(tab) => {
            soundManager.playTabSound();
            onNavigateTab(tab);
          }}
          hasShopUpgrade={false}
          hasRankChange={false}
        />
      </div>
    </PhoneShell>
  );
};
