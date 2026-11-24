import type { SchoolId } from '../data/schoolIds.ts';
import React from 'react';
import { MapScreenPhone } from './MapScreenPhone';
import { NavTab } from './BottomNav';
import { useSchoolViews } from '../hooks/useSchoolViews.ts';
import { countByRegion, filterSchools, getTopSchools } from '../data/schoolDirectory.ts';
import { Sparkles, ArrowRight, Compass, ZoomIn } from 'lucide-react';

interface TwoPhoneMapComparisonProps {
  onNavigateTab: (tab: NavTab) => void;
  onGoToMySchool?: () => void;
  onSelectSchool?: (schoolId: SchoolId) => void;
}

export const TwoPhoneMapComparison: React.FC<TwoPhoneMapComparisonProps> = ({
  onNavigateTab,
  onGoToMySchool,
  onSelectSchool,
}) => {
  const schools = useSchoolViews();
  const seoulCount = countByRegion(schools).get('서울') ?? 0;
  const mySchool = schools.find((school) => school.isMySchool);
  const topNames = getTopSchools(schools, 3).map((school) => school.shortName).join(', ');
  const chungcheong = filterSchools(schools, '충청', '');
  const chungcheongTop = chungcheong[0];
  return (
    <div className="w-full flex flex-col items-center py-2 animate-fadeIn select-none">
      {/* Top Banner Explaining the 2 Phone Mockups */}
      <div className="w-full max-w-5xl flex flex-col sm:flex-row items-center justify-between gap-2 mb-4 px-2">
        <div className="flex items-center gap-2">
          <span className="font-pixel text-[11px] text-white bg-[#0284c7] border border-[#261608] px-2 py-0.5 shadow-[1px_1px_0px_#261608] font-bold">
            2-PHONE SIDE BY SIDE MOCKUP
          </span>
          <span className="font-pixel text-xs text-[#8b5327] font-bold">
            1) 전국 뷰 (권역별 군집 9개 & TOP 3) ➔ 2) 충청 권역 확대 뷰 ({chungcheong.length}개교 개별 핀)
          </span>
        </div>

        <div className="flex items-center gap-1.5 text-[10px] font-pixel text-[#8b5327]">
          <Sparkles className="w-3.5 h-3.5 text-[#facc15]" />
          <span>동일한 16비트 도트 맵 & 동일한 UI 프레임 유지</span>
        </div>
      </div>

      {/* 2 Phone Screens Side by Side */}
      <div className="w-full max-w-5xl flex flex-col lg:flex-row items-center lg:items-start justify-center gap-6 lg:gap-8">
        {/* ========================================================= */}
        {/* PHONE 1: SCREEN 1 — NATIONAL VIEW (ZOOMED OUT)            */}
        {/* ========================================================= */}
        <div className="flex flex-col items-center">
          {/* Screen 1 Title Header */}
          <div className="mb-2 flex items-center gap-2">
            <span className="w-5 h-5 bg-[#facc15] border-2 border-[#261608] flex items-center justify-center font-press-start text-[9px] text-[#261608] font-black shadow-[1px_1px_0px_#261608]">
              1
            </span>
            <div className="flex items-center gap-1.5">
              <Compass className="w-4 h-4 text-[#0284c7]" />
              <h2 className="font-pixel text-sm font-black text-[#261608]">
                SCREEN 1 — 전국 지도 시작 화면 (National View, 처음엔 전체 활성)
              </h2>
            </div>
          </div>

          {/* Screen 1 Phone Mockup */}
          <MapScreenPhone initialRegion="전체" onNavigateTab={onNavigateTab} onSelectSchool={onSelectSchool} />

          {/* Description Caption */}
          <div className="mt-2.5 p-2 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] max-w-[380px] text-center">
            <p className="font-pixel text-[10px] text-[#261608] font-bold leading-relaxed">
              • 개별 대학 핀 없이 권역별 9개 라운드 버블(학교 수는 데이터에서 계산) 표시<br />
              • TOP 3({topNames}) 순위 리본 & 점수 배너<br />
              • 서울 {seoulCount} 버블: 옐로우 펄싱 링 & <span className="text-[#e11d48]">"내 학교 {mySchool?.shortName} · {mySchool?.rank}위"</span> 태그<br />
              • 하단 힌트: "지역을 눌러 대학을 확인하세요" · 양피지 시트: 전체 대학 {schools.length}개
            </p>
          </div>
        </div>

        {/* Transition Indicator between phones (visible on large screen) */}
        <div className="hidden lg:flex flex-col items-center justify-center self-center gap-2 -mx-2 mt-20">
          <div className="w-9 h-9 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center justify-center">
            <ArrowRight className="w-4 h-4 text-[#0284c7]" />
          </div>
          <span className="font-pixel text-[9px] text-[#8b5327] font-bold writing-mode-vertical">
            충청 탭 터치 시
          </span>
        </div>

        {/* ========================================================= */}
        {/* PHONE 2: SCREEN 2 — REGION VIEW (CHUNGCHEONG ZOOMED IN)   */}
        {/* ========================================================= */}
        <div className="flex flex-col items-center">
          {/* Screen 2 Title Header */}
          <div className="mb-2 flex items-center gap-2">
            <span className="w-5 h-5 bg-[#38bdf8] border-2 border-[#261608] flex items-center justify-center font-press-start text-[9px] text-[#261608] font-black shadow-[1px_1px_0px_#261608]">
              2
            </span>
            <div className="flex items-center gap-1.5">
              <ZoomIn className="w-4 h-4 text-[#16a34a]" />
              <h2 className="font-pixel text-sm font-black text-[#261608]">
                SCREEN 2 — 권역 지도 시작 화면 (Region View, 처음엔 충청 확대)
              </h2>
            </div>
          </div>

          {/* Screen 2 Phone Mockup */}
          <MapScreenPhone initialRegion="충청" onNavigateTab={onNavigateTab} onSelectSchool={onSelectSchool} />

          {/* Description Caption */}
          <div className="mt-2.5 p-2 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] max-w-[380px] text-center">
            <p className="font-pixel text-[10px] text-[#261608] font-bold leading-relaxed">
              • 충청 권역 도트 맵 확대 & "충청" 칩 활성화<br />
              • 충청 {chungcheong.length}개교 개별 핀<br />
              • 확대 스케일 분산 배치 & 밀집 학교 인출선(Leader line) 적용으로 라벨 겹침 제로<br />
              • 전국 TOP 3 학교에 순위 리본 · 하단 시트: 충청 대학 {chungcheong.length}개 (1위 {chungcheongTop?.shortName})
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};
