import type { SchoolId } from '../data/schoolIds.ts';
import React from 'react';
import { ArrowLeft, Volume2, VolumeX } from 'lucide-react';
import { RANK_TREND_TONE, rankTrend } from './RankingParts';
import { soundManager } from '../utils/sound';
import { SharedImage } from './SharedImage.tsx';
import type { SchoolView } from '../data/schoolDirectory.ts';
import { MarqueeText } from './MarqueeText.tsx';

interface TopHUDProps {
  /** 표시할 학교명. 칸보다 길면 MarqueeText로 왕복 표시한다. */
  schoolName: string;
  /** The player's school (M4). */
  schoolId: SchoolId;
  schoolBadgeColor: string;
  /** Badge letter when there is no logo. Defaults to the first letter of the name. */
  schoolInitial?: string;
  /** Shown in the badge title, e.g. "서울 · 수도권". */
  schoolRegion: string;
  schoolScore: number;
  /** Null while the school has no server ranking (API visit, SCRUM-163). */
  rank: number | null;
  /** Null hides the change badge (signed out, not loaded, or nothing to compare against 00:00 KST). */
  rankDelta: string | null;
  campusLevel: number;
  soundEnabled: boolean;
  onToggleSound: () => void;
  onOpenRankings?: () => void;
  onOpenMap?: () => void;
  /**
   * 'visit' shows another school (V2, docs/261110-2005-visit_ui_jeju_map_plan.md §4-2): a "방문 중" tag instead of the
   * game tag, a plain level badge (Q3), a back-to-map button, and the visit-score / visit-rank test IDs.
   */
  mode?: 'mine' | 'visit';
  /** Visit mode: back to the map, left of the badge. */
  onBack?: () => void;
}

/** HUD school props for a school row (comparison screens use the player's school). */
export const schoolHudProps = (school: Pick<SchoolView, 'name' | 'id' | 'badgeColor' | 'firstSyllable' | 'city' | 'filterRegion'>) => ({
  schoolName: school.name,
  schoolId: school.id,
  schoolBadgeColor: school.badgeColor,
  schoolInitial: school.firstSyllable,
  schoolRegion: `${school.city} · ${school.filterRegion}`,
});

