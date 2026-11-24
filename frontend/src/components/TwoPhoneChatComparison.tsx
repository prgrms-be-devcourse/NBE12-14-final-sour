import React from 'react';
import { ChatScreen1Phone } from './ChatScreen1Phone';
import { ChatScreen2Phone } from './ChatScreen2Phone';
import { NavTab } from './BottomNav';
import { Sparkles, ArrowRight, MessageSquare, ShieldCheck, Flame } from 'lucide-react';

interface TwoPhoneChatComparisonProps {
  schoolScore?: number;
  rank?: number;
  rankDelta?: string;
  campusLevel?: number;
  clickPower?: number;
  basePower?: number;
  activeStudents?: number;
  onCampusTap?: (x: number, y: number, isCrit: boolean, gainedPoints: number) => number | null | void;
  onNavigateTab?: (tab: NavTab) => void;
}

export const TwoPhoneChatComparison: React.FC<TwoPhoneChatComparisonProps> = ({
  schoolScore = 1333872,
  rank = 7,
  rankDelta = '▲2',
  campusLevel = 4,
  clickPower = 36,
  basePower = 18,
  activeStudents = 37,
  onCampusTap = () => {},
  onNavigateTab = () => {},
}) => {
  return (
    <div className="w-full flex flex-col items-center py-2 animate-fadeIn select-none">
      {/* Top Banner Explaining the 2 Phone Mockups */}
      <div className="w-full max-w-5xl flex flex-col sm:flex-row items-center justify-between gap-2 mb-4 px-2">
        <div className="flex items-center gap-2">
          <span className="font-pixel text-[11px] text-white bg-[#0284c7] border border-[#261608] px-2 py-0.5 shadow-[1px_1px_0px_#261608] font-bold">
            2-PHONE SIDE BY SIDE MOCKUP
          </span>
          <span className="font-pixel text-xs text-[#8b5327] font-bold">
            SCREEN 1 (채팅 미리보기 바) ➔ SCREEN 2 (75% 바텀시트 전개)
          </span>
        </div>

        <div className="flex items-center gap-3 text-[10px] font-pixel text-[#8b5327]">
          <div className="flex items-center gap-1">
            <Sparkles className="w-3.5 h-3.5 text-[#facc15]" />
            <span>동일 픽셀아트 스타일 & HUD 유지</span>
          </div>
          <div className="flex items-center gap-1">
            <ShieldCheck className="w-3.5 h-3.5 text-[#22c55e]" />
            <span>필터링 및 클린 시스템</span>
          </div>
        </div>
      </div>

      {/* 2 Phone Screens Side by Side */}
      <div className="w-full max-w-5xl flex flex-col lg:flex-row items-center lg:items-start justify-center gap-6 lg:gap-8">
        {/* ========================================================= */}
        {/* PHONE 1: SCREEN 1 — MAIN SCREEN WITH CHAT PREVIEW         */}
        {/* ========================================================= */}
        <div className="flex flex-col items-center">
          {/* Screen 1 Title Header */}
          <div className="mb-2 flex items-center gap-2">
            <span className="w-5 h-5 bg-[#facc15] border-2 border-[#261608] flex items-center justify-center font-press-start text-[9px] text-[#261608] font-black shadow-[1px_1px_0px_#261608]">
              1
            </span>
            <div className="flex items-center gap-1.5">
              <MessageSquare className="w-4 h-4 text-[#8b5327]" />
              <h2 className="font-pixel text-sm font-black text-[#261608]">
                SCREEN 1 — 메인 화면 (채팅 미리보기 바)
              </h2>
            </div>
          </div>

          {/* Screen 1 Phone Mockup */}
          <ChatScreen1Phone
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
              • <span className="text-[#8b5327]">하단 네비 위 슬림 양피지 바:</span> 말풍선 아이콘 + 최신 메시지 <span className="text-[#0284c7]">"새벽코딩러: 도서관 앞 클릭 모임 ㄱㄱ"</span><br />
              • <span className="text-[#e11d48]">미확인 배지 "12"</span> & 우측 펼치기 <span className="font-bold">"▲"</span> 버튼<br />
              • 기여도 및 클리커 정보 패널의 내부 패딩을 조정하여 요소 누락이나 겹침 없이 1화면에 완벽 피트<br />
              • 클릭 인터랙션 및 학교 점수 획득 정상 동작
            </p>
          </div>
        </div>

        {/* Transition Indicator between phones (visible on large screen) */}
        <div className="hidden lg:flex flex-col items-center justify-center self-center gap-2 -mx-2 mt-32">
          <div className="w-10 h-10 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center justify-center animate-bounce">
            <ArrowRight className="w-5 h-5 text-[#0284c7]" />
          </div>
          <span className="font-pixel text-[10px] text-[#8b5327] font-bold text-center px-1">
            ▲ 바 터치 시<br />바텀시트 전개
          </span>
        </div>

        {/* ========================================================= */}
        {/* PHONE 2: SCREEN 2 — CHAT OPENED AS BOTTOM SHEET (75%)     */}
        {/* ========================================================= */}
        <div className="flex flex-col items-center">
          {/* Screen 2 Title Header */}
          <div className="mb-2 flex items-center gap-2">
            <span className="w-5 h-5 bg-[#38bdf8] border-2 border-[#261608] flex items-center justify-center font-press-start text-[9px] text-[#261608] font-black shadow-[1px_1px_0px_#261608]">
              2
            </span>
            <div className="flex items-center gap-1.5">
              <Flame className="w-4 h-4 text-[#e11d48]" />
              <h2 className="font-pixel text-sm font-black text-[#261608]">
                SCREEN 2 — 채팅 바텀시트 (75% 높이 전개)
              </h2>
            </div>
          </div>

          {/* Screen 2 Phone Mockup */}
          <ChatScreen2Phone
            schoolScore={schoolScore}
            rank={rank}
            rankDelta={rankDelta}
            campusLevel={campusLevel}
            activeStudents={activeStudents}
          />

          {/* Description Caption */}
          <div className="mt-2.5 p-2 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] max-w-[390px] text-left">
            <p className="font-pixel text-[10px] text-[#261608] font-bold leading-relaxed">
              • <span className="text-[#8b5327]">화면 75% 덮는 양피지 시트:</span> 상단 25% 캠퍼스 딤 처리<br />
              • <span className="text-[#e11d48] font-press-start text-[8px]">SOON</span> 리본 배지 & 2단 탭: <span className="text-[#8b5327]">"교내 채팅"(활성)</span> / "연합 채팅" & 닫기 <span className="font-bold">"▼"</span><br />
              • 상단 고정 대화창 공지: <span className="text-[#8b5327]">"욕설·비방은 자동으로 가려져요"</span><br />
              • <span className="text-[#0284c7]">상대방 메시지 (좌측 크림색)</span> vs <span className="text-[#ca8a04]">내 메시지 (우측 노란색)</span><br />
              • <span className="text-stone-500">⚠ 필터링된 메시지입니다</span> 그레이 박스 표시<br />
              • 하단 인풋: "메시지 입력 (최대 100자)" 및 노란색 픽셀 <span className="text-[#ca8a04]">전송</span> 버튼 (실제 전송 가능)
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};
