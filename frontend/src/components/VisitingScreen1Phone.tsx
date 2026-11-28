import type { SchoolId } from '../data/schoolIds.ts';
import { useEffect, useState } from 'react';
import { Trophy } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { formatContribution } from '../utils/formatContribution';
import { BottomNav, NavTab } from './BottomNav';
import { BottomSheetDialog } from './BottomSheetDialog';
import { CampusSignButton, CampusStage, GuestbookBoardButton } from './CampusStage';
import { GameScreenLayout } from './GameScreenLayout';
import { WorkspaceShortcut } from './GameWorkspace';
import { TopHUD, schoolHudProps } from './TopHUD';
import { LiveClicksEmpty, SPECTATOR_NOTICE, SpectatorBar, VisitNotFoundPhone, VisitSchoolInfoSheet, formatScore } from './VisitingParts.tsx';
import { LoadNotice } from './LoadNotice';
import { useVisitSchool, type VisitSchool } from '../hooks/useVisitSchool.ts';
import { CAMPUS_ACTION_LABEL, type CampusActionKind, type CampusAttacks } from '../hooks/useCampusAttacks';
import { VisitAttackBar } from './VisitAttackBar';
import { TapFloatingNumbers, useTapFloatingNumbers } from './TapFloatingNumbers';
import { useReducedMotion } from './CampusAmbience';
import { wobbleBuildings, wobbleCampusAttack } from '../utils/campusWobble';
import { useCampusTimePhase } from '../hooks/useCampusTimePhase';
import { useOtherClicks } from '../hooks/useMockOtherClicks';
import { MOCK_REALTIME_CONFIG } from '../data/mockRealtime';
import { useUniversityActivityFeed, useUniversityActivitySource } from '../hooks/useUniversityActivity';
import { OtherActivityParticles } from './OtherActivityParticles';

const noOwnClicks = () => 0;

interface VisitingScreen1PhoneProps {
  /** Visited school ID from the map (M2). Mock rows, or the server's school detail in API mode (SCRUM-163). */
  /** null: no school was chosen; the screen shows the not-found notice. */
  schoolId: SchoolId | null;
  onBackToMap?: () => void;
  onBackToMySchool?: () => void;
  onNavigateTab?: (tab: NavTab) => void;
  onOpenGuestbook?: () => void;
  /** In the wide workspace the campus signs open the side panel instead of a dialog. */
  onOpenDetails?: () => void;
  embedded?: boolean;
  mainRef?: React.Ref<HTMLElement>;
  /** The game's sound setting (App). Without it, as on the comparison mockup, the screen keeps its own. */
  soundEnabled?: boolean;
  onToggleSound?: () => void;
  /** API-only attack queue, kept at App root so navigation cannot retarget or drop a batch (SCRUM-168). */
  attacks?: CampusAttacks | null;
}

/**
 * Visit screen in the main game's layout (V1·V2, docs/261110-2005-visit_ui_jeju_map_plan.md §4): the same header,
 * campus frame and footer as my campus, with a "방문 중" HUD, a 학교 정보 sign where 상세 스탯 is, and the spectator
 * bar where the chat preview is. Mock/guest spectating never scores; signed-in API members attack without granting
 * contribution or consuming a mock buff (SCRUM-168), and assist a school of their current alliance instead (SCRUM-187).
 */
export function VisitingScreen1Phone(props: VisitingScreen1PhoneProps) {
  const { schoolId } = props;
  const visit = useVisitSchool(schoolId);
  return <VisitCampusScreen {...props} visit={visit} />;
}

