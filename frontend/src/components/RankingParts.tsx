// Pieces shared by the two ranking screens (SCRUM-161): row image, caption and the not-ready notice.

import React, { type ReactNode } from 'react';
import { isHallRank } from '../api/rankingHall';
import type { RankingBoard, RankingBoardRow, RankingTab } from '../hooks/useRankingBoard';
import { ImageFallback, SharedImage } from './SharedImage.tsx';
import { LoadNotice } from './LoadNotice';

export type RankTrend = 'up' | 'down' | 'same';
/** ▲ up, ▼ down, anything else ("-") unchanged. The server's rankChange can be a fall (SCRUM-184). */
export const rankTrend = (delta: string): RankTrend => (delta.startsWith('▲') ? 'up' : delta.startsWith('▼') ? 'down' : 'same');
/** Badge colors for every rank-change badge: list rows, the pinned own row and the main HUD. */
export const RANK_TREND_TONE: Record<RankTrend, string> = {
  up: 'text-[#ffffff] bg-[#22c55e]',
  down: 'text-[#ffffff] bg-[#fb7185]',
  same: 'text-[#261608] bg-[#faecc7]',
};

/** The mock's demo image store, or the server's URL with the bundled school logo and the first syllable as fallbacks. */
export const RankingRowImage: React.FC<{ row: RankingBoardRow; className?: string; fallback?: ReactNode }> = ({ row, className, fallback = row.firstSyllable }) =>
  row.image.kind === 'shared' ? (
    <SharedImage target={row.image.target} name={row.name} fallback={fallback} className={className} />
  ) : (
    <ImageFallback url={row.image.url ?? undefined} defaultUrl={row.image.defaultUrl} name={row.name} fallback={fallback} className={className} />
  );

/** Keep the usual list and load-more independent of the supplemental hall scan. */
export function rankingListRows(board: RankingBoard) {
  return board.hall ? board.rows.filter((row, index) => index >= 3 || !isHallRank(row.rank)) : board.rows.slice(3);
}

export function rankingIsEmpty({ rows, hall }: RankingBoard) {
  return rows.length === 0 && !hall?.loading && !hall?.incomplete && !hall?.rows.length;
}

export const SIGNED_OUT_RANKING_NOTICE = '로그인하면 내 학교의 학과·개인 순위를 볼 수 있어요.';

/** Two caption lines: what the list ranks, then how it is ordered. Server rows share a rank on a tie. */
export function rankingCaption(tab: RankingTab, board: Pick<RankingBoard, 'isSample'>, schoolName: string): [string, string] {
  const scope = tab === 'school' ? '전국 학교' : tab === 'dept' ? `${schoolName} 교내 학과` : `${schoolName} 교내 개인 · 소비 후 보유 기여도`;
  return board.isSample
    ? [`${scope} · 목업 샘플`, '점수 내림차순 → 이름 오름차순 · 동점 순위는 임시 연번 · 변경 즉시 반영']
    : [scope, '점수 내림차순 · 동점은 같은 순위'];
}

/** Loading, failure or signed-out notice; null once the rows are ready. */
export const RankingStatusNotice: React.FC<{ board: RankingBoard }> = ({ board }) => {
  if (board.status === 'ready') return null;
  if (board.status === 'signed-out') {
    return (
      <div role="status" className="rpg-box p-3 bg-[#fffbeb] border-2 border-[#261608] text-center font-pixel text-[11px] font-bold text-[#8b5327]">
        {SIGNED_OUT_RANKING_NOTICE}
      </div>
    );
  }
  return <LoadNotice status={board.status === 'loading' ? 'loading' : 'failed'} name="순위" failureDetail={board.failureDetail} onRetry={board.retry} variant="panel" />;
};
