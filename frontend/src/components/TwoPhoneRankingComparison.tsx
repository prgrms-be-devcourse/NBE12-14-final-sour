import React, { useState } from 'react';
import { RankingScreen1Phone } from './RankingScreen1Phone';
import { RankingScreen2Phone } from './RankingScreen2Phone';
import { RankingScreenPhone } from './RankingScreenPhone';
import { NavTab } from './BottomNav';
import { Trophy, ArrowDown, Sparkles, Layers, Smartphone, MoveVertical, Touchpad } from 'lucide-react';
import { soundManager } from '../utils/sound';

interface TwoPhoneRankingComparisonProps {
  schoolScore?: number;
  onNavigateTab?: (tab: NavTab) => void;
}

export const TwoPhoneRankingComparison: React.FC<TwoPhoneRankingComparisonProps> = ({
  schoolScore = 1333872,
  onNavigateTab = () => {},
}) => {
  // Mode: 'unified' (Single interactive drag-scroll phone) | 'two_phone' (Side-by-side comparison)
  const [displayMode, setDisplayMode] = useState<'unified' | 'two_phone'>('unified');

  return (
    <div className="w-full flex flex-col items-center py-2 animate-fadeIn select-none">
      {/* Top Banner & Mode Toggle */}
      <div className="w-full max-w-5xl flex flex-col sm:flex-row items-center justify-between gap-3 mb-4 px-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="font-pixel text-[11px] text-white bg-[#d97706] border border-[#261608] px-2 py-0.5 shadow-[1px_1px_0px_#261608] font-bold">
            RANKINGS MOBILE UX
          </span>
          <span className="font-pixel text-xs text-[#8b5327] font-bold">
            {displayMode === 'unified'
              ? '포디움부터 76위까지 마우스 드래그 & 모바일 터치 관성 스크롤'
              : 'SCREEN 1 (상단 포디움) ➔ SCREEN 2 (스크롤 다운 & 무한 스크롤)'}
          </span>
        </div>

        {/* View Switcher: Single Drag-Scroll Phone vs 2-Phone Comparison */}
        <div className="flex items-center gap-1.5 p-1 rpg-box bg-[#fdf5df]">
          <button
            onClick={() => {
              setDisplayMode('unified');
              soundManager.playTabSound();
            }}
            className={`pixel-plaque px-2.5 py-1 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
              displayMode === 'unified' ? 'plaque-yellow' : 'plaque-parchment text-[#8b5327]'
            }`}
          >
            <Smartphone className="w-3.5 h-3.5 text-[#261608]" />
            <span>일체형 드래그 무한스크롤</span>
          </button>

          <button
            onClick={() => {
              setDisplayMode('two_phone');
              soundManager.playTabSound();
            }}
            className={`pixel-plaque px-2.5 py-1 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
              displayMode === 'two_phone' ? 'plaque-yellow' : 'plaque-parchment text-[#8b5327]'
            }`}
          >
            <Layers className="w-3.5 h-3.5 text-[#261608]" />
            <span>2-Phone 기획 비교</span>
          </button>
        </div>
      </div>

      {displayMode === 'unified' ? (
        /* ========================================================= */
        /* SINGLE INTEGRATED DRAG-SCROLL PHONE                       */
        /* ========================================================= */
        <div className="w-full flex flex-col items-center animate-fadeIn">
          {/* Interactive Guidance Box */}
          <div className="rpg-box px-4 py-2 bg-[#fefce8] border-2 border-[#261608] mb-3 flex items-center gap-3 max-w-[420px] text-center shadow-[2px_2px_0px_#261608]">
            <Touchpad className="w-5 h-5 text-[#b45309] shrink-0 animate-bounce" />
            <div className="flex flex-col items-start text-left">
              <span className="font-pixel text-xs font-bold text-[#261608]">
                손가락 터치 또는 마우스 클릭-드래그 지원
              </span>
              <span className="font-pixel text-[10px] text-[#8b5327]">
                화면을 위아래로 끌어당기면 관성 스크롤과 함께 10개교씩 추가 로딩됩니다.
              </span>
            </div>
          </div>

          <RankingScreenPhone
            currentSchoolScore={schoolScore}
            onNavigateTab={onNavigateTab}
          />
        </div>
      ) : (
        /* ========================================================= */
        /* 2-PHONE COMPARISON (SCREEN 1 & SCREEN 2)                  */
        /* ========================================================= */
        <div className="w-full max-w-5xl flex flex-col lg:flex-row items-center justify-center gap-4 lg:gap-6 animate-fadeIn">
          {/* PHONE 1: SCREEN 1 */}
          <div className="flex flex-col items-center">
            <div className="mb-2 flex items-center gap-2">
              <span className="w-5 h-5 bg-[#facc15] border-2 border-[#261608] flex items-center justify-center font-press-start text-[9px] text-[#261608] font-black shadow-[1px_1px_0px_#261608]">
                1
              </span>
              <div className="flex items-center gap-1.5">
                <Trophy className="w-4 h-4 text-[#d97706]" />
                <h2 className="font-pixel text-sm font-black text-[#261608]">
                  SCREEN 1 — 리스트 최상단 (포디움 & 4~7위)
                </h2>
              </div>
            </div>

            <p className="font-pixel text-[10px] text-[#8b5327] mb-2 text-center max-w-[380px]">
              전국 학교 샘플 · 점수/학교명 기준 정렬 · 내 학교는 현재 순위에 따라 강조 표시
            </p>

            <RankingScreen1Phone
              onNavigateTab={onNavigateTab}
              onScrollDown={() => {
                setDisplayMode('unified');
              }}
            />
          </div>

          {/* CENTER INTERACTION ARROW: "스크롤 ↓" */}
          <div className="flex lg:flex-col items-center justify-center gap-1.5 my-2 lg:my-0 shrink-0">
            <button
              onClick={() => {
                setDisplayMode('unified');
                soundManager.playTabSound();
              }}
              className="rpg-box p-2 bg-[#fef08a] hover:bg-[#fde047] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex lg:flex-col items-center gap-1 text-[#261608] animate-bounce cursor-pointer active:translate-y-0.5"
              title="일체형 드래그 스크롤로 체험하기"
            >
              <ArrowDown className="w-4 h-4 text-[#b45309]" />
              <div className="flex flex-col items-center text-center">
                <span className="font-pixel text-xs font-black text-[#261608] leading-tight">
                  스크롤 ↓
                </span>
                <span className="font-pixel text-[9px] text-[#8b5327] leading-none mt-0.5">
                  직접 체험하기
                </span>
              </div>
            </button>
          </div>

          {/* PHONE 2: SCREEN 2 */}
          <div className="flex flex-col items-center">
            <div className="mb-2 flex items-center gap-2">
              <span className="w-5 h-5 bg-[#facc15] border-2 border-[#261608] flex items-center justify-center font-press-start text-[9px] text-[#261608] font-black shadow-[1px_1px_0px_#261608]">
                2
              </span>
              <div className="flex items-center gap-1.5">
                <Layers className="w-4 h-4 text-[#0284c7]" />
                <h2 className="font-pixel text-sm font-black text-[#261608]">
                  SCREEN 2 — 스크롤 다운 (8~20위 & 고정바)
                </h2>
              </div>
            </div>

            <p className="font-pixel text-[10px] text-[#8b5327] mb-2 text-center max-w-[380px]">
              포디움 스크롤 아웃 · 상단 1위 콤팩트 요약 · 내 학교 고정바 노출 · 맨 위로 플로팅 버튼
            </p>

            <RankingScreen2Phone
              onNavigateTab={onNavigateTab}
              onScrollToTop={() => {
                setDisplayMode('unified');
              }}
            />
          </div>
        </div>
      )}
    </div>
  );
};
