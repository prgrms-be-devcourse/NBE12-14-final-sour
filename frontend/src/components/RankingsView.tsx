import React, { useState, useEffect, type ReactNode, type UIEventHandler } from 'react';
import { Crown, RefreshCw } from 'lucide-react';
import { ALLIANCE_INVITATIONS_ENABLED } from '../api/alliances';
import { RankingSchoolMenu, RankingSchoolTrigger, type RankingSchool, type RankingSchoolNavigation } from './RankingSchoolMenu';
import { soundManager } from '../utils/sound';
import { useMySchool } from '../hooks/useSchoolViews';
import { useRankingBoard, type RankingBoardRow, type RankingTab } from '../hooks/useRankingBoard';
import { RANK_TREND_TONE, RankingRowImage, RankingStatusNotice, rankTrend, rankingCaption } from './RankingParts';

interface RankingsViewProps extends RankingSchoolNavigation {
  currentSchoolScore: number;
  onCheer?: () => void;
  cheerCooldown?: number;
  onBackToCampus?: () => void;
  desktopPanel?: boolean;
}

const SELF_RANK_LABEL: Record<RankingTab, string> = {
  school: '내 학교',
  dept: '내 학과',
  personal: '내 순위',
};

function RankingScroll({ desktopPanel, tab, onScroll, children }: {
  desktopPanel: boolean;
  tab: RankingTab;
  onScroll: UIEventHandler<HTMLDivElement>;
  children: ReactNode;
}) {
  if (!desktopPanel) return <>{children}</>;
  // Remount only the scroll area when changing boards, not when refreshing a board.
  return <div key={tab} className="game-ranking-scroll" role="region" aria-label="순위 목록" tabIndex={0} onScroll={onScroll}>
    {children}
  </div>;
}

