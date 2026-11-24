import type { SchoolId } from '../data/schoolIds.ts';
import React from 'react';
import { PhoneShell } from './PhoneShell';
import { GuestbookBottomSheet } from './GuestbookBottomSheet';
import { ApiGuestbookBottomSheet } from './ApiGuestbookBottomSheet';
import { API_MODE } from '../api/mode';
import { BottomSheetDialog } from './BottomSheetDialog';
import { TopHUD, schoolHudProps } from './TopHUD';
import { soundManager } from '../utils/sound';
import { CampusScene } from './CampusScene';
import { VisitNotFoundPhone, useVisitedSchool } from './VisitingParts.tsx';
import { useMockGame } from '../hooks/useMockGame.tsx';
import { CampusAmbience } from './CampusAmbience';

interface VisitingScreen2PhoneProps {
  /** Visited school ID (M2). */
  /** null: no school was chosen; the screen shows the not-found notice. */
  schoolId: SchoolId | null;
  onCloseGuestbook?: () => void;
  onGoToLogin?: () => void;
  /** Actual service flow; the default remains an inline comparison phone. */
  modal?: boolean;
}

export const VisitingScreen2Phone: React.FC<VisitingScreen2PhoneProps> = ({
  schoolId,
  onCloseGuestbook = () => {},
  onGoToLogin,
  modal = false,
}) => {
  const { school, mySchool } = useVisitedSchool(schoolId);
  const game = useMockGame();
  if (!school) return <VisitNotFoundPhone schoolId={schoolId} onBackToMap={onCloseGuestbook} />;

  if (modal) return (
    <BottomSheetDialog label={`${school.name} 방명록`} onClose={onCloseGuestbook}>
      {API_MODE === 'api' ? <ApiGuestbookBottomSheet universityId={school.id} schoolName={school.name} onClose={onCloseGuestbook} onGoToLogin={onGoToLogin} /> : <GuestbookBottomSheet
        onClose={onCloseGuestbook}
        schoolId={school.id}
        schoolName={school.name}
        schoolInitial={school.firstSyllable}
        schoolBadgeColor={school.badgeColor}
        mySchoolId={mySchool.id}
        mySchoolName={mySchool.name}
        myNickname={game.player.nickname}
        mySchoolInitial={mySchool.firstSyllable}
        mySchoolBadgeColor={mySchool.badgeColor}
      />}
    </BottomSheetDialog>
  );

  return (
    <PhoneShell>
      {/* Screen Container with Top ~25% Dimmed Campus & Bottom 75% Full Guestbook Sheet */}
      <div className="flex-1 relative flex flex-col justify-between overflow-hidden bg-[#faecc7]/30">
        {/* Background / Dimmed Campus Portion (Top ~25% visible) */}
        {/* inert: the HUD's buttons here are a picture of the visit screen, not reachable by Tab either. */}
        <div inert className="absolute inset-x-0 top-0 h-[26%] overflow-hidden pointer-events-none">
          {/* Top HUD in visit mode, as on the visit screen (V3, docs/261110-2005-visit_ui_jeju_map_plan.md §4-3) */}
          <div className="p-2 pb-1">
            <TopHUD
              mode="visit"
              {...schoolHudProps(school)}
              schoolScore={school.score}
              rank={school.rank}
              rankDelta={school.rankChange}
              campusLevel={school.campusLevel}
              soundEnabled={soundManager.enabled}
              onToggleSound={() => {}}
            />
          </div>

          {/* Dimmed mini scene of the visited campus */}
          <div className="w-full h-24 relative overflow-hidden opacity-60 filter blur-[0.5px]">
            <CampusScene level={school.campusLevel} labelPrefix={school.name} className="w-full h-full" />
            <CampusAmbience level={school.campusLevel} />
          </div>
        </div>

        {/* Dimming Translucent Overlay on Top Portion */}
        <div
          onClick={onCloseGuestbook}
          title="탭하여 캠퍼스로 돌아가기"
          className="absolute inset-x-0 top-0 h-[25%] bg-[#1a0f05]/75 z-20 cursor-pointer flex items-end justify-center pb-2"
        >
          <span className="font-pixel text-[9px] text-[#fef08a] bg-[#261608]/80 px-2 py-0.5 border border-[#8b5327] animate-pulse">
            ▼ 터치하여 캠퍼스로 돌아가기
          </span>
        </div>

        {/* Full Guestbook Bottom Sheet (covers 75% of screen height) */}
        <div className="absolute inset-x-0 bottom-0 h-[75%] z-30">
          <GuestbookBottomSheet
            onClose={onCloseGuestbook}
            schoolId={school.id}
            schoolName={school.name}
            schoolInitial={school.firstSyllable}
            schoolBadgeColor={school.badgeColor}
            mySchoolId={mySchool.id}
            mySchoolName={mySchool.name}
            myNickname={game.player.nickname}
            mySchoolInitial={mySchool.firstSyllable}
            mySchoolBadgeColor={mySchool.badgeColor}
          />
        </div>
      </div>
    </PhoneShell>
  );
};
