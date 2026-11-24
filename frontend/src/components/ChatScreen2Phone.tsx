import React from 'react';
import { PhoneShell } from './PhoneShell';
import { TopHUD, schoolHudProps } from './TopHUD';
import { useMySchool } from '../hooks/useSchoolViews';
import { useMockGame } from '../hooks/useMockGame.tsx';
import { CampusView } from './CampusView';
import { ChatBottomSheet } from './ChatBottomSheet';

interface ChatScreen2PhoneProps {
  schoolScore?: number;
  rank?: number;
  rankDelta?: string;
  campusLevel?: number;
  activeStudents?: number;
  onCloseChat?: () => void;
}

export const ChatScreen2Phone: React.FC<ChatScreen2PhoneProps> = ({
  schoolScore = 1333872,
  rank = 7,
  rankDelta = '▲2',
  campusLevel = 4,
  activeStudents = 37,
  onCloseChat,
}) => {
  const mySchool = useMySchool();
  const game = useMockGame();
  return (
    <PhoneShell>
      {/* Screen Container with Top 25% Dimmed Campus and Bottom 75% Chat Bottom Sheet */}
      <div className="flex-1 relative flex flex-col justify-between overflow-hidden bg-[#faecc7]/30">
        {/* Background / Dimmed Campus Portion (Top ~25% visible) */}
        <div className="absolute inset-x-0 top-0 h-[28%] p-2 overflow-hidden pointer-events-none">
          <TopHUD
            {...schoolHudProps(mySchool)}
            schoolScore={schoolScore}
            rank={rank}
            rankDelta={rankDelta}
            campusLevel={campusLevel}
            soundEnabled={true}
            onToggleSound={() => {}}
            onOpenRankings={() => {}}
            onOpenMap={() => {}}
          />
          <div className="mt-1 scale-95 opacity-60 filter blur-[0.5px]">
            <CampusView
              onTap={() => {}}
              clickPower={36}
              basePower={18}
              activeStudents={activeStudents}
            />
          </div>
        </div>

        {/* Dimming Translucent Overlay on Top Portion */}
        <div
          onClick={onCloseChat}
          title="탭하여 채팅 닫기"
          className="absolute inset-x-0 top-0 h-[25%] bg-[#1a0f05]/75 z-20 cursor-pointer flex items-end justify-center pb-2"
        >
          <span className="font-pixel text-[9px] text-[#fef08a] bg-[#261608]/80 px-2 py-0.5 border border-[#8b5327] animate-pulse">
            ▼ 터치하여 캠퍼스로 돌아가기
          </span>
        </div>

        {/* Chat Bottom Sheet (covers 75% of screen height) */}
        <div className="absolute inset-x-0 bottom-0 h-[75%] z-30">
          <ChatBottomSheet
            onClose={onCloseChat}
            mySchoolInitial={mySchool.firstSyllable}
            myNickname={game.player.nickname}
            myDepartment={game.player.department}
          />
        </div>
      </div>
    </PhoneShell>
  );
};
