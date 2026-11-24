import type { SchoolId } from '../data/schoolIds.ts';
import React from 'react';
import { ArrowLeft, Eye, Heart, MapPin, Users } from 'lucide-react';
import { CAMPUS_STAGES } from '../data/gameRules.ts';
import { soundManager } from '../utils/sound';
import { useSchoolViews } from '../hooks/useSchoolViews.ts';
import { getSchoolById, type SchoolView } from '../data/schoolDirectory.ts';
import { getVisitGuestbookPreview } from '../data/mockGuestbook.ts';
import { SharedImage } from './SharedImage.tsx';
import { PhoneShell } from './PhoneShell';
import type { VisitSchoolExtras, VisitSchoolView } from '../hooks/useVisitSchool.ts';

// Shared pieces of the visiting screens. Plan: docs/261108-2000-m2_map_visit_return_plan.md 4-1.
// The visited school is read by ID from the same rows the map and rankings use, so the values agree.

export interface VisitedSchool {
  /** Undefined when the ID is not in the school list (A5: show a notice, never another school). */
  school: SchoolView | undefined;
  mySchool: SchoolView;
}

export function useVisitedSchool(schoolId: SchoolId | null): VisitedSchool {
  const views = useSchoolViews();
  const mySchool = views.find((school) => school.isMySchool);
  if (!mySchool) throw new Error('My school is missing from the school list.');
  return { school: schoolId === null ? undefined : getSchoolById(views, schoolId), mySchool };
}

export function rankMedal(rank: number | null): string {
  return (rank !== null && ['🥇', '🥈', '🥉'][rank - 1]) || '🏫';
}

/** "3위 (▲2)"; a school without a server rank reads "순위 없음". Null hides the change (no copy of today yet). */
export function formatVisitRank(rank: number | null, rankChange: string | null): string {
  const base = rank === null ? '순위 없음' : `${rank}위`;
  return rankChange === null ? base : `${base} (${rankChange})`;
}

export function formatScore(score: number): string {
  return `${score.toLocaleString('ko-KR')}점`;
}

export const SPECTATOR_NOTICE = '타 대학 캠퍼스는 클릭할 수 없습니다!';

/** Decision D2: there is no per-school contributor data in the mockup, so say so instead of showing fixed rows. */
export const ContributorsEmpty: React.FC = () => (
  <p className="font-pixel text-[9px] text-[#8b5327] bg-[#ffffff] border border-dashed border-[#261608]/40 px-1.5 py-1 text-center">
    기여 랭커 정보 없음 (목업)
  </p>
);

/** Decision D2: the live click count comes from the server in the service; the mockup has none. Ticker content. */
export const LiveClicksEmpty: React.FC = () => (
  <>
    <Users className="w-3 h-3 text-[#0284c7]" aria-hidden="true" />
    관전 중 · 실시간 클릭 정보 없음 (목업)
  </>
);

/**
 * Spectator bar where my campus shows the chat preview (V2, docs/261110-2005-visit_ui_jeju_map_plan.md §4-2, Q1):
 * says the visit is look-only and leads back to my school. The button is as tall as the 20px icon, so the bar keeps
 * the chat preview's height and the campus sits where it does on my campus (the 44px touch area is the global ::after).
 */
export const SpectatorBar: React.FC<{ mySchoolShortName: string | null; onBackToMySchool: () => void }> = ({ mySchoolShortName, onBackToMySchool }) => (
  <div data-testid="spectator-bar" className="w-full bg-[#faecc7] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] px-2.5 py-1 flex items-center justify-between gap-2 select-none">
    <div className="flex items-center gap-1.5 min-w-0 flex-1">
      <div className="w-5 h-5 bg-[#38bdf8] border border-[#261608] flex items-center justify-center shrink-0 shadow-[1px_1px_0px_#261608]">
        <Eye className="w-3 h-3 text-[#261608]" aria-hidden="true" />
      </div>
      <p className="min-w-0 font-pixel text-[11px] font-bold text-[#261608] truncate" title={mySchoolShortName ? `내 학교(${mySchoolShortName}) 점수에 기여하려면 내 학교로 돌아가세요` : '로그인하면 내 학교 점수에 기여할 수 있어요'}>
        구경만 할 수 있어요
      </p>
    </div>
    <button
      type="button"
      onClick={() => {
        soundManager.playTabSound();
        onBackToMySchool();
      }}
      aria-label="내 학교로 돌아가기"
      className="pixel-plaque plaque-yellow shrink-0 h-5 px-2 py-0 leading-none font-pixel text-[11px] font-black text-[#261608] flex items-center gap-1 cursor-pointer"
    >
      <ArrowLeft className="w-3 h-3" aria-hidden="true" />
      <span>내 학교로</span>
    </button>
  </div>
);