export const RankingsView: React.FC<RankingsViewProps> = ({
  currentSchoolScore,
  onBackToCampus,
  desktopPanel = false,
  onVisitSchool, onGoToLogin, visitBlocked, allianceActionsEnabled = ALLIANCE_INVITATIONS_ENABLED,
}) => {
  const [selectedSchool, setSelectedSchool] = useState<RankingSchool | null>(null);
  const openSchoolMenu = onVisitSchool ? setSelectedSchool : undefined;
  const [activeRankingTab, setActiveRankingTab] = useState<RankingTab>('school');
  const [refreshCountdown, setRefreshCountdown] = useState<number>(5);

  useEffect(() => {
    const timer = setInterval(() => {
      setRefreshCountdown((prev) => (prev <= 1 ? 5 : prev - 1));
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  const mySchool = useMySchool();
  // Mock rows or server pages, by mode (SCRUM-161).
  const board = useRankingBoard(activeRankingTab, currentSchoolScore);
  const [captionScope, captionOrder] = rankingCaption(activeRankingTab, board, mySchool.shortName);

  const top1: RankingBoardRow | undefined = board.rows[0];
  const top2: RankingBoardRow | undefined = board.rows[1];
  const top3: RankingBoardRow | undefined = board.rows[2];
  const remainingRows = board.rows.slice(3);
  const myRow = board.mine;
  const moreLabel = board.isSample ? '+ 10개 대학 더 불러오기' : '+ 더 불러오기';
  const onRankingScroll: UIEventHandler<HTMLDivElement> = ({ currentTarget: el }) => {
    // The button remains available when the first page is too short to scroll.
    if (el.scrollHeight - (el.scrollTop + el.clientHeight) <= 24) board.loadMore();
  };

  const renderPixelCrest = (item: RankingBoardRow, size: 'sm' | 'md' | 'lg' = 'sm') => {
    const sizeClass = { lg: 'w-10 h-10 text-sm font-black', md: 'w-8 h-8 text-xs font-bold', sm: 'w-6 h-6 text-[10px] font-bold' }[size];

    const syllable = item.firstSyllable;
    const bgColor = item.badgeColor;

    return (
      <span aria-hidden="true"
        className={`${sizeClass} border-2 border-[#261608] flex items-center justify-center shrink-0 shadow-[1px_1px_0px_#261608] font-pixel text-white`}
        style={{ backgroundColor: bgColor }}
      >
        {item.schoolId === undefined ? <span className="drop-shadow-[1px_1px_0px_#261608]">{syllable}</span>
          : <RankingRowImage row={item} className="h-full w-full" />}
      </span>
    );
  };

  return (
    <div className={`w-full flex flex-col gap-1.5 animate-fadeIn select-none${desktopPanel ? ' game-panel-ranking' : ''}`}>
      {/* Header Bar with "5초마다 갱신" and back button */}
      <div className="rpg-box p-2 flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <span className="font-pixel text-[10px] font-bold text-[#ffffff] bg-[#e11d48] border border-[#261608] px-1 shadow-[1px_1px_0px_#261608]">
            순위
          </span>
          <span className="font-pixel text-xs font-black text-[#261608]">
            전국 대항전
          </span>
        </div>

        <div className="flex items-center gap-1.5">
          <span className="font-pixel text-[9px] text-[#8b5327] flex items-center gap-0.5">
            <RefreshCw className={`w-2.5 h-2.5 text-[#22c55e] ${board.refreshing ? 'animate-spin' : ''}`} />
            5초마다 갱신 ({refreshCountdown}s)
          </span>
          {onBackToCampus && <button type="button" onClick={onBackToCampus}
            className="pixel-plaque plaque-parchment text-[10px] font-pixel font-bold px-1.5 py-0.5 text-[#261608] cursor-pointer">
            돌아가기
          </button>}
        </div>
      </div>

      <p className="font-pixel text-[9px] text-[#8b5327]">
        {captionScope}<br />
        {captionOrder}
      </p>
      {/* Top 3 Pixel Tab Switcher */}
      <div className="grid grid-cols-3 gap-1 p-1 rpg-box bg-[#fdf5df]">
        <button type="button"
          onClick={() => {
            setActiveRankingTab('school');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque py-1 px-1 text-center font-pixel text-xs font-bold cursor-pointer transition-transform ${
            activeRankingTab === 'school' ? 'plaque-yellow' : 'plaque-parchment text-[#8b5327]'
          }`}
        >
          학교 순위
        </button>

        <button type="button"
          onClick={() => {
            setActiveRankingTab('dept');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque py-1 px-1 text-center font-pixel text-xs font-bold cursor-pointer transition-transform ${
            activeRankingTab === 'dept' ? 'plaque-yellow' : 'plaque-parchment text-[#8b5327]'
          }`}
        >
          교내 학과
        </button>

        <button type="button"
          onClick={() => {
            setActiveRankingTab('personal');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque py-1 px-1 text-center font-pixel text-xs font-bold cursor-pointer transition-transform ${
            activeRankingTab === 'personal' ? 'plaque-yellow' : 'plaque-parchment text-[#8b5327]'
          }`}
        >
          교내 개인
        </button>
      </div>

      {board.status === 'ready' ? (<>
      <RankingScroll desktopPanel={desktopPanel} tab={activeRankingTab} onScroll={onRankingScroll}>
      {board.rows.length === 0 && <p role="status" className="rpg-box p-3 font-pixel text-xs">표시할 순위가 없어요.</p>}
      {/* Pixel Podium on Grassy Stage */}
      {top1 && <div className="game-ranking-podium w-full border-3 border-[#261608] shadow-[2px_2px_0px_#261608] bg-[#38a134] overflow-hidden shrink-0 relative flex flex-col justify-end pt-2 pb-1 px-1.5">
        <div className="absolute inset-x-0 top-0 h-6 bg-[#38bdf8] border-b-2 border-[#261608] flex items-center justify-between px-2">
          <span className="font-pixel text-[8px] text-white font-bold">
            👑 TOP 3 명예의 전당
          </span>
          <div className="w-3 h-3 bg-[#facc15] border border-[#261608]" />
        </div>

        <div className="grid grid-cols-3 gap-1 items-end pt-5 relative z-10">
          {/* 2nd Place */}
          {top2 ? (
            <div className="flex flex-col items-center">
              <div className="flex flex-col items-center mb-0.5">
                <RankingSchoolTrigger row={top2} open={openSchoolMenu} className="flex min-w-0 flex-col items-center">
                  {renderPixelCrest(top2, 'md')}
                  <span className="font-pixel text-[10px] font-bold text-[#ffffff] drop-shadow-[1px_1px_0px_#261608] text-center truncate max-w-[75px]">{top2.name}</span>
                </RankingSchoolTrigger>
                <span className="font-press-start text-[7px] text-[#fef08a]">
                  {top2.score.toLocaleString('ko-KR')}
                </span>
              </div>
              <div className="w-full h-12 bg-[#cbd5e1] border-2 border-[#261608] shadow-[1px_1px_0px_#261608] flex flex-col items-center justify-center">
                <span className="font-press-start text-[10px] font-black text-[#261608]">{top2.rank}</span>
              </div>
            </div>
          ) : <div aria-hidden="true" />}

          {/* 1st Place */}
          {top1 ? (
            <div className="flex flex-col items-center -mt-2">
              <Crown className="w-4 h-4 text-[#facc15] fill-[#facc15] drop-shadow-[1px_1px_0px_#261608] animate-bounce" />
              <div className="flex flex-col items-center mb-0.5">
                <RankingSchoolTrigger row={top1} open={openSchoolMenu} className="flex min-w-0 flex-col items-center">
                  {renderPixelCrest(top1, 'lg')}
                  <span className="font-pixel text-xs font-black text-[#fef08a] drop-shadow-[1px_1px_0px_#261608] text-center truncate max-w-[85px]">{top1.name}</span>
                </RankingSchoolTrigger>
                <span className="font-press-start text-[8px] text-[#fef08a] font-black">
                  {top1.score.toLocaleString('ko-KR')}
                </span>
              </div>
              <div className="w-full h-16 bg-[#facc15] border-2 border-[#261608] shadow-[1px_1px_0px_#261608] flex flex-col items-center justify-center">
                <span className="font-press-start text-xs font-black text-[#261608]">{top1.rank}</span>
              </div>
            </div>
          ) : <div aria-hidden="true" />}

          {/* 3rd Place */}
          {top3 ? (
            <div className="flex flex-col items-center">
              <div className="flex flex-col items-center mb-0.5">
                <RankingSchoolTrigger row={top3} open={openSchoolMenu} className="flex min-w-0 flex-col items-center">
                  {renderPixelCrest(top3, 'md')}
                  <span className="font-pixel text-[10px] font-bold text-[#ffffff] drop-shadow-[1px_1px_0px_#261608] text-center truncate max-w-[75px]">{top3.name}</span>
                </RankingSchoolTrigger>
                <span className="font-press-start text-[7px] text-[#fef08a]">
                  {top3.score.toLocaleString('ko-KR')}
                </span>
              </div>
              <div className="w-full h-10 bg-[#d97706] border-2 border-[#261608] shadow-[1px_1px_0px_#261608] flex flex-col items-center justify-center">
                <span className="font-press-start text-[9px] font-black text-white">{top3.rank}</span>
              </div>
            </div>
          ) : <div aria-hidden="true" />}
        </div>
      </div>}

      {/* Scrollable Parchment Rows (rank 4 and below, as loaded) */}
      <div
        className="game-ranking-list flex flex-col gap-1 max-h-[160px] overflow-y-auto pr-0.5"
        onScroll={desktopPanel ? undefined : onRankingScroll}
      >
        {remainingRows.map((row) => (
          <div
            key={row.key}
            className="game-ranking-row rpg-box p-1.5 flex items-center justify-between bg-[#fdf5df] border-2"
          >
            <div className="flex items-center gap-1.5 min-w-0">
              <span className="font-press-start text-[10px] font-black text-[#261608] w-5 text-center shrink-0">
                {row.rank}
              </span>
              <RankingSchoolTrigger row={row} open={openSchoolMenu} className="flex items-center gap-1.5 min-w-0 text-left">
                {renderPixelCrest(row, 'sm')}
                <span className="min-w-0">
                  <span className="font-pixel text-xs font-bold text-[#261608] truncate block">{row.name}</span>
                  <span className="text-[8px] text-[#8b5327] font-mono block -mt-0.5">{row.subtitle}</span>
                </span>
              </RankingSchoolTrigger>
            </div>

            <div className="flex items-center gap-1 shrink-0">
              <span className="font-press-start text-[9px] text-[#261608] font-bold">
                {row.score.toLocaleString('ko-KR')}
              </span>
              {/* Null: nothing to compare against 00:00 KST (new member, or no copy for today), so no badge. */}
              {row.change !== null && (
                <span
                  className={`text-[8px] font-mono px-0.5 py-0.2 border border-[#261608] font-bold ${RANK_TREND_TONE[rankTrend(row.change)]}`}
                >
                  {row.change}
                </span>
              )}
            </div>
          </div>
        ))}

        {(board.hasMore || board.loadingMore) && (
          <button type="button"
            onClick={board.loadMore}
            disabled={board.loadingMore}
            className="w-full py-1.5 text-center font-pixel text-[9px] text-[#8b5327] bg-[#faecc7]/80 hover:bg-[#faecc7] border border-dashed border-[#8b5327] cursor-pointer mt-0.5 active:translate-y-0.5"
          >
            {board.loadingMore ? '순위 더 불러오는 중…' : moreLabel} ({board.rows.length} / {board.totalCount})
          </button>
        )}
      </div>

      </RankingScroll>

      {/* My Row: Outside the desktop scroll area, or just after the list in compact mode. */}
      {myRow && (
        <div
          className="game-ranking-self p-1.5 border-3 border-[#facc15] bg-[#fef08a] shadow-[0_0_0_2px_#261608,2px_2px_0px_#261608] flex items-center justify-between mt-1"
          style={{
            boxShadow: '0 0 0 2px #261608, 2px 2px 0px #261608, 0 0 8px rgba(250, 204, 21, 0.9)',
          }}
        >
          <div className="game-ranking-self-meta flex items-center gap-1.5 min-w-0">
            <span className="game-ranking-self-rank font-press-start text-xs font-black text-[#261608] w-5 text-center shrink-0">
              {myRow.rank}
            </span>
            <RankingSchoolTrigger row={myRow} open={openSchoolMenu} className="flex items-center gap-1.5 min-w-0">
              {renderPixelCrest(myRow, 'sm')}
              <span className="font-pixel text-xs font-black text-[#261608] truncate">{myRow.name}</span>
            </RankingSchoolTrigger>
            <span className="game-ranking-self-badge text-[8px] bg-[#e11d48] text-white font-mono px-0.5 border border-[#261608] shrink-0 font-black">
              {desktopPanel ? SELF_RANK_LABEL[activeRankingTab] : 'MY'}
            </span>
          </div>

          <div className="game-ranking-self-score flex items-center gap-1 shrink-0">
            <span className="font-press-start text-xs text-[#261608] font-black">
              {myRow.score.toLocaleString('ko-KR')}
            </span>
            {myRow.change !== null && (
              <span data-testid="my-rank-change" data-trend={rankTrend(myRow.change)}
                className={`text-[9px] font-mono px-1 py-0.2 border border-[#261608] font-black ${RANK_TREND_TONE[rankTrend(myRow.change)]}`}>
                {myRow.change}
              </span>
            )}
          </div>
        </div>
      )}
      </>) : <RankingStatusNotice board={board} />}
      {selectedSchool && <RankingSchoolMenu school={selectedSchool} source={board.source}
        onVisitSchool={onVisitSchool} onGoToLogin={onGoToLogin} visitBlocked={visitBlocked}
        allianceActionsEnabled={allianceActionsEnabled} close={() => setSelectedSchool(null)} />}
    </div>
  );
};