/** The service workspace shares one visit result between this campus and the left panel. */
export function VisitCampusScreen({
  schoolId,
  visit,
  onBackToMap = () => {},
  onBackToMySchool = () => {},
  onNavigateTab = () => {},
  onOpenGuestbook = () => {},
  onOpenDetails,
  embedded = false,
  mainRef,
  soundEnabled,
  onToggleSound,
  attacks = null,
}: VisitingScreen1PhoneProps & { visit: VisitSchool }) {
  const [clickNoticeToast, setClickNoticeToast] = useState(false);
  const [isInfoOpen, setIsInfoOpen] = useState(false);
  const [localSound, setLocalSound] = useState(() => soundManager.enabled);
  const tapNumbers = useTapFloatingNumbers();
  // The visited school's first-ranked department on its signboard (server detail; mock visits have none).
  const top = visit.extras?.topDepartments.find((row) => row.rank === 1) ?? visit.extras?.topDepartments[0];
  const visitSign = top ? { name: top.name, score: top.totalScore } : null;
  const reducedMotion = useReducedMotion();
  const ambience = useCampusTimePhase();
  const { school, isMyUniversity } = visit;
  // Clicks by this school's students and the attacks and assists it received (2026-11-28): polled while this screen
  // shows it, guests and spectators included; my own attack or assist answers stand in for the poll while I tap.
  // A mock visit has no source and so no particles.
  const visitFeed = useUniversityActivityFeed(useUniversityActivitySource(), school?.id ?? null);
  const { particles: activityParticles } = useOtherClicks({ getMyClicksSinceLastTick: noOwnClicks, feed: visitFeed, mockPolling: false });
  const attackMode = !!attacks?.ready && !!school && !isMyUniversity && attacks.myUniversityId !== school.id;
  // The server decides who is an ally; an ally is assisted, never attacked.
  const actionKind: CampusActionKind = visit.isMyAlly ? 'assist' : 'attack';
  const attackBlocked = !!attacks && (attacks.blocked || (attacks.pendingTargetId !== null && attacks.pendingTargetId !== schoolId));
  // The server says this is my own school (e.g. a stale map entry): my campus is where clicks count.
  useEffect(() => {
    if (isMyUniversity) onBackToMySchool();
  }, [isMyUniversity, onBackToMySchool]);
  useEffect(() => {
    if (embedded) setIsInfoOpen(false);
  }, [embedded]);

  // Attack and assist taps show their damage like a campus click; the score itself changes on the server's answer.
  const handleActionTap = (event: React.MouseEvent<HTMLButtonElement>) => {
    if (!attacks || !school) return;
    const outcome = attacks.tap(school.id, actionKind);
    if (!outcome) return;
    if (actionKind === 'assist') soundManager.playAssistSound(outcome.isCrit);
    else soundManager.playAttackSound(outcome.isCrit);
    if (typeof navigator !== 'undefined' && navigator.vibrate) navigator.vibrate(outcome.isCrit ? 25 : 12);
    // Attack: the buildings shake left and right and the surroundings the opposite way.
    // Assist: only the buildings squash and stretch, as on a campus click.
    const stage = event.currentTarget.closest('[data-campus-stage]');
    if (actionKind === 'attack') wobbleCampusAttack(stage, reducedMotion);
    else wobbleBuildings(stage, reducedMotion);
    const rect = event.currentTarget.getBoundingClientRect();
    // A keyboard press has no pointer position (detail 0): show it in the middle.
    const x = event.detail === 0 ? rect.width / 2 : event.clientX - rect.left;
    const y = event.detail === 0 ? rect.height / 2 : event.clientY - rect.top;
    tapNumbers.add(x, y, `${actionKind === 'assist' ? '+' : '-'}${outcome.points}${outcome.isCrit ? '!' : ''}`, outcome.isCrit);
  };

  const handleCampusClick = () => {
    soundManager.playTapSound();
    setClickNoticeToast(true);
    setTimeout(() => setClickNoticeToast(false), 2000);
  };
  const toggleSound = onToggleSound ?? (() => {
    soundManager.enabled = !localSound;
    setLocalSound(!localSound);
  });
  const closeInfo = () => {
    setIsInfoOpen(false);
    soundManager.playTabSound();
  };

  if (visit.status === 'not-found') return <VisitNotFoundPhone schoolId={schoolId} onBackToMap={onBackToMap} embedded={embedded} />;
  if (visit.status !== 'ready' || !school) {
    return (
      <LoadNotice
        variant={embedded ? 'panel' : 'phone'}
        status={visit.status === 'failed' ? 'failed' : 'loading'}
        name="방문 학교"
        failureDetail={visit.failureDetail}
        onRetry={visit.retry}
        onBack={onBackToMap}
        backLabel="지도로 돌아가기"
      />
    );
  }

  return (
    <GameScreenLayout
      kind="visit"
      embedded={embedded}
      mainRef={mainRef}
      fillMain
      header={
        <TopHUD
          mode="visit"
          onBack={onBackToMap}
          {...schoolHudProps(school)}
          schoolScore={school.score}
          rank={school.rank}
          rankDelta={school.rankChange}
          campusLevel={school.campusLevel}
          soundEnabled={soundEnabled ?? localSound}
          onToggleSound={toggleSound}
          onOpenRankings={() => onNavigateTab('rankings')}
        />
      }
      previewBar={attackMode && attacks
        ? <VisitAttackBar attacks={attacks} schoolId={school.id} onBackToMySchool={onBackToMySchool} kind={actionKind} />
        : <SpectatorBar mySchoolShortName={visit.mySchoolShortName} onBackToMySchool={onBackToMySchool} />}
      nav={embedded ? (
        <nav aria-label="캠퍼스 메뉴" className="game-center-nav">
          <WorkspaceShortcut icon="shop" onClick={() => onNavigateTab('shop')}>상점</WorkspaceShortcut>
        </nav>
      ) : (
        <BottomNav
          activeTab="map"
          onChangeTab={(tab) => {
            soundManager.playTabSound();
            onNavigateTab(tab);
          }}
          hasShopUpgrade={false}
          hasRankChange={false}
        />
      )}
      overlays={
        !embedded && isInfoOpen && (
          <BottomSheetDialog label={`${school.name} 학교 정보`} onClose={closeInfo}>
            <VisitSchoolInfoSheet
              school={school}
              extras={visit.extras}
              onOpenGuestbook={() => {
                setIsInfoOpen(false);
                onOpenGuestbook();
              }}
            />
          </BottomSheetDialog>
        )
      }
    >
      <CampusStage
        level={school.campusLevel}
        sign={visitSign}
        fill
        bubble={attackMode ? <span>{CAMPUS_ACTION_LABEL[actionKind]} 모드 · 서버 응답으로 점수 반영</span> : <LiveClicksEmpty />}
        bubbleDot="#0284c7"
        sceneLabelPrefix={school.name}
        frameProps={attackMode ? undefined : { role: 'button', 'aria-label': `${school.name} 캠퍼스 (관전 모드)`, onClick: handleCampusClick }}
      >
        {attackMode && attacks && (
          <button type="button" aria-label={`${school.name} 캠퍼스 ${CAMPUS_ACTION_LABEL[actionKind]}`} disabled={attackBlocked}
            className={`absolute inset-0 z-10 ${actionKind === 'assist' ? 'cursor-pointer' : 'cursor-crosshair'} touch-manipulation focus-visible:outline-4 focus-visible:outline-offset-[-8px] focus-visible:outline-[#facc15] disabled:cursor-not-allowed`}
            onClick={handleActionTap} />
        )}
        <OtherActivityParticles particles={activityParticles} lifetimeMs={MOCK_REALTIME_CONFIG.particleLifetimeMs}
          palette={ambience.particlePalette} reducedMotion={reducedMotion} />
        {attackMode && <TapFloatingNumbers items={tapNumbers.items} />}
        {!attackMode && clickNoticeToast && (
          <div className="absolute inset-0 bg-[#261608]/50 flex items-center justify-center p-3 animate-fadeIn z-30 pointer-events-none">
            <div className="rpg-box p-2 bg-[#ffffff] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center gap-1.5">
              <span className="text-base">🚫</span>
              <span role="status" className="font-pixel text-[10px] font-bold text-[#b45309]">
                {SPECTATOR_NOTICE}
              </span>
            </div>
          </div>
        )}

        <GuestbookBoardButton ariaLabel={`${school.name} 방명록 보기`} onOpen={onOpenGuestbook} />
        <CampusSignButton
          testId="visit-info-button"
          icon={<Trophy className="w-3 h-3" aria-hidden="true" />}
          title="학교 정보"
          value={formatContribution(school.score)}
          tooltip={`학교 정보 보기 · 학교 점수 ${formatScore(school.score)}`}
          ariaLabel={`학교 정보 보기, 학교 점수 ${formatScore(school.score)}`}
          onOpen={onOpenDetails ?? (() => setIsInfoOpen(true))}
        />
      </CampusStage>
    </GameScreenLayout>
  );
}
