import React from 'react';
import { PhoneShell } from './PhoneShell';
import { TopHUD, schoolHudProps } from './TopHUD';
import { useMySchool } from '../hooks/useSchoolViews';
import { CampusView } from './CampusView';
import { ChatPreviewBar } from './ChatPreviewBar';
import { BottomNav, NavTab } from './BottomNav';

interface OurSchoolGuestbookScreen1PhoneProps {
  schoolScore?: number;
  rank?: number;
  rankDelta?: string;
  campusLevel?: number;
  clickPower?: number;
  basePower?: number;
  activeStudents?: number;
  onCampusTap?: (x: number, y: number, isCrit: boolean, gainedPoints: number) => number | null | void;
  onOpenGuestbook?: () => void;
  onOpenChat?: () => void;
  onNavigateTab?: (tab: NavTab) => void;
}

export const OurSchoolGuestbookScreen1Phone: React.FC<OurSchoolGuestbookScreen1PhoneProps> = ({
  schoolScore = 1333872,
  rank = 7,
  rankDelta = '▲2',
  campusLevel = 4,
  clickPower = 36,
  basePower = 18,
  activeStudents = 37,
  onCampusTap = () => {},
  onOpenGuestbook = () => {},
  onOpenChat = () => {},
  onNavigateTab = () => {},
}) => {
  const mySchool = useMySchool();
  return (
    <PhoneShell>
      {/* Screen Content Area */}
      <div className="flex-1 p-2 flex flex-col justify-between overflow-hidden bg-[#faecc7]/30">
        {/* Top HUD */}
        <TopHUD
          {...schoolHudProps(mySchool)}
          schoolScore={schoolScore}
          rank={rank}
          rankDelta={rankDelta}
          campusLevel={campusLevel}
          soundEnabled={true}
          onToggleSound={() => {}}
          onOpenRankings={() => onNavigateTab('rankings')}
          onOpenMap={() => onNavigateTab('map')}
        />

        {/* Center: Campus View with Wooden Notice Board in Lower-Left Area */}
        <div className="my-1 flex-1 flex flex-col justify-start overflow-hidden">
          <CampusView
            onTap={onCampusTap}
            clickPower={clickPower}
            basePower={basePower}
            activeStudents={activeStudents}
            onOpenGuestbook={onOpenGuestbook}
            showGuestbookBoard={true}
          />

        </div>

        {/* Bottom Section: Slim Chat Preview Bar directly above BottomNav */}
        <div className="w-full flex flex-col gap-1.5 mt-1 shrink-0">
          <ChatPreviewBar
            latestSender="새벽코딩러"
            latestMessage="도서관 앞 클릭 모임 ㄱㄱ"
            unreadCount={12}
            onClick={onOpenChat}
          />

          <BottomNav
            activeTab="campus"
            onChangeTab={onNavigateTab}
            hasShopUpgrade={true}
            hasRankChange={true}
          />
        </div>
      </div>
    </PhoneShell>
  );
};
