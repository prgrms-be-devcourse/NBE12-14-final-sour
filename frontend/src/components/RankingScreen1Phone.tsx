import React, { useState, useEffect } from 'react';
import { PhoneShell } from './PhoneShell';
import { Crown, ArrowUp, ArrowDown, Minus, RefreshCw } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { BottomNav, NavTab } from './BottomNav';
import { getSchoolRankings, type RankingRow } from '../data/mockRankings';
import type { SchoolId } from '../data/schoolIds';
import { useMockGame } from '../hooks/useMockGame';
import { SharedImage } from './SharedImage.tsx';
import { RANK_TREND_TONE, rankTrend } from './RankingParts';

interface RankingScreen1PhoneProps {
  onNavigateTab?: (tab: NavTab) => void;
  onScrollDown?: () => void;
}

export const RankingScreen1Phone: React.FC<RankingScreen1PhoneProps> = ({
  onNavigateTab = () => {},
  onScrollDown = () => {},
}) => {
  const [refreshCountdown, setRefreshCountdown] = useState<number>(5);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);

  // 5-second countdown timer
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

  const game = useMockGame();
  const rankings = getSchoolRankings(game.state.schoolScore, game.player.schoolId);
  const [top1, top2, top3] = rankings;
  const rows4to7 = rankings.slice(3, 7);
  const row8CutOff = rankings[7];

  const renderBadge = (item: RankingRow<SchoolId>, size: 'sm' | 'md' | 'lg' = 'sm') => {
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
        <SharedImage target={{ type: 'school', id: item.id }} name={item.name} fallback={item.firstSyllable} className="w-full h-full drop-shadow-[1px_1px_0px_#261608]" />
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
            className={`w-3 h-3 text-[#22c55e] ${isRefreshing ? 'animate-spin' : ''}`}
          />
          <span className="font-mono font-bold text-[#261608]">5초마다 갱신</span>
          <span className="text-[9px] font-mono text-[#e11d48] bg-[#fef08a] border border-[#261608] px-1 py-0.2">
            {refreshCountdown}s
          </span>
        </div>
      </div>

      {/* 3. Top Tab Switcher (학교 순위 active) */}
      <div className="w-full px-2 pt-1.5 bg-[#faecc7]/50 shrink-0">
        <div className="grid grid-cols-3 gap-1 p-1 rpg-box bg-[#fdf5df]">
          <button className="pixel-plaque py-1.5 px-1 text-center font-pixel text-xs font-bold cursor-pointer plaque-yellow">
            학교 순위
          </button>
          <button className="pixel-plaque py-1.5 px-1 text-center font-pixel text-xs font-bold cursor-pointer plaque-parchment text-[#8b5327]">
            교내 학과
          </button>
          <button className="pixel-plaque py-1.5 px-1 text-center font-pixel text-xs font-bold cursor-pointer plaque-parchment text-[#8b5327]">
            교내 개인
          </button>
        </div>
      </div>

      {/* 4. Main Scrollable List Area (Top of List) */}
      <div className="flex-1 flex flex-col justify-between overflow-hidden px-2 pt-1.5 pb-0 bg-[#faecc7]/30 relative">
        {/* ========================================================= */}
        {/* TOP 3 PODIUM ON A GRASSY STAGE                            */}
        {/* ========================================================= */}
        <div className="w-full border-3 border-[#261608] shadow-[3px_3px_0px_#261608] bg-[#38a134] overflow-hidden shrink-0 relative flex flex-col justify-end pt-3 pb-1 px-2">
          {/* Sky Strip & Sun */}
          <div className="absolute inset-x-0 top-0 h-9 bg-[#38bdf8] border-b-2 border-[#261608] flex items-center justify-between px-3">
            <span className="font-pixel text-[9px] text-white/95 font-bold">
              👑 TOP 3 명예의 전당
            </span>
            <div className="w-3.5 h-3.5 bg-[#facc15] border border-[#261608] shadow-[1px_1px_0px_#261608]" />
          </div>

          <span className="absolute bottom-12 left-2 text-[10px] pointer-events-none">🌼</span>
          <span className="absolute bottom-14 right-2 text-[10px] pointer-events-none">🌸</span>

          {/* 3 Podium Pillars: [2nd 부산대] [1st KAIST] [3rd 전남대] */}
          <div className="grid grid-cols-3 gap-1.5 items-end pt-7 relative z-10">
            {/* 2nd Place: 부산대 (Left) */}
            <div className="flex flex-col items-center">
              <div className="flex flex-col items-center mb-1">
                {renderBadge(top2, 'md')}
                <span className="font-pixel text-[11px] font-bold text-white drop-shadow-[1px_1px_0px_#261608] text-center truncate max-w-[85px] mt-0.5">
                  {top2.name}
                </span>
                <span className="text-[8px] font-pixel text-[#e0f2fe] drop-shadow-[1px_1px_0px_#261608]">
                  {top2.region}
                </span>
                <span className="font-press-start text-[8px] text-[#fef08a] drop-shadow-[1px_1px_0px_#261608] mt-0.5">
                  {top2.score.toLocaleString('ko-KR')}
                </span>
              </div>

              {/* Podium Block: Silver */}
              <div className="w-full h-15 bg-[#cbd5e1] border-3 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col items-center justify-center relative">
                <div className="w-5 h-5 bg-[#94a3b8] border-2 border-[#261608] flex items-center justify-center font-press-start text-[10px] font-black text-white shadow-[1px_1px_0px_#261608]">
                  2
                </div>
                <span className="font-pixel text-[8px] text-[#261608] font-bold mt-0.5">
                  SILVER
                </span>
              </div>
            </div>

            {/* 1st Place: KAIST (Center - Tallest) */}
            <div className="flex flex-col items-center -mt-2.5">
              <div className="animate-bounce flex flex-col items-center mb-0.5">
                <Crown className="w-5 h-5 text-[#facc15] fill-[#facc15] drop-shadow-[1px_1px_0px_#261608]" />
              </div>

              <div className="flex flex-col items-center mb-1">
                {renderBadge(top1, 'lg')}
                <span className="font-pixel text-xs font-black text-[#fef08a] drop-shadow-[1px_1px_0px_#261608] text-center truncate max-w-[100px] mt-0.5">
                  {top1.name}
                </span>
                <span className="text-[8px] font-pixel text-[#fef9c3] drop-shadow-[1px_1px_0px_#261608]">
                  {top1.region}
                </span>
                <span className="font-press-start text-[9px] text-[#fef08a] drop-shadow-[1px_1px_0px_#261608] font-black mt-0.5">
                  {top1.score.toLocaleString('ko-KR')}
                </span>
              </div>

              {/* Podium Block: Gold (Tallest) */}
              <div className="w-full h-20 bg-[#facc15] border-3 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col items-center justify-center relative">
                <div className="w-6 h-6 bg-[#ca8a04] border-2 border-[#261608] flex items-center justify-center font-press-start text-xs font-black text-[#fef08a] shadow-[1px_1px_0px_#261608]">
                  1
                </div>
                <span className="font-pixel text-[9px] text-[#261608] font-black mt-0.5">
                  CHAMPION
                </span>
              </div>
            </div>

            {/* 3rd Place: 전남대 (Right) */}
            <div className="flex flex-col items-center">
              <div className="flex flex-col items-center mb-1">
                {renderBadge(top3, 'md')}
                <span className="font-pixel text-[11px] font-bold text-white drop-shadow-[1px_1px_0px_#261608] text-center truncate max-w-[85px] mt-0.5">
                  {top3.name}
                </span>
                <span className="text-[8px] font-pixel text-[#dcfce7] drop-shadow-[1px_1px_0px_#261608]">
                  {top3.region}
                </span>
                <span className="font-press-start text-[8px] text-[#fef08a] drop-shadow-[1px_1px_0px_#261608] mt-0.5">
                  {top3.score.toLocaleString('ko-KR')}
                </span>
              </div>

              {/* Podium Block: Bronze */}
              <div className="w-full h-12 bg-[#d97706] border-3 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col items-center justify-center relative">
                <div className="w-5 h-5 bg-[#92400e] border-2 border-[#261608] flex items-center justify-center font-press-start text-[10px] font-black text-[#fde68a] shadow-[1px_1px_0px_#261608]">
                  3
                </div>
                <span className="font-pixel text-[8px] text-white font-bold mt-0.5">
                  BRONZE
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* ========================================================= */}
        {/* LIST ROWS 4 TO 7 (AND ROW 8 CUT OFF AT BOTTOM)            */}
        {/* ========================================================= */}
        <div className="w-full flex-1 mt-1.5 overflow-hidden flex flex-col justify-start">
          {/* Subheader */}
          <div className="flex items-center justify-between text-[10px] font-pixel text-[#8b5327] px-1 mb-1 font-bold shrink-0">
            <span>순위 및 소속</span>
            <span>누적 점수 · 변동</span>
          </div>

          {/* Rows List continuing smoothly to the bottom edge */}
          <div className="flex flex-col gap-1.5 overflow-hidden">
            {rows4to7.map((row) => {
              const isHanyang = row.isMySchool;

              return (
                <div
                  key={row.rank}
                  className={`rpg-box p-1.5 flex items-center justify-between transition-all shrink-0 ${
                    isHanyang
                      ? 'bg-[#fefce8] border-3 border-[#facc15] shadow-[0_0_8px_rgba(250,204,21,0.7)]'
                      : 'bg-[#fdf5df] border-2 hover:bg-[#fffbeb]'
                  }`}
                >
                  {/* Left: Rank, Badge, School Name & Region */}
                  <div className="flex items-center gap-2 min-w-0">
                    <span
                      className={`font-press-start text-xs font-black w-6 text-center shrink-0 ${
                        isHanyang ? 'text-[#b45309]' : 'text-[#261608]'
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
                        {isHanyang && (
                          <span className="font-pixel text-[8px] text-[#261608] bg-[#facc15] border border-[#261608] px-1 font-black shrink-0">
                            내 학교
                          </span>
                        )}
                      </div>
                      <p className="text-[9px] text-[#8b5327] font-pixel truncate leading-none mt-0.5">
                        {row.region}
                      </p>
                    </div>
                  </div>

                  {/* Right: Score & Change Indicator */}
                  <div className="flex items-center gap-1.5 shrink-0">
                    <span className="font-press-start text-[9px] text-[#261608] font-bold">
                      {row.score.toLocaleString('ko-KR')}
                    </span>
                    <span
                      className={`text-[8px] font-mono px-1 py-0.2 border border-[#261608] flex items-center gap-0.5 font-bold ${RANK_TREND_TONE[rankTrend(row.change)]}`}
                    >
                      {row.change.startsWith('▲') ? (
                        <ArrowUp className="w-2.5 h-2.5" />
                      ) : row.change.startsWith('▼') ? (
                        <ArrowDown className="w-2.5 h-2.5" />
                      ) : (
                        <Minus className="w-2.5 h-2.5" />
                      )}
                      {row.change.replace(/[▲▼]/g, '') || '-'}
                    </span>
                  </div>
                </div>
              );
            })}

            {/* Cut-off Row 8 (고려대학교) peeking at the bottom edge */}
            <div
              onClick={() => {
                soundManager.playTabSound();
                onScrollDown();
              }}
              title="아래로 스크롤하여 더 많은 순위 보기"
              className="rpg-box p-1.5 flex items-center justify-between bg-[#fdf5df]/80 border-2 border-[#261608] opacity-75 translate-y-1 cursor-pointer hover:opacity-100 transition-opacity shrink-0"
            >
              <div className="flex items-center gap-2 min-w-0">
                <span className="font-press-start text-xs font-black text-[#261608] w-6 text-center shrink-0">
                  {row8CutOff.rank}
                </span>
                {renderBadge(row8CutOff, 'sm')}
                <div className="min-w-0">
                  <span className="font-pixel text-xs font-bold text-[#261608] truncate">
                    {row8CutOff.name}
                  </span>
                  <p className="text-[9px] text-[#8b5327] font-pixel truncate leading-none mt-0.5">
                    {row8CutOff.region}
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-1.5 shrink-0">
                <span className="font-press-start text-[9px] text-[#261608] font-bold">
                  {row8CutOff.score.toLocaleString('ko-KR')}
                </span>
                <span className={`text-[8px] font-mono px-1 py-0.2 border border-[#261608] flex items-center gap-0.5 font-bold ${RANK_TREND_TONE[rankTrend(row8CutOff.change)]}`}>
                  {row8CutOff.change.startsWith('▲') ? (
                    <ArrowUp className="w-2.5 h-2.5" />
                  ) : row8CutOff.change.startsWith('▼') ? (
                    <ArrowDown className="w-2.5 h-2.5" />
                  ) : (
                    <Minus className="w-2.5 h-2.5" />
                  )}
                  {row8CutOff.change.replace(/[▲▼]/g, '') || '-'}
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* Note: Since 한양대 (7) is visible above, the pinned bottom bar is intentionally hidden as instructed */}
      </div>

      {/* 5. Bottom Navigation Bar with '순위' active */}
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