/**
 * Body of the visited school's info sheet (V2, Q2), opened from the sign where my campus has 상세 스탯: the values
 * that sat in panels under the old small campus (score, contributors, guestbook preview). With the API (SCRUM-163)
 * `extras` adds the member count, top departments and contributors; the mock has none and says so.
 */
export const VisitSchoolInfoSheet: React.FC<{ school: VisitSchoolView; extras?: VisitSchoolExtras | null; onOpenGuestbook: () => void }> = ({
  school,
  extras = null,
  onOpenGuestbook,
}) => {
  const stage = CAMPUS_STAGES.find((s) => s.level === school.campusLevel) ?? CAMPUS_STAGES[0];
  return (
    <div className="p-3 flex flex-col gap-2 bg-[#fdf5df] text-[#261608]">
      <h2 className="font-pixel text-sm font-black">{school.name} 학교 정보</h2>
      <section aria-label="학교 점수" className="rpg-box p-2 flex flex-col gap-1 shadow-[2px_2px_0px_#261608]">
        <div className="flex items-center justify-between gap-2 font-pixel text-xs font-bold">
          <span>학교 점수</span>
          <span data-testid="visit-info-score" className="font-press-start text-[9px] text-[#0284c7]">{formatScore(school.score)}</span>
        </div>
        <div className="flex items-center justify-between gap-2 font-pixel text-xs font-bold">
          <span>전국 순위</span>
          <span data-testid="visit-info-rank">{rankMedal(school.rank)} {formatVisitRank(school.rank, school.rankChange)}</span>
        </div>
        {extras && (
          <div className="flex items-center justify-between gap-2 font-pixel text-xs font-bold">
            <span>재학생 회원</span>
            <span data-testid="visit-info-members">{extras.memberCount.toLocaleString('ko-KR')}명</span>
          </div>
        )}
        <p className="font-pixel text-xs font-bold">🏛️ 캠퍼스 Lv.{stage.level} · {stage.name}</p>
      </section>
      <section aria-label="기여 랭커" className="rpg-box p-2 flex flex-col gap-1 shadow-[2px_2px_0px_#261608]">
        <h3 className="font-pixel text-xs font-black">기여 랭커</h3>
        {extras ? (
          <VisitTopList
            label="기여 랭커 목록"
            empty="아직 기여한 학생이 없어요"
            rows={extras.topContributors.map((row) => ({ key: row.userId, rank: row.rank, name: row.nickname, score: row.contribScore }))}
          />
        ) : (
          <ContributorsEmpty />
        )}
      </section>
      {extras && (
        <section aria-label="상위 학과" className="rpg-box p-2 flex flex-col gap-1 shadow-[2px_2px_0px_#261608]">
          <h3 className="font-pixel text-xs font-black">상위 학과</h3>
          <VisitTopList
            label="상위 학과 목록"
            empty="아직 점수를 올린 학과가 없어요"
            rows={extras.topDepartments.map((row) => ({ key: row.id, rank: row.rank, name: row.name, score: row.totalScore }))}
          />
        </section>
      )}
      <section aria-label={extras ? '학교 방명록' : '방명록 인기 쪽지'} className="bg-[#8b5327] border-3 border-[#261608] shadow-[3px_3px_0px_#261608] p-2 flex flex-col gap-2">
        <h3 className="font-pixel text-xs font-black text-[#fffbeb] drop-shadow-[1px_1px_0px_#261608]">{extras ? '학교 방명록' : '방명록 인기 쪽지'}</h3>
        {extras ? <p className="font-pixel text-xs text-[#fffbeb]">전체보기에서 실제 글을 확인하세요. 인기순은 준비 중입니다.</p> : <GuestbookPreviewNotes schoolId={school.id} />}
        <button
          type="button"
          onClick={(event) => {
            event.currentTarget.focus({ preventScroll: true });
            soundManager.playTabSound();
            onOpenGuestbook();
          }}
          className="pixel-plaque plaque-yellow min-h-11 font-pixel text-xs font-black text-[#261608] cursor-pointer"
        >
          방명록 전체보기
        </button>
      </section>
    </div>
  );
};