export const TopHUD: React.FC<TopHUDProps> = ({
  schoolName,
  schoolId,
  schoolBadgeColor,
  schoolInitial,
  schoolRegion,
  schoolScore,
  rank,
  rankDelta,
  campusLevel,
  soundEnabled,
  onToggleSound,
  onOpenRankings,
  onOpenMap,
  mode = 'mine',
  onBack,
}) => {
  const formattedScore = schoolScore.toLocaleString('ko-KR');
  const visiting = mode === 'visit';

  return (
    <header className="game-campus-hud rpg-box px-2.5 py-2 relative z-30 select-none">
      {/* Top row: 로고, 게임 태그, 학교명(넘치면 왕복), 레벨, 소리. 지역은 로고 title로 제공해 한 줄로 유지 */}
      <div className="flex items-center justify-between gap-1.5 border-b-2 border-[#261608]/20 pb-1.5 mb-1.5">
        {/* Left: Square Pixel Badge & School Name */}
        <div className="flex items-center gap-2 min-w-0 flex-1">
          {visiting && (
            <button
              type="button"
              onClick={() => {
                soundManager.playTabSound();
                onBack?.();
              }}
              title="지도로 돌아가기"
              aria-label="지도로 돌아가기"
              className="pixel-plaque plaque-parchment w-7 h-7 shrink-0 flex items-center justify-center cursor-pointer text-[#261608]"
            >
              <ArrowLeft className="w-3.5 h-3.5" />
            </button>
          )}
          {/* Simple square pixel badge showing "한" in white on solid blue (#0284c7) */}
          <div title={`${schoolName} · ${schoolRegion}`} style={{ backgroundColor: schoolBadgeColor }} className="w-7 h-7 shrink-0 border-2 border-[#261608] shadow-[1px_1px_0px_#261608] flex items-center justify-center">
            <SharedImage target={{ type: 'school', id: schoolId }} name={schoolName} fallback={schoolInitial ?? schoolName.charAt(0)} className="w-full h-full font-pixel font-black text-white text-sm" />
          </div>

          <div className="min-w-0 flex-1 flex items-center gap-1">
            {/* "대대대" tag; "방문 중" while visiting */}
            {visiting ? (
              <span data-testid="visit-tag" className="shrink-0 font-pixel text-xs font-bold text-[#fef08a] bg-[#0284c7] border border-[#261608] px-1 rounded-none leading-tight">
                방문 중
              </span>
            ) : (
              <span className="shrink-0 font-pixel text-xs font-bold text-[#e11d48] bg-[#ffe4e6] border border-[#f43f5e] px-1 rounded-none leading-tight">
                대대대
              </span>
            )}
            {/* 학교명: 칸보다 길면 양 끝 페이드와 함께 좌우로 천천히 왕복 */}
            <h1 className="min-w-0 flex-1 font-pixel text-sm font-bold text-[#261608] leading-none">
              <MarqueeText text={schoolName} />
            </h1>
          </div>
        </div>

        {/* Right: Campus Level "Lv.4" & Sound Toggle */}
        <div className="flex items-center gap-1.5 shrink-0">
          {visiting ? (
            <span
              title={`캠퍼스 Lv.${campusLevel}`}
              className="pixel-plaque plaque-wood px-2 py-1 text-xs font-pixel font-bold flex items-center gap-1"
            >
              <span className="w-1.5 h-1.5 bg-[#4ade80] inline-block" />
              <span>Lv.{campusLevel}</span>
            </span>
          ) : (
            <button
              type="button"
              onClick={onOpenMap}
              title="캠퍼스 성장도 확인하기"
              className="pixel-plaque plaque-wood px-2 py-1 text-xs font-pixel font-bold cursor-pointer flex items-center gap-1"
            >
              <span className="w-1.5 h-1.5 bg-[#4ade80] inline-block animate-pixel-blink" />
              <span>Lv.{campusLevel}</span>
            </button>
          )}

          <button
            type="button"
            onClick={() => {
              onToggleSound();
              soundManager.playTabSound();
            }}
            title={soundEnabled ? '효과음 끄기' : '효과음 켜기'}
            className="pixel-plaque plaque-parchment w-7 h-7 flex items-center justify-center cursor-pointer text-[#261608]"
            aria-label={soundEnabled ? '소리 끄기' : '소리 켜기'}
          >
            {soundEnabled ? (
              <Volume2 className="w-3.5 h-3.5 text-[#261608]" />
            ) : (
              <VolumeX className="w-3.5 h-3.5 text-stone-500" />
            )}
          </button>
        </div>
      </div>

      {/* Bottom row: School Score ("학교 점수 1,333,872점") & Rank Badge ("전국 7위 ▲2") */}
      <div className="flex items-center justify-between gap-2">
        {/* School Score with Sunflower Yellow Accent */}
        <div className="flex items-baseline gap-1.5 min-w-0">
          <span className="game-campus-score-label text-[11px] text-[#8b5327] font-bold font-pixel shrink-0">
            학교 점수
          </span>
          <div data-testid={visiting ? 'visit-score' : undefined} className="game-campus-score font-press-start text-xs sm:text-sm text-[#261608] font-black tracking-tight truncate bg-[#fef08a] border-2 border-[#261608] px-1.5 py-0.5 shadow-[1px_1px_0px_#261608]">
            {formattedScore}
            <span className="text-[10px] font-pixel font-bold ml-1 text-[#8b5327]">점</span>
          </div>
        </div>

        {/* Rank Badge: "전국 7위 ▲2" */}
        <button
          type="button"
          onClick={onOpenRankings}
          className="pixel-plaque plaque-yellow px-2 py-1 flex items-center gap-1 text-xs font-pixel font-bold cursor-pointer shrink-0"
          title="전국 대학 순위 보기"
        >
          <span>전국 <span data-testid={visiting ? 'visit-rank' : undefined}>{rank === null ? '순위 없음' : `${rank}위`}</span></span>
          {rankDelta !== null && (
            <span
              data-testid="rank-change"
              data-trend={rankTrend(rankDelta)}
              className={`text-[10px] font-mono border border-[#261608] px-1 py-0.2 font-black shadow-[1px_1px_0px_#261608] ${RANK_TREND_TONE[rankTrend(rankDelta)]}`}
            >
              {rankDelta}
            </span>
          )}
        </button>
      </div>
    </header>
  );
};
