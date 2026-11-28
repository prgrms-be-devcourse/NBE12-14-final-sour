import { useEffect, useEffectEvent, useLayoutEffect, useRef, useState } from 'react';
import { Building2, ChevronLeft, ChevronRight, Crown, GraduationCap, Pause, Play, RefreshCw, UserRound } from 'lucide-react';
import type { RankingBoard, RankingBoardRow, RankingTab } from '../hooks/useRankingBoard';
import { useReducedMotion } from './CampusAmbience';
import { RankingRowImage } from './RankingParts';
import { RankingSchoolTrigger, type RankingSchool } from './RankingSchoolMenu';

type Rank = 1 | 2 | 3;
const RANKS = [1, 2, 3] as const;
const ROTATE_MS = 3_000;
const SYMBOL = { school: Building2, dept: GraduationCap, personal: UserRound };
const CONTROL = 'game-hall-control pixel-plaque plaque-parchment';
type OpenSchool = (school: RankingSchool) => void;

function PlaqueContent({ row, tab }: { row: RankingBoardRow; tab: RankingTab }) {
  const Icon = SYMBOL[tab];
  const score = row.score.toLocaleString('ko-KR');
  return <>
    <span className="game-hall-image" aria-hidden="true" style={{ backgroundColor: row.badgeColor }}>
      <RankingRowImage row={row} className="size-full" fallback={<Icon data-hall-symbol={tab} className="size-5" aria-hidden="true" />} />
    </span>
    <span className="game-hall-name">{row.name}</span>
    <span className="game-hall-score" data-wide-score={score.length > 12 || undefined}>{score}점</span>
  </>;
}

function HallRank({ rank, tab, rows, reduced, menuOpen, openSchool }: {
  rank: Rank; tab: RankingTab; rows: RankingBoardRow[]; reduced: boolean; menuOpen: boolean; openSchool?: OpenSchool;
}) {
  const [selected, setSelected] = useState(rows[0].key);
  const index = Math.max(0, rows.findIndex(row => row.key === selected));
  const current = rows[index];
  if (current.key !== selected) setSelected(current.key);
  const [choice, setChoice] = useState<{ reduced: boolean; play: boolean }>();
  const rankRef = useRef<HTMLLIElement>(null);
  const tied = rows.length > 1;
  const playing = choice?.reduced === reduced ? choice.play : !reduced;
  const rotate = tied && playing && !menuOpen;
  const step = (direction: number) => setSelected(key => {
    const at = Math.max(0, rows.findIndex(row => row.key === key));
    return rows[(at + direction + rows.length) % rows.length].key;
  });
  const onTick = useEffectEvent(() => {
    if (rotate && !document.hidden && !rankRef.current?.querySelector(':focus-visible')) step(1);
  });
  useEffect(() => {
    if (!rotate) return;
    const timer = setInterval(() => onTick(), ROTATE_MS);
    return () => clearInterval(timer);
  }, [rotate, current.key]);

  const last = useRef(current);
  const [outgoing, setOutgoing] = useState<RankingBoardRow>();
  useLayoutEffect(() => {
    const previous = last.current;
    last.current = current;
    if (reduced) setOutgoing(undefined);
    else if (previous.key !== current.key) setOutgoing(previous);
  }, [current, reduced]);
  const fading = outgoing !== undefined && !reduced;

  return <li ref={rankRef} className="game-hall-rank" data-hall-rank={rank}>
    <div className="game-hall-rank-label">
      {rank === 1 && <Crown className="size-4" aria-hidden="true" />}
      <h3>{tied ? '공동 ' : ''}{rank}위</h3>
      {tied && <span> · {index + 1}/{rows.length}</span>}
    </div>
    <div className="game-hall-plaque">
      <RankingSchoolTrigger row={current} open={openSchool} className="game-hall-entry">
        <span key={current.key} className="game-hall-content" data-hall-current data-fading={fading || undefined}
          onAnimationEnd={fading ? () => setOutgoing(undefined) : undefined}>
          <PlaqueContent row={current} tab={tab} />
        </span>
      </RankingSchoolTrigger>
      {fading && <span key={outgoing.key} className="game-hall-content game-hall-outgoing" aria-hidden="true">
        <PlaqueContent row={outgoing} tab={tab} />
      </span>}
    </div>
    {tied && <div className="game-hall-controls">
      <button type="button" className={CONTROL} aria-label={`${rank}위 이전`} onClick={() => step(-1)}><ChevronLeft aria-hidden="true" />이전</button>
      <button type="button" className={CONTROL} aria-label={`${rank}위 다음`} onClick={() => step(1)}><ChevronRight aria-hidden="true" />다음</button>
      <button type="button" className={CONTROL} aria-label={`${rank}위 ${playing ? '일시정지' : '재생'}`}
        onClick={() => setChoice({ reduced, play: !playing })}>
        {playing ? <><Pause aria-hidden="true" />일시정지</> : <><Play aria-hidden="true" />재생</>}
      </button>
    </div>}
  </li>;
}

export function RankingHall({ hall, tab, menuOpen, openSchool }: {
  hall: NonNullable<RankingBoard['hall']>; tab: RankingTab; menuOpen: boolean; openSchool?: OpenSchool;
}) {
  const reduced = useReducedMotion();
  const heading = useRef<HTMLHeadingElement>(null);
  const groups = RANKS.map(rank => ({ rank, rows: hall.rows.filter(row => row.rank === rank) })).filter(group => group.rows.length);
  if (!groups.length && !hall.incomplete && !hall.loading) return null;

  return <section className="game-ranking-podium game-hall rpg-box-wood" aria-label="명예의 전당">
    <h2 ref={heading} tabIndex={-1} className="game-hall-title">명예의 전당</h2>
    <ol className="game-hall-ranks" role="list">
      {groups.map(({ rank, rows }) => <HallRank key={rank} rank={rank} rows={rows} tab={tab} reduced={reduced} menuOpen={menuOpen} openSchool={openSchool} />)}
    </ol>
    {hall.incomplete ? <div className="game-hall-feedback">
      <p role="status">공동 순위를 모두 불러오지 못했어요.</p>
      <button type="button" className={CONTROL} aria-disabled={hall.loading || undefined} onClick={() => {
        if (hall.loading) return;
        heading.current?.focus();
        hall.retry();
      }}>
        <RefreshCw aria-hidden="true" />{hall.loading ? '다시 불러오는 중…' : '다시 시도'}
      </button>
    </div> : hall.loading && <p className="game-hall-feedback">공동 순위를 확인하는 중…</p>}
  </section>;
}
