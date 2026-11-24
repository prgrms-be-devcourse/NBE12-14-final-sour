import React from 'react';
import { OurSchoolGuestbookScreen1Phone } from './OurSchoolGuestbookScreen1Phone';
import { OurSchoolGuestbookScreen2Phone } from './OurSchoolGuestbookScreen2Phone';
import { NavTab } from './BottomNav';
import { Sparkles, ArrowRight, MessageSquare, Heart, Compass, BookOpen } from 'lucide-react';

interface TwoPhoneOurSchoolGuestbookComparisonProps {
  schoolScore?: number;
  rank?: number;
  rankDelta?: string;
  campusLevel?: number;
  clickPower?: number;
  basePower?: number;
  activeStudents?: number;
  onCampusTap?: (x: number, y: number, isCrit: boolean, gainedPoints: number) => number | null | void;
  onNavigateTab?: (tab: NavTab) => void;
  onGoToMap?: () => void;
}

export const TwoPhoneOurSchoolGuestbookComparison: React.FC<TwoPhoneOurSchoolGuestbookComparisonProps> = ({
  schoolScore = 1333872,
  rank = 7,
  rankDelta = '▲2',
  campusLevel = 4,
  clickPower = 36,
  basePower = 18,
  activeStudents = 37,
  onCampusTap = () => {},
  onNavigateTab = () => {},
  onGoToMap = () => {},
}) => {
  return (
    <div className="w-full flex flex-col items-center py-2 animate-fadeIn select-none">
      {/* Top Banner Explaining the 2 Phone Mockups */}
      <div className="w-full max-w-5xl flex flex-col sm:flex-row items-center justify-between gap-2 mb-4 px-2">
        <div className="flex items-center gap-2">
          <span className="font-pixel text-[11px] text-white bg-[#8b5327] border border-[#261608] px-2 py-0.5 shadow-[1px_1px_0px_#261608] font-bold">
            2-PHONE SIDE BY SIDE MOCKUP
          </span>
          <span className="font-pixel text-xs text-[#8b5327] font-bold">
            SCREEN 1 (캠퍼스 내 방명록 진입점) ➔ SCREEN 2 (75% 우리 학교 방명록 바텀시트)
          </span>
        </div>

        <div className="flex items-center gap-3 text-[10px] font-pixel text-[#8b5327]">
          <div className="flex items-center gap-1">
            <span className="bg-[#e11d48] text-white font-press-start text-[7px] px-1 py-0.2 border border-[#261608]">
              N 5
            </span>
            <span>캠퍼스 월드 내 일체형 오브젝트</span>
          </div>
          <div className="flex items-center gap-1">
            <Heart className="w-3.5 h-3.5 text-[#e11d48] fill-[#e11d48]" />
            <span>타교생 응원 메시지 & 좋아요</span>
          </div>
        </div>
      </div>

      {/* 2 Phone Screens Side by Side */}
      <div className="w-full max-w-5xl flex flex-col lg:flex-row items-center lg:items-start justify-center gap-6 lg:gap-8">
        {/* ========================================================= */}
        {/* PHONE 1: SCREEN 1 — MAIN SCREEN WITH GUESTBOOK ENTRY POINT*/}
        {/* ========================================================= */}
        <div className="flex flex-col items-center">
          {/* Screen 1 Title Header */}
          <div className="mb-2 flex items-center gap-2">
            <span className="w-5 h-5 bg-[#facc15] border-2 border-[#261608] flex items-center justify-center font-press-start text-[9px] text-[#261608] font-black shadow-[1px_1px_0px_#261608]">
              1
            </span>
            <div className="flex items-center gap-1.5">
              <BookOpen className="w-4 h-4 text-[#8b5327]" />
              <h2 className="font-pixel text-sm font-black text-[#261608]">
                SCREEN 1 — 메인 화면 (캠퍼스 내 방명록 진입점)
              </h2>
            </div>
          </div>

          {/* Screen 1 Phone Mockup */}
          <OurSchoolGuestbookScreen1Phone
            schoolScore={schoolScore}
            rank={rank}
            rankDelta={rankDelta}
            campusLevel={campusLevel}
            clickPower={clickPower}
            basePower={basePower}
            activeStudents={activeStudents}
            onCampusTap={onCampusTap}
            onNavigateTab={onNavigateTab}
          />

          {/* Description Caption */}
          <div className="mt-2.5 p-2 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] max-w-[390px] text-left">
            <p className="font-pixel text-[10px] text-[#261608] font-bold leading-relaxed">
              • <span className="text-[#8b5327]">캠퍼스 좌하단 길목:</span> 나무 픽셀 게시판 오브젝트(두 다리 지지대 + 핀으로 꽂힌 쪽지 2장)<br />
              • <span className="text-[#e11d48]">상단 인디케이터:</span> 미니 태그 <span className="text-[#8b5327]">"방명록"</span> + 레드 배지 <span className="text-[#e11d48] font-press-start text-[8px]">N 5</span> & 은은한 반짝임(Sparkle)<br />
              • UI 플로팅 버튼이 아닌 캠퍼스 월드 그래픽의 일부처럼 자연스럽게 융합<br />
              • HUD, 압축 패널, 슬림 채팅 프리뷰 바("새벽코딩러: 도서관 앞 클릭 모임 ㄱㄱ"), 하단 네비게이션 무결점 유지
            </p>
          </div>
        </div>

        {/* Transition Indicator between phones (visible on large screen) */}
        <div className="hidden lg:flex flex-col items-center justify-center self-center gap-2 -mx-2 mt-32">
          <div className="w-10 h-10 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center justify-center animate-bounce">
            <ArrowRight className="w-5 h-5 text-[#8b5327]" />
          </div>
          <span className="font-pixel text-[10px] text-[#8b5327] font-bold text-center px-1">
            방명록 보드 터치 시<br />바텀시트 전개
          </span>
        </div>

        {/* ========================================================= */}
        {/* PHONE 2: SCREEN 2 — OUR SCHOOL GUESTBOOK BOTTOM SHEET     */}
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
                SCREEN 2 — 우리 학교 방명록 (75% 바텀시트)
              </h2>
            </div>
          </div>

          {/* Screen 2 Phone Mockup */}
          <OurSchoolGuestbookScreen2Phone
            schoolScore={schoolScore}
            rank={rank}
            rankDelta={rankDelta}
            campusLevel={campusLevel}
            activeStudents={activeStudents}
            onGoToMap={onGoToMap}
          />

          {/* Description Caption */}
          <div className="mt-2.5 p-2 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] max-w-[390px] text-left">
            <p className="font-pixel text-[10px] text-[#261608] font-bold leading-relaxed">
              • <span className="text-[#8b5327]">헤더:</span> "[내 학교] 방명록", "다른 학교 학생들이 남긴 응원이에요", <span className="text-[#0284c7]">"최신순"(활성)</span> / "인기순" 탭, "방명록 N개"(실제 목록 수, 내 학교 학생 글 제외), 닫기 <span className="font-bold">"▼"</span>, <span className="text-[#e11d48] font-press-start text-[8px]">SOON</span> 리본 배지<br />
              • <span className="text-[#8b5327]">나무 보드 쪽지 리스트:</span> 부산대, KAIST, 전남대, 제주대, 고려대 쪽지(내 학교 제외) + 노란색 <span className="bg-[#facc15] text-[#261608] px-1 font-bold text-[8px]">NEW</span> 태그<br />
              • 하트 좋아요(일부 채워짐) 인터랙션 지원<br />
              • <span className="text-[#8b5327]">작성창 없음:</span> 하단 대화창 <span className="text-[#8b5327]">"방명록은 다른 학교를 방문했을 때 남길 수 있어요"</span> + 링크형 <span className="text-[#ca8a04]">"지도에서 학교 찾기 &gt;"</span> 버튼
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};