/** Top three rows of the server's detail (ties share a rank), or a line saying there are none yet. */
const VisitTopList: React.FC<{ label: string; empty: string; rows: Array<{ key: number; rank: number; name: string; score: number }> }> = ({ label, empty, rows }) =>
  rows.length === 0 ? (
    <p className="font-pixel text-[9px] text-[#8b5327] bg-[#ffffff] border border-dashed border-[#261608]/40 px-1.5 py-1 text-center">{empty}</p>
  ) : (
    <ol aria-label={label} className="flex flex-col gap-0.5">
      {rows.map((row) => (
        <li key={row.key} className="flex items-center justify-between gap-2 font-pixel text-[11px] font-bold bg-[#ffffff] border border-[#261608]/30 px-1.5 py-0.5">
          <span className="min-w-0 truncate">{rankMedal(row.rank)} {row.rank}위 · {row.name}</span>
          <span className="font-press-start text-[8px] text-[#0284c7] shrink-0">{formatScore(row.score)}</span>
        </li>
      ))}
    </ol>
  );

interface VisitNotFoundPhoneProps {
  schoolId: SchoolId | null;
  onBackToMap: () => void;
  embedded?: boolean;
}

/** A5: an unknown school ID ends in a notice and a way back to the map. */
export const VisitNotFoundPhone: React.FC<VisitNotFoundPhoneProps> = ({ schoolId, onBackToMap, embedded }) => (
  <PhoneShell chrome={false} embedded={embedded} className="items-center justify-center gap-3 p-6">
    <div role="alert" className="rpg-box p-3 bg-[#fffbeb] border-3 border-[#261608] shadow-[3px_3px_0px_#261608] flex flex-col items-center gap-1 text-center">
      <p className="font-pixel text-sm font-black text-[#261608]">학교를 찾을 수 없습니다</p>
      <p className="font-pixel text-[10px] text-[#8b5327]">요청한 학교 ID: {schoolId ?? '(없음)'}</p>
    </div>
    <button
      type="button"
      onClick={() => {
        soundManager.playTabSound();
        onBackToMap();
      }}
      className="pixel-plaque plaque-yellow py-1.5 px-3 font-pixel text-xs font-black text-[#261608] flex items-center gap-1.5 cursor-pointer shadow-[2px_2px_0px_#261608]"
    >
      <MapPin className="w-3.5 h-3.5" />
      <span>지도로 돌아가기</span>
    </button>
  </PhoneShell>
);

const PREVIEW_PIN_COLORS = ['#e11d48', '#0284c7'];

/** Most liked notes of the visited school's guestbook, from the same samples as the full guestbook sheet. */
export const GuestbookPreviewNotes: React.FC<{ schoolId: SchoolId }> = ({ schoolId }) => (
  <div className="flex flex-col gap-1.5">
    {getVisitGuestbookPreview(schoolId).map((note, index) => (
      <div key={note.id} data-testid="guestbook-preview-note" className="w-full bg-[#fffdf5] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] p-2 flex flex-col gap-0.5 relative">
        <div style={{ backgroundColor: PREVIEW_PIN_COLORS[index % PREVIEW_PIN_COLORS.length] }} className="absolute -top-1.5 left-2 w-3 h-3 border border-[#261608] rounded-full shadow-[1px_1px_0px_#261608]" />
        <div className="flex items-center justify-between text-[9px] font-pixel pt-0.5">
          <div className="flex items-center gap-1 min-w-0">
            <span style={{ backgroundColor: note.schoolBadgeBg }} className="w-3.5 h-3.5 text-white border border-[#261608] font-pixel font-bold text-[8px] flex items-center justify-center shrink-0">
              <SharedImage target={{ type: 'school', id: note.schoolId }} name={note.schoolName} fallback={note.schoolInitial} className="w-full h-full" />
            </span>
            <span className="font-bold text-[#261608] truncate">{note.nickname}</span>
            <span className="text-[8px] text-[#8b5327] truncate">({note.schoolName})</span>
          </div>
          <span className="text-stone-400 font-pixel text-[8px]">{note.relativeTime}</span>
        </div>
        <p className="font-pixel text-[11px] text-[#261608] font-bold mt-0.5 break-words">{note.message}</p>
        <div className="flex items-center justify-end gap-1 text-[9px] font-pixel text-[#e11d48] font-bold pt-0.5">
          <Heart className="w-2.5 h-2.5 fill-[#e11d48]" />
          <span className="font-mono text-[9px]">{note.likeCount}</span>
        </div>
      </div>
    ))}
  </div>
);
