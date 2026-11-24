import React, { useState, useEffect } from 'react';
import { PhoneShell } from './PhoneShell';
import { Crown, ArrowUp, ArrowDown, Minus, RefreshCw, ChevronUp, Sparkles } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { BottomNav, NavTab } from './BottomNav';
import { getSchoolRankings, type RankingRow } from '../data/mockRankings';
import type { SchoolId } from '../data/schoolIds';
import { useMockGame } from '../hooks/useMockGame';
import { SharedImage } from './SharedImage.tsx';
import { RANK_TREND_TONE, rankTrend } from './RankingParts';

interface RankingScreen2PhoneProps {
  onNavigateTab?: (tab: NavTab) => void;
  onScrollToTop?: () => void;
}

export const RankingScreen2Phone: React.FC<RankingScreen2PhoneProps> = ({
  onNavigateTab = () => {},
  onScrollToTop = () => {},
}) => {
  const [refreshCountdown, setRefreshCountdown] = useState<number>(5);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);

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
  const top1 = rankings[0];
  const rows8to20 = rankings.slice(7, 20);
  const mySchool = rankings.find((school) => school.isMySchool)!;

  const renderBadge = (item: RankingRow<SchoolId>, size: 'sm' | 'md' = 'sm') => {
    const sizeClasses =
      size === 'md' ? 'w-7 h-7 text-xs font-bold' : 'w-5.5 h-5.5 text-[9px] font-bold';

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
      {/* 2. Screen Header Bar: Title & "5초마다 갱신" (Sticky) */}
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

      {/* 3. Sticky Tab Switcher */}
      <div className="w-full px-2 pt-1.5 bg-[#faecc7]/50 shrink-0 z-20">
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

      {/* 4. Compact One-Line Summary under Tabs: "👑 1 KAIST 2,903,118" */}
      <div className="w-full px-2 py-1 bg-[#faecc7]/90 border-b border-[#261608]/40 shrink-0 z-20">
        <div className="rpg-box px-2 py-1 bg-[#fef08a] border border-[#261608] flex items-center justify-between shadow-[1px_1px_0px_#261608]">
          <div className="flex items-center gap-1.5 min-w-0">
            <span className="text-xs">👑</span>
            <span className="font-press-start text-[9px] text-[#b45309] font-black">
              1
            </span>
            <div className="w-4.5 h-4.5 bg-[#1d4ed8] border border-[#261608] flex items-center justify-center font-pixel text-[8px] text-white font-bold">
              <SharedImage target={{ type: 'school', id: top1.id }} name={top1.name} fallback={top1.firstSyllable} className="w-full h-full" />
            </div>
            <span className="font-pixel text-[11px] font-black text-[#261608] truncate">
              {top1.name}
            </span>
            <span className="text-[8px] font-pixel text-[#8b5327] hidden sm:inline">
              ({top1.region})
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

      {/* 5. Main Scrolled Down Content: List Rows 8 to 20 + Loading Row */}
      <div className="flex-1 flex flex-col justify-between overflow-hidden px-2 pt-1 pb-1 bg-[#faecc7]/30 relative">
        <div className="flex items-center justify-between text-[10px] font-pixel text-[#8b5327] px-1 mb-1 font-bold shrink-0">
          <span>순위 및 소속 (스크롤 중)</span>
          <span>누적 점수 · 변동</span>
        </div>

        {/* Scrollable list container showing rows 8 through 20 */}
        <div className="flex-1 overflow-y-auto pr-0.5 flex flex-col gap-1 pb-1">
          {rows8to20.map((row) => (
            <div
              key={row.rank}
              className="rpg-box p-1.5 flex items-center justify-between bg-[#fdf5df] border-2 hover:bg-[#fffbeb] transition-colors shrink-0"
            >
              {/* Left: Rank, Badge, School Name & Region */}
              <div className="flex items-center gap-2 min-w-0">
                <span className="font-press-start text-[11px] font-black text-[#261608] w-6 text-center shrink-0">
                  {row.rank}
                </span>

                {renderBadge(row, 'sm')}

                <div className="min-w-0">
                  <span className="font-pixel text-xs font-bold text-[#261608] truncate block">
                    {row.name}
                  </span>
                  <p className="text-[8px] text-[#8b5327] font-pixel truncate leading-none mt-0.5">
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
          ))}

          {/* Pixel Loading Row at the end of the list */}
          <div className="rpg-box p-2 bg-[#fdf5df] border-2 border-dashed border-[#8b5327] flex items-center justify-center gap-2 my-1 shrink-0">
            {/* Three Blinking Pixel Dots */}
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
              더 불러오는 중… (20 / 76)
            </span>
          </div>
        </div>

        {/* Small Round Floating Pixel Button "▲ 맨 위로" (Above pinned bar) */}
        <button
          onClick={() => {
            soundManager.playTabSound();
            onScrollToTop();
          }}
          className="absolute right-3 bottom-14 z-30 bg-[#facc15] hover:bg-[#fde047] text-[#261608] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] px-2.5 py-1 flex items-center gap-1 cursor-pointer font-pixel text-[9px] font-black active:translate-y-0.5 transition-transform"
        >
          <ChevronUp className="w-3 h-3 text-[#261608]" />
          <span>▲ 맨 위로</span>
        </button>

        {/* ========================================================= */}
        {/* PINNED "MY SCHOOL" BAR: VISIBLE (Since row 7 scrolled away) */}
        {/* "7 한양대학교 내 학교 1,333,872 ↑2"                       */}
        {/* ========================================================= */}
        <div className="w-full shrink-0 pt-1 z-20">
          <div className="text-[9px] font-pixel text-[#8b5327] font-bold px-1 mb-0.5 flex items-center justify-between">
            <span className="flex items-center gap-1 text-[#261608]">
              <Sparkles className="w-3 h-3 text-[#facc15]" /> 내 소속 실시간 순위
            </span>
            <span className="text-[#22c55e] text-[8px]">실시간 반영 중</span>
          </div>

          <div
            className="p-1.5 border-3 border-[#facc15] bg-[#fef08a] shadow-[0_0_0_2px_#261608,2px_2px_0px_#261608] flex items-center justify-between transition-all"
            style={{
              boxShadow: '0 0 0 2px #261608, 2px 2px 0px #261608, 0 0 8px rgba(250, 204, 21, 0.85)',
            }}
          >
            <div className="flex items-center gap-2 min-w-0">
              <span className="font-press-start text-xs font-black text-[#b45309] w-6 text-center shrink-0">
                {mySchool.rank}
              </span>

              {renderBadge(mySchool, 'sm')}

              <div className="min-w-0">
                <div className="flex items-center gap-1">
                  <span className="font-pixel text-xs font-black text-[#261608] truncate">
                    {mySchool.name}
                  </span>
                  <span className="font-pixel text-[8px] text-[#261608] bg-[#facc15] border border-[#261608] px-1 font-black shrink-0">
                    내 학교
                  </span>
                </div>
                <span className="text-[8px] text-[#8b5327] font-pixel truncate">
                  {mySchool.region}
                </span>
              </div>
            </div>

            <div className="flex items-center gap-1.5 shrink-0">
              <span className="font-press-start text-[9px] text-[#261608] font-black">
                {mySchool.score.toLocaleString('ko-KR')}
              </span>
              <span className={`text-[8px] font-mono px-1 py-0.2 border border-[#261608] flex items-center gap-0.5 font-bold ${RANK_TREND_TONE[rankTrend(mySchool.change)]}`}>
                {mySchool.change}
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* 6. Bottom Navigation Bar with '순위' active */}
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
