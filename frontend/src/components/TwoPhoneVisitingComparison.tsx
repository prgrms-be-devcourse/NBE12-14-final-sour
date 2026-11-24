import React from 'react';
import { VisitingScreen1Phone } from './VisitingScreen1Phone';
import { VisitingScreen2Phone } from './VisitingScreen2Phone';
import { NavTab } from './BottomNav';
import { DEMO_VISIT_SCHOOL_ID } from '../data/schoolDirectory.ts';
import { useVisitedSchool } from './VisitingParts.tsx';
import { Sparkles, ArrowRight, BookOpen, Heart, ShieldCheck } from 'lucide-react';

interface TwoPhoneVisitingComparisonProps {
  onBackToMySchool?: () => void;
  onNavigateTab?: (tab: NavTab) => void;
}

export const TwoPhoneVisitingComparison: React.FC<TwoPhoneVisitingComparisonProps> = ({
  onBackToMySchool = () => {},
  onNavigateTab = () => {},
}) => {
  const { school } = useVisitedSchool(DEMO_VISIT_SCHOOL_ID);
  const demoName = school?.name ?? DEMO_VISIT_SCHOOL_ID;
  return (
    <div className="w-full flex flex-col items-center py-2 animate-fadeIn select-none">
      {/* Top Banner Explaining the 2 Phone Mockups */}
      <div className="w-full max-w-5xl flex flex-col sm:flex-row items-center justify-between gap-2 mb-4 px-2">
        <div className="flex items-center gap-2">
          <span className="font-pixel text-[11px] text-white bg-[#0d9488] border border-[#261608] px-2 py-0.5 shadow-[1px_1px_0px_#261608] font-bold">
            2-PHONE SIDE BY SIDE MOCKUP
          </span>
          <span className="font-pixel text-xs text-[#8b5327] font-bold">
            SCREEN 1 (방문 화면 · 방명록 게시판) ➔ SCREEN 2 (75% 전체 방명록 바텀시트)
          </span>
        </div>

        <div className="flex items-center gap-3 text-[10px] font-pixel text-[#8b5327]">
          <div className="flex items-center gap-1">
            <span style={{ backgroundColor: school?.badgeColor }} className="w-3.5 h-3.5 text-white font-pixel font-bold text-[8px] flex items-center justify-center border border-[#261608]">
              {school?.firstSyllable}
            </span>
            <span>시연 학교 {demoName} · 공통 순위 {school?.rank}위 · 캠퍼스 Lv.{school?.campusLevel}</span>
          </div>
          <div className="flex items-center gap-1">
            <Heart className="w-3.5 h-3.5 text-[#e11d48] fill-[#e11d48]" />
            <span>하트 좋아요 & 쪽지 작성 인터랙션</span>
          </div>
        </div>
      </div>

      {/* 2 Phone Screens Side by Side */}
      <div className="w-full max-w-5xl flex flex-col lg:flex-row items-center lg:items-start justify-center gap-6 lg:gap-8">
        {/* ========================================================= */}
        {/* PHONE 1: SCREEN 1 — VISITING SCREEN WITH GUESTBOOK PREVIEW */}
        {/* ========================================================= */}
        <div className="flex flex-col items-center">
          {/* Screen 1 Title Header */}
          <div className="mb-2 flex items-center gap-2">
            <span className="w-5 h-5 bg-[#facc15] border-2 border-[#261608] flex items-center justify-center font-press-start text-[9px] text-[#261608] font-black shadow-[1px_1px_0px_#261608]">
              1
            </span>
            <div className="flex items-center gap-1.5">
              <BookOpen className="w-4 h-4 text-[#0d9488]" />
              <h2 className="font-pixel text-sm font-black text-[#261608]">
                SCREEN 1 — 방문 화면 (메인 게임 틀)
              </h2>
            </div>
          </div>

          {/* Screen 1 Phone Mockup */}
          <VisitingScreen1Phone
            schoolId={DEMO_VISIT_SCHOOL_ID}
            onBackToMySchool={onBackToMySchool}
            onNavigateTab={onNavigateTab}
          />

          {/* Description Caption */}
          <div className="mt-2.5 p-2 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] max-w-[390px] text-left">
            <p className="font-pixel text-[10px] text-[#261608] font-bold leading-relaxed">
              • <span className="text-[#0d9488]">메인 게임과 같은 틀:</span> 상단 HUD·캠퍼스·하단 바·내비 배치가 내 캠퍼스와 같음 (V1)<br />
              • <span className="text-[#0284c7]">방문 HUD:</span> 뒤로가기(지도), "방문 중" 태그, 학교 점수·전국 순위 (Lv 배지는 누를 수 없음)<br />
              • <span className="text-[#8b5327]">왼쪽 아래 방명록 게시판:</span> 누르면 이 학교 방명록 바텀시트 (Screen 2)<br />
              • <span className="text-[#eab308]">오른쪽 아래 "학교 정보" 표지판:</span> 학교 점수·순위, 기여 랭커 "정보 없음 (목업)"(M2 결정 D2), 인기 쪽지 2건과 "방명록 전체보기"<br />
              • 캠퍼스 클릭은 안내만 표시(점수·흔들림 없음), 하단 관전 바 "구경만 할 수 있어요" · "내 학교로" 버튼
            </p>
          </div>
        </div>

        {/* Transition Indicator between phones (visible on large screen) */}
        <div className="hidden lg:flex flex-col items-center justify-center self-center gap-2 -mx-2 mt-32">
          <div className="w-10 h-10 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center justify-center animate-bounce">
            <ArrowRight className="w-5 h-5 text-[#0d9488]" />
          </div>
          <span className="font-pixel text-[10px] text-[#8b5327] font-bold text-center px-1">
            방명록 게시판 터치 시<br />바텀시트 전개
          </span>
        </div>

        {/* ========================================================= */}
        {/* PHONE 2: SCREEN 2 — FULL GUESTBOOK BOTTOM SHEET (75%)     */}
        {/* ========================================================= */}
        <div className="flex flex-col items-center">
          {/* Screen 2 Title Header */}
          <div className="mb-2 flex items-center gap-2">
            <span className="w-5 h-5 bg-[#38bdf8] border-2 border-[#261608] flex items-center justify-center font-press-start text-[9px] text-[#261608] font-black shadow-[1px_1px_0px_#261608]">
              2
            </span>
            <div className="flex items-center gap-1.5">
              <Heart className="w-4 h-4 text-[#e11d48]" />
              <h2 className="font-pixel text-sm font-black text-[#261608]">
                SCREEN 2 — 전체 방명록 바텀시트 (75% 높이 전개)
              </h2>
            </div>
          </div>

          {/* Screen 2 Phone Mockup */}
          <VisitingScreen2Phone schoolId={DEMO_VISIT_SCHOOL_ID} />

          {/* Description Caption */}
          <div className="mt-2.5 p-2 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] max-w-[390px] text-left">
            <p className="font-pixel text-[10px] text-[#261608] font-bold leading-relaxed">
              • <span className="text-[#8b5327]">헤더:</span> "{demoName} 방명록", <span className="text-[#0284c7]">"인기순"(활성)</span> / "최신순" 정렬 탭, 닫기 <span className="font-bold">"▼"</span>, <span className="text-[#e11d48] font-press-start text-[8px]">SOON</span> 리본 배지<br />
              • <span className="text-[#8b5327]">스크롤 쪽지 리스트:</span> 연세대, KAIST, 전남대, 제주대, 고려대 등 7개교 쪽지 노출 (하트 터치 시 즉시 카운트 증감 및 채워진 하트 전환)<br />
              • <span className="text-[#16a34a]">하단 양피지 작성 편지함:</span> "[내 학교] · [내 닉네임] 으로 남겨요", 텍스트 입력 "한마디 남기기 (최대 50자)", 실시간 글자수 "0/50", "작성한 방명록은 삭제할 수 없어요", 그린 "남기기" 버튼 (실제 작성 가능)
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};
