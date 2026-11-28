import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import type { SchoolId } from './data/schoolIds';
import { TopHUD } from './components/TopHUD';
import { CampusView } from './components/CampusView';
import { useCampusTopDepartment } from './hooks/useCampusTopDepartment';
import { LibraryBonusControl } from './components/LibraryBonusControl';
import { PhoneShellVariantContext } from './components/PhoneShell';
import { GameScreenLayout } from './components/GameScreenLayout';
import { useDeviceMode, useGameLayout } from './hooks/useDeviceMode';
import { ApiEntry } from './components/ApiEntry';
import { GameWorkspace, WorkspaceShortcut } from './components/GameWorkspace';
import { GameSchoolPanel, LazyApiChat, type SchoolPanelTab } from './components/GameSchoolPanel';
import { GameCompetitionPanel, type CompetitionTab } from './components/GameCompetitionPanel';
import { GameAllianceFlow, type AllianceScreen } from './components/GameAllianceFlow';
import { GameProfileBar } from './components/GameProfileBar';
import { AllianceNavProvider, BottomNav, NavTab, type AllianceNavValue } from './components/BottomNav';
import { RankingsView } from './components/RankingsView';
import { ShopView } from './components/ShopView';
import { CampusMapView } from './components/CampusMapView';
import { OnboardingFlow } from './components/OnboardingFlow';
import { SpriteSheetViewer } from './components/SpriteSheetViewer';
import { RankingScreenPhone } from './components/RankingScreenPhone';
import { TwoPhoneMapComparison } from './components/TwoPhoneMapComparison';
import { MapIllustrationViewer } from './components/MapIllustrationViewer';
import { MapScreenPhone, INITIAL_MAP_CONTEXT, type MapContext } from './components/MapScreenPhone';
import { VisitCampusScreen } from './components/VisitingScreen1Phone';
import { useVisitSchool } from './hooks/useVisitSchool';
import { VisitingScreen2Phone } from './components/VisitingScreen2Phone';
import { OnboardingPhone } from './components/onboarding/OnboardingPhone';
import { ApiSignupPhone } from './components/onboarding/ApiSignupPhone';
import { ApiAccountNavLabel, ApiLoginPhone } from './components/onboarding/ApiLoginPhone';
import { ApiPlayerSync } from './components/ApiPlayerSync';
import { ApiSchoolScoreSync } from './components/ApiSchoolScoreSync';
import { API_MODE, selectByMode } from './api/mode';
import { useMySchool } from './hooks/useSchoolViews';
import { TwoPhoneChatComparison } from './components/TwoPhoneChatComparison';
import { TwoPhoneVisitingComparison } from './components/TwoPhoneVisitingComparison';
import { TwoPhoneOurSchoolGuestbookComparison } from './components/TwoPhoneOurSchoolGuestbookComparison';
import { TwoPhoneRankingComparison } from './components/TwoPhoneRankingComparison';
import { OurSchoolGuestbookBottomSheet } from './components/OurSchoolGuestbookBottomSheet';
import { ApiGuestbookBottomSheet } from './components/ApiGuestbookBottomSheet';
import { ChatPreviewBar } from './components/ChatPreviewBar';
import { ChatBottomSheet } from './components/ChatBottomSheet';
import { BottomSheetDialog } from './components/BottomSheetDialog';
import { BotCheckDialog } from './components/BotCheckDialog';
import { ApiBotCheckDialog } from './components/ApiBotCheckDialog';
import { SupportGuestScreenPhone } from './components/SupportGuestScreenPhone';
import { SupportMemberScreenPhone } from './components/SupportMemberScreenPhone';
import { MyInfoScreenPhone } from './components/MyInfoScreenPhone';
import { AdminScreenPhone } from './components/AdminScreenPhone';
import { ApiTestToolsNotice } from './components/AdminTestToolsPanel';
import { DEMO_SCHOOL_NAMES, type DemoSchoolNameId } from './data/demoSchoolNames';
import { PurchaseHistoryScreenPhone } from './components/PurchaseHistoryScreenPhone';
import { CampusStatsSheet } from './components/CampusStatsSheet';
import { useMySchoolRank } from './hooks/useMySchoolRank';
import { MockGameProvider, useMockGame } from './hooks/useMockGame';
import { useCampusClicks } from './hooks/useCampusClicks';
import { useCampusAttacks } from './hooks/useCampusAttacks';
import { AttackNotice } from './components/VisitAttackBar';
import { ClickerProvider, useClickerController } from './hooks/useClicker';
import { ItemShopProvider, useItemShopController } from './hooks/useItemShop';
import { UniversityActivityContext, useApiUniversityActivitySource, type UniversityActivitySource } from './hooks/useUniversityActivity';
import { useMockRequest } from './hooks/useMockRequest';
import { MOCK_REQUEST_MODES, type MockRequestMode } from './utils/mockRequest';
import { MockLoadGate } from './components/MockLoadGate';
import { AllianceProvider, useAlliance } from './hooks/useMockAlliance';
import { BattleProvider, useBattle } from './hooks/useMockBattle';
import { CampusAmbienceProvider, useAmbienceControl } from './hooks/useCampusTimePhase';
import type { AmbienceOverride } from './data/campusAmbience';
import { soundManager } from './utils/sound';
import { MOCK_TOOLS } from './utils/mockTools';
import { Smartphone, Gamepad2, Layers, Trophy, ShoppingBag, MapPin, Image as ImageIcon, Compass, MessageSquare, BookOpen, Sparkles } from 'lucide-react';

/** Service flow screens (M5-D). Demo tools and design comparisons are left out of the 44px touch areas. */
const SERVICE_VIEW_MODES = new Set<string>(['gameplay', 'signup', 'login', 'single_map', 'visiting', 'ranking', 'shop', 'alliance_apply', 'current_alliance', 'battle_apply', 'battle_status', 'support_guest', 'my_info', 'purchase_history', 'member_support', 'admin']);
/** Sign-up: the backend in API mode (SCRUM-151), the mock onboarding otherwise. */
const SignupScreen = selectByMode<React.FC<{ onGoToCampus: () => void; onGoToLogin?: () => void }>>({ api: ApiSignupPhone, mock: OnboardingPhone });
type OtherView = AllianceScreen | 'ranking_screens' | 'our_guestbook_screens' | 'visiting_screens' | 'chat_screens' | 'single_map'
  | 'map_screens' | 'map_illustration' | 'ranking' | 'spritesheet' | 'onboarding' | 'signup' | 'login' | 'gameplay'
  | 'support_guest' | 'my_info' | 'purchase_history' | 'member_support' | 'admin';
type GameView = ({ mode: 'visiting'; schoolId: SchoolId }
  | { mode: 'shop'; schoolId: SchoolId | null } // Retain the campus under the shop, irrespective of viewport.
  | { mode: OtherView; schoolId: null }) & { focusCenter?: boolean };

const useActivitySource = selectByMode<(myUniversityId: number | null) => UniversityActivitySource | null>({
  api: useApiUniversityActivitySource,
  mock: () => null,
});

function AppContent() {
  const [view, setView] = useState<GameView>({ mode: 'gameplay', schoolId: null, focusCenter: API_MODE === 'api' });
  const { mode: viewMode, schoolId: visitSchoolId } = view;
  const setViewMode = useCallback((mode: OtherView) => {
    if (MOCK_TOOLS || SERVICE_VIEW_MODES.has(mode)) setView({ mode, schoolId: null });
  }, []);
  const [isChatSheetOpen, setIsChatSheetOpen] = useState<boolean>(false);
  const [isOurGuestbookSheetOpen, setIsOurGuestbookSheetOpen] = useState<boolean>(false);
  const [isStatsSheetOpen, setIsStatsSheetOpen] = useState<boolean>(false);
  const [schoolPanelTab, setSchoolPanelTab] = useState<SchoolPanelTab>('detail');
  const [competitionTab, setCompetitionTab] = useState<CompetitionTab>('map');
  const [purchaseConfirming, setPurchaseConfirming] = useState(false);
  const [isMockMenuOpen, setIsMockMenuOpen] = useState<boolean>(false);
  const [mapContext, setMapContext] = useState<MapContext>(INITIAL_MAP_CONTEXT);
  const [isVisitGuestbookOpen, setIsVisitGuestbookOpen] = useState<boolean>(false);

  const ambienceControl = useAmbienceControl();
  // T2 (plan §5): on a phone the service screens fill the viewport; demo comparisons keep their frames and scroll.
  const isPhone = useDeviceMode() === 'full';
  const gameLayout = useGameLayout();
  const isServiceView = SERVICE_VIEW_MODES.has(viewMode);
  const compact = isPhone || gameLayout === 'compact';
  const wideCampus = gameLayout === 'wide' && (viewMode === 'gameplay' || viewMode === 'visiting' || viewMode === 'shop');
  const backdrop = compact && isServiceView ? 'device-full-with-profile min-h-dvh bg-[#f1f5f9]' : 'game-backdrop';
  const visit = useVisitSchool(visitSchoolId);
  const shellVariant = compact && isServiceView ? 'full' : 'framed';
  const viewWrap = shellVariant === 'full' ? 'w-full flex flex-col' : 'w-full flex flex-col items-center py-2 animate-fadeIn';
  const game = useMockGame();
  const campusClicks = useCampusClicks();
  const campusAttacks = useCampusAttacks(campusClicks.beginUpgrade);
  // API mode: campus and visit screens poll their school's previous-second activity (mock mode polls the mock source).
  const activitySource = useActivitySource(campusAttacks?.myUniversityId ?? null);
  const clicker = useClickerController(campusClicks);
  const itemShop = useItemShopController(campusClicks);
  const request = useMockRequest();
  const playerInfo = game.player;
  const schoolScore = game.state.schoolScore;
  const mySchool = useMySchool();
  // The school signboard shows the school's first-ranked department (user request 2026-11-27).
  const campusSign = useCampusTopDepartment(mySchool.id);
  // API mode: the server's rank and change against 00:00 KST (SCRUM-184). The score above follows the server's total
  // through ApiSchoolScoreSync and click answers (SCRUM-188).
  const schoolRanking = useMySchoolRank(schoolScore, playerInfo.schoolId);
  const { rank, change: rankDelta } = schoolRanking;
  const campusLevel = game.campus.level;
  const myContribution = game.state.contribution;
  const [activeStudents, setActiveStudents] = useState<number>(37);
  const [activeTab, setActiveTab] = useState<Exclude<NavTab, 'shop'>>('campus');
  const centerTab = wideCampus ? 'campus' : activeTab;
  const buying = itemShop?.pending || request.isPending('shop-buy');
  let visitBlocked: string | undefined;
  if (buying) visitBlocked = '아이템 구매 처리가 끝난 뒤 방문할 수 있습니다.';
  else if (viewMode === 'shop' && purchaseConfirming) visitBlocked = '아이템 구매 확인을 닫은 뒤 방문할 수 있습니다.';

  const centerMain = useRef<HTMLElement>(null);
  useEffect(() => {
    if (view.focusCenter) centerMain.current?.focus();
  }, [view]);

  // A mobile dialog and a wide panel never mount the same chat or guestbook together.
  useEffect(() => {
    if (!wideCampus) return;
    setIsChatSheetOpen(false);
    setIsOurGuestbookSheetOpen(false);
    setIsStatsSheetOpen(false);
    setIsVisitGuestbookOpen(false);
  }, [wideCampus]);

  const goToMySchool = () => {
    setIsVisitGuestbookOpen(false);
    setActiveTab('campus');
    setView({ mode: 'gameplay', schoolId: null, focusCenter: true });
  };
  const goToMap = () => {
    if (wideCampus) { setCompetitionTab('map'); return; }
    setIsVisitGuestbookOpen(false);
    setViewMode('single_map');
  };
  const openShop = () => {
    if (viewMode === 'shop') return;
    setIsVisitGuestbookOpen(false);
    setActiveTab('campus');
    setView({ mode: 'shop', schoolId: visitSchoolId, focusCenter: true });
  };
  const closeShop = () => {
    if (visitSchoolId === null) goToMySchool();
    else setView({ mode: 'visiting', schoolId: visitSchoolId, focusCenter: true });
  };
  const openGameTab = (tab: NavTab) => {
    if (tab === 'shop') openShop();
    else if (wideCampus && tab !== 'campus') setCompetitionTab(tab);
    else setActiveTab(tab);
  };
  // Visit events are never queued. The purchase controllers already own the in-flight mutation.
  const openSchool = (schoolId: SchoolId) => {
    if (visitBlocked) return;
    if (schoolId === playerInfo.schoolId) {
      goToMySchool();
      return;
    }
    setActiveTab('campus');
    setIsVisitGuestbookOpen(false);
    setView({ mode: 'visiting', schoolId, focusCenter: true });
  };
  // Bottom-nav tabs from service screens outside the main game (visit, alliance·battle — T4, plan §7).
  const navigateTab = (tab: NavTab) => {
    if (tab === 'campus') goToMySchool();
    else if (tab === 'map') goToMap();
    else if (tab === 'rankings') {
      if (wideCampus) setCompetitionTab('rankings');
      else setViewMode('ranking');
    }
    else if (tab === 'shop') openShop();
  };
  const navigateShopTab = (tab: NavTab) => {
    if (tab === 'campus') closeShop();
    else if (tab !== 'shop') navigateTab(tab);
  };
  const [soundEnabled, setSoundEnabled] = useState(() => soundManager.enabled);
  // [시연] header school name length check. In API mode the admin test tool sets it (SCRUM-175).
  const [demoSchoolNameId, setDemoSchoolNameId] = useState<DemoSchoolNameId>('default');
  const schoolNameTest = { value: demoSchoolNameId, onChange: setDemoSchoolNameId };
  const demoSchoolName = DEMO_SCHOOL_NAMES.find((d) => d.id === demoSchoolNameId)!.name ?? mySchool.name;

  // 기존 피버 연출은 유지하되 별도 EXP는 생성하지 않는다.
  const [_feverGauge, setFeverGauge] = useState<number>(35);
  const [isFever, setIsFever] = useState<boolean>(false);
  const shopItems = game.storeItems;
  const basePower = clicker.normalPower;
  const clickPower = clicker.criticalPower;

  const handleToggleSound = () => {
    const next = !soundEnabled;
    setSoundEnabled(next);
    soundManager.enabled = next;
  };

  // Fluctuating active student ticker count (no idle/auto income added to score)
  useEffect(() => {
    const teamInterval = setInterval(() => {
      const flu = Math.floor(Math.random() * 9) - 4;
      setActiveStudents((prev) => Math.max(30, Math.min(48, prev + flu)));
    }, 4500);

    return () => clearInterval(teamInterval);
  }, []);

  useEffect(() => {
    if (isFever) {
      const feverTimer = setTimeout(() => {
        setIsFever(false);
        setFeverGauge(0);
      }, 6000);
      return () => clearTimeout(feverTimer);
    } else {
      const decayTimer = setInterval(() => {
        setFeverGauge((prev) => Math.max(0, prev - 2));
      }, 800);
      return () => clearInterval(decayTimer);
    }
  }, [isFever]);

  const handleCampusTap = useCallback(
    (_x: number, _y: number, isCrit: boolean, _gainedPoints: number) => {
      const outcome = campusClicks.tap(isCrit);
      // A refused click (M5-A) adds nothing, not even to the fever gauge.
      if (outcome === null) return null;

      if (!isFever) {
        setFeverGauge((prev) => {
          const next = prev + 5;
          if (next >= 100) {
            setIsFever(true);
            soundManager.playFeverSound();
            return 100;
          }
          return next;
        });
      }
      return outcome;
    },
    [campusClicks.tap, isFever]
  );

  const alliance = useAlliance();
  const battle = useBattle();
  const hasAllianceAlert =
    alliance.receivedRequests.some((r) => r.status === 'pending')
    || battle.pendingBattles.some((b) => battle.myAllianceId !== null && b.recipientAllianceId === battle.myAllianceId)
    || battle.isMyAllianceOccupied;
  const allianceNav = useMemo<AllianceNavValue>(
    () => ({ open: () => {
      if (wideCampus) setCompetitionTab('alliance');
      else setViewMode('current_alliance');
    }, hasAlert: hasAllianceAlert }),
    [wideCampus, hasAllianceAlert, setViewMode],
  );

  const hasAffordableShopItem = (itemShop
    ? itemShop.items.some((item) => item.payType === 'CONTRIBUTION' && item.type === 'MULTIPLIER' && myContribution >= item.price)
    : shopItems.some((item) => !item.previewOnly && myContribution >= item.cost))
    || (clicker.upgradeCost !== null && myContribution >= clicker.upgradeCost);

  // The launchers and settings mount only while the tool sheet is open.
  const viewMenus = MOCK_TOOLS && isMockMenuOpen && (
    <div className="flex flex-col items-start gap-2">
      <nav aria-label="서비스 흐름" className="flex items-center gap-1.5 flex-wrap">
        <span className="font-pixel text-[10px] font-black text-[#15803d]">서비스 흐름</span>
        <button
          onClick={() => {
            setViewMode('gameplay');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'gameplay' ? 'plaque-green' : 'plaque-wood'
          }`}
        >
          <Gamepad2 className="w-3.5 h-3.5 text-white" />
          <span>🎮 메인 게임 화면</span>
        </button>

        <button
          onClick={() => {
            setViewMode('signup');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'signup' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <Smartphone className="w-3.5 h-3.5 text-[#261608]" />
          <span>📱 순차 온보딩 (Single Phone)</span>
        </button>

        {/* Login exists only with the backend (SCRUM-152); the mock has no accounts. */}
        {API_MODE === 'api' && (
          <button
            onClick={() => {
              setViewMode('login');
              soundManager.playTabSound();
            }}
            className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
              viewMode === 'login' ? 'plaque-yellow' : 'plaque-parchment'
            }`}
          >
            <ApiAccountNavLabel />
          </button>
        )}

        <button
          onClick={() => {
            setViewMode('single_map');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'single_map' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <Compass className="w-3.5 h-3.5 text-[#261608]" />
          <span>🗺️ 단일 폰 지도</span>
        </button>

        <button
          onClick={() => {
            setViewMode('ranking');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'ranking' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <Trophy className="w-3.5 h-3.5 text-[#261608]" />
          <span>🏆 랭킹 화면 (Single Phone)</span>
        </button>

        <button
          onClick={() => {
            openShop();
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'shop' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <ShoppingBag className="w-3.5 h-3.5 text-[#261608]" />
          <span>🏪 상점 & 강화 (Single Phone)</span>
        </button>

        <button
          onClick={() => {
            setViewMode('alliance_apply');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'alliance_apply' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <span>🤝 연합 신청 (Alliance Apply)</span>
        </button>

        <button
          onClick={() => {
            setViewMode('current_alliance');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'current_alliance' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <span>🛡️ 현재 연합 (Current Alliance)</span>
        </button>

        <button
          onClick={() => {
            setViewMode('battle_apply');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'battle_apply' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <span>⚔️ 대결 신청 (Battle Apply)</span>
        </button>

        <button
          onClick={() => {
            setViewMode('battle_status');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'battle_status' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <span>📊 대결 현황 (Battle Status)</span>
        </button>

        {/* SCRUM-122: guest inquiry with the bot check. */}
        <button
          onClick={() => {
            setViewMode('support_guest');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'support_guest' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <span>📮 비회원 문의</span>
        </button>
      </nav>
      <nav aria-label="[시연] 시안 비교·도구" className="flex items-center gap-1.5 flex-wrap">
        <span className="font-pixel text-[10px] font-black text-[#8b5327]">[시연] 시안 비교·도구</span>
        <button
          onClick={() => {
            setViewMode('ranking_screens');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'ranking_screens' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <Trophy className="w-3.5 h-3.5 text-[#d97706]" />
          <span>🏆 2-Phone 순위 비교 (무한 스크롤)</span>
        </button>

        <button
          onClick={() => {
            setViewMode('our_guestbook_screens');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'our_guestbook_screens' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <Sparkles className="w-3.5 h-3.5 text-[#e11d48]" />
          <span>📜 2-Phone 우리 방명록 (Screen 1 & 2)</span>
        </button>

        <button
          onClick={() => {
            setViewMode('visiting_screens');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'visiting_screens' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <BookOpen className="w-3.5 h-3.5 text-[#0d9488]" />
          <span>📖 2-Phone 방문교 방명록</span>
        </button>

        <button
          onClick={() => {
            setViewMode('chat_screens');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'chat_screens' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <MessageSquare className="w-3.5 h-3.5 text-[#261608]" />
          <span>💬 2-Phone 채팅 비교</span>
        </button>

        <button
          onClick={() => {
            setViewMode('map_screens');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'map_screens' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <MapPin className="w-3.5 h-3.5 text-[#261608]" />
          <span>📱📱 2-Phone 지도 비교</span>
        </button>

        <button
          onClick={() => {
            setViewMode('map_illustration');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'map_illustration' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <ImageIcon className="w-3.5 h-3.5 text-[#261608]" />
          <span>🖼️ 배경 맵 일러스트 (1080x1600)</span>
        </button>

        <button
          onClick={() => {
            setViewMode('spritesheet');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'spritesheet' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <Layers className="w-3.5 h-3.5 text-[#261608]" />
          <span>🏛️ 10단계 캠퍼스 성장 시트 (5x2)</span>
        </button>

        <button
          onClick={() => {
            setViewMode('onboarding');
            soundManager.playTabSound();
          }}
          className={`pixel-plaque px-2.5 py-1.5 text-xs font-pixel font-bold flex items-center gap-1.5 cursor-pointer ${
            viewMode === 'onboarding' ? 'plaque-yellow' : 'plaque-parchment'
          }`}
        >
          <Smartphone className="w-3.5 h-3.5 text-[#261608]" />
          <span>3단 온보딩 화면</span>
        </button>
      </nav>
    </div>
  );
  const demoTools = MOCK_TOOLS && isMockMenuOpen && (
    <section aria-label="[시연] 도구" className="mb-3 flex flex-wrap items-center justify-center gap-3 border-2 border-dashed border-[#8b5327] bg-[#fffbeb] px-3 py-1.5">
      <span className="font-pixel text-xs font-black text-[#8b5327]">[시연] 도구</span>
      <label className="font-pixel text-xs font-bold">[시연] 시간대{' '}
        <select aria-label="[시연] 시간대" value={ambienceControl?.override ?? 'auto'} onChange={(e) => ambienceControl?.setOverride(e.target.value as AmbienceOverride)} className="border-2 border-[#261608] bg-white px-2 py-1">
          <option value="auto">자동</option><option value="dawn">새벽</option><option value="day">낮</option><option value="dusk">노을</option><option value="night">밤</option>
        </select>
      </label>
      {/* API mode: the admin test tool (SCRUM-175) takes over the school name, request mode and click refusal. */}
      {API_MODE === 'mock' && <>
        <label className="font-pixel text-xs font-bold">[시연] 학교명{' '}
          <select aria-label="[시연] 학교명" value={demoSchoolNameId} onChange={(e) => setDemoSchoolNameId(e.target.value as DemoSchoolNameId)} className="border-2 border-[#261608] bg-white px-2 py-1">
            {DEMO_SCHOOL_NAMES.map((d) => <option key={d.id} value={d.id}>{d.label}</option>)}
          </select>
        </label>
        {/* M5-B: how the next mock requests (auth, shop, ranking/map/visit queries) end. */}
        <label className="font-pixel text-xs font-bold">[시연] 요청{' '}
          <select aria-label="[시연] 요청 상태" value={request.mode} onChange={(e) => request.setMode(e.target.value as MockRequestMode)} className="border-2 border-[#261608] bg-white px-2 py-1">
            {MOCK_REQUEST_MODES.map((option) => <option key={option.mode} value={option.mode}>{option.label}</option>)}
          </select>
        </label>
        {/* M5-A: every click on my campus is refused while this is on. */}
        <label className="font-pixel text-xs font-bold flex items-center gap-1">
          <input type="checkbox" checked={game.clickRejectDemo} onChange={(e) => game.setClickRejectDemo(e.target.checked)} />
          [시연] 서버 클릭 거절 응답
        </label>
      </>}
      {/* SCRUM-121: stands in for the server's abuse flag. A click while on opens the bot check. */}
      <label className="font-pixel text-xs font-bold flex items-center gap-1">
        <input type="checkbox" checked={game.abuseFlagged} onChange={(e) => game.setAbuseFlagged(e.target.checked)} />
        [시연] 어뷰징 의심 표시 (봇 확인 요구)
      </label>
    </section>
  );

  return (
    <ClickerProvider value={clicker}>
    <ItemShopProvider value={itemShop}>
    <UniversityActivityContext.Provider value={activitySource}>
    <AllianceNavProvider value={allianceNav}>
      <div data-desktop-game={wideCampus ? '' : undefined} className={`w-full flex flex-col items-center justify-start select-none text-[#261608] ${backdrop}`}>
        <GameProfileBar mode={API_MODE} nickname={playerInfo.nickname} onCampus={goToMySchool} onNavigate={setViewMode}
          tools={MOCK_TOOLS && <button type="button" aria-haspopup="dialog" className="game-workspace-action shrink-0"
            onClick={(event) => { event.currentTarget.focus(); setIsMockMenuOpen(true); soundManager.playTabSound(); }}>테스트 도구</button>} />
        {API_MODE === 'api' && <ApiPlayerSync getPendingScore={campusClicks.getPendingScore} />}
        {API_MODE === 'api' && <ApiSchoolScoreSync getPendingScore={campusClicks.getPendingScore} isSending={campusClicks.isSending} />}
        {API_MODE === 'api' && <ApiTestToolsNotice schoolName={schoolNameTest} />}
        {/* The attack bar already shows pending work. Do not shift the campus under repeated taps with a lock banner. */}
        {campusClicks.notice && !(viewMode === 'visiting' && campusAttacks?.pendingClicks) && (
          <div role="alert" className="w-full max-w-3xl border-2 border-[#8b5327] bg-[#fffbeb] p-3 text-sm font-pixel">
            <p>{campusClicks.notice}</p>
            {campusClicks.retryLimited && (
              <button type="button" onClick={campusClicks.retryLimited} className="pixel-plaque plaque-yellow min-h-12 mt-2 px-3 py-2 text-xs font-bold cursor-pointer">거절된 배치를 제외하고 클릭 재개</button>
            )}
            {campusClicks.botCheck && (
              <button type="button" onClick={campusClicks.botCheck.onOpen} className="pixel-plaque plaque-yellow min-h-12 mt-2 px-3 py-2 text-xs font-bold cursor-pointer">자동 입력 방지 확인</button>
            )}
          </div>
        )}
        <AttackNotice attacks={campusAttacks} />
        {MOCK_TOOLS && isMockMenuOpen && (
          <BottomSheetDialog label="테스트 도구" onClose={() => setIsMockMenuOpen(false)}>
            {/* Choosing a screen closes the sheet; changing demo settings keeps it open. */}
            <div className="p-3 flex flex-col gap-3" onClick={(e) => { if ((e.target as Element).closest('nav button')) setIsMockMenuOpen(false); }}>
              {viewMenus}
              {demoTools}
            </div>
          </BottomSheetDialog>
        )}

        {/* Main Content Area based on viewMode. Service screens get 44px touch areas (src/index.css). */}
        <PhoneShellVariantContext.Provider value={shellVariant}>
        <div className="contents" data-touch-area={SERVICE_VIEW_MODES.has(viewMode) ? '44' : undefined}>
        <GameWorkspace wide={wideCampus}
          left={<GameSchoolPanel tab={schoolPanelTab} onTabChange={setSchoolPanelTab} mySchool={mySchool} ranking={schoolRanking}
            visit={visitSchoolId === null ? null : visit} onGoToMap={goToMap} onGoToShop={openShop} />}
          right={<GameCompetitionPanel tab={competitionTab} onTabChange={setCompetitionTab}
            mapContext={mapContext} onMapContextChange={setMapContext} schoolScore={schoolScore} visitBlocked={visitBlocked}
            onVisit={openSchool} onGoToLogin={() => setViewMode('login')} />}
        >
        {viewMode === 'ranking_screens' ? (
          <TwoPhoneRankingComparison
            schoolScore={schoolScore}
            onNavigateTab={(tab) => {
              if (tab === 'campus') {
                setViewMode('gameplay');
              } else if (tab === 'map') {
                setViewMode('map_screens');
              } else if (tab === 'shop') {
                openShop();
              }
            }}
          />
        ) : viewMode === 'our_guestbook_screens' ? (
          <TwoPhoneOurSchoolGuestbookComparison
            schoolScore={schoolScore}
            rank={rank ?? undefined}
            rankDelta={rankDelta ?? undefined}
            campusLevel={campusLevel}
            clickPower={clickPower}
            basePower={basePower}
            activeStudents={activeStudents}
            onCampusTap={handleCampusTap}
            onNavigateTab={(tab) => {
              if (tab === 'campus') {
                setViewMode('gameplay');
              } else if (tab === 'map') {
                setViewMode('map_screens');
              } else if (tab === 'rankings') {
                setViewMode('ranking');
              } else if (tab === 'shop') {
                openShop();
              }
            }}
            onGoToMap={() => setViewMode('map_screens')}
          />
        ) : viewMode === 'visiting_screens' ? (
          <TwoPhoneVisitingComparison
            onBackToMySchool={goToMySchool}
            onNavigateTab={navigateTab}
          />
        ) : viewMode === 'chat_screens' ? (
          <TwoPhoneChatComparison
            schoolScore={schoolScore}
            rank={rank ?? undefined}
            rankDelta={rankDelta ?? undefined}
            campusLevel={campusLevel}
            clickPower={clickPower}
            basePower={basePower}
            activeStudents={activeStudents}
            onCampusTap={handleCampusTap}
            onNavigateTab={(tab) => {
              if (tab === 'campus') {
                setViewMode('gameplay');
              } else if (tab === 'map') {
                setViewMode('map_screens');
              } else if (tab === 'rankings') {
                setViewMode('ranking');
              } else if (tab === 'shop') {
                openShop();
              }
            }}
          />
        ) : viewMode === 'visiting' ? (
          <div className={wideCampus ? 'h-full' : viewWrap}>
            {!wideCampus && isVisitGuestbookOpen && (
              <VisitingScreen2Phone modal schoolId={visitSchoolId} onCloseGuestbook={() => setIsVisitGuestbookOpen(false)}
                onGoToLogin={() => { setIsVisitGuestbookOpen(false); setViewMode('login'); }} />
            )}
            <MockLoadGate key={`visit:${visitSchoolId ?? ''}`} name="방문 학교" variant={wideCampus ? 'panel' : 'phone'} onBack={goToMap} backLabel="지도로 돌아가기">
              <VisitCampusScreen
                schoolId={visitSchoolId}
                visit={visit}
                embedded={wideCampus}
                mainRef={centerMain}
                attacks={campusAttacks}
                onBackToMap={goToMap}
                onBackToMySchool={goToMySchool}
                onNavigateTab={navigateTab}
                onOpenDetails={wideCampus ? () => setSchoolPanelTab('detail') : undefined}
                onOpenGuestbook={() => wideCampus ? setSchoolPanelTab('guestbook') : setIsVisitGuestbookOpen(true)}
                soundEnabled={soundEnabled}
                onToggleSound={handleToggleSound}
              />
            </MockLoadGate>
          </div>
        ) : viewMode === 'single_map' ? (
          <div className={viewWrap}>
            {visitBlocked && <p role="status" className="rpg-box px-3 py-2 text-sm font-pixel">{visitBlocked}</p>}
            <MockLoadGate key="map" name="학교 지도" onBack={goToMySchool}>
              <MapScreenPhone
                context={mapContext}
                onContextChange={setMapContext}
                onSelectSchool={openSchool}
                onNavigateTab={(tab) => {
                  if (tab === 'campus') {
                    setActiveTab('campus');
                    setViewMode('gameplay');
                  } else if (tab === 'rankings') {
                    setViewMode('ranking');
                  } else if (tab === 'shop') {
                    openShop();
                  }
                }}
              />
            </MockLoadGate>
          </div>
        ) : viewMode === 'map_illustration' ? (
          <MapIllustrationViewer
            onGoToMapScreen={() => setViewMode('single_map')}
          />
        ) : viewMode === 'map_screens' ? (
          <TwoPhoneMapComparison
            onSelectSchool={openSchool}
            onNavigateTab={(tab) => {
              if (tab === 'campus') {
                setActiveTab('campus');
                setViewMode('gameplay');
              } else if (tab === 'rankings') {
                setViewMode('ranking');
              } else if (tab === 'shop') {
                openShop();
              }
            }}
            onGoToMySchool={() => {
              setActiveTab('campus');
              setViewMode('gameplay');
            }}
          />
        ) : viewMode === 'shop' ? (
          <div className={wideCampus ? 'h-full' : viewWrap}>
            <ShopView embedded={wideCampus} mainRef={centerMain} onBackToCampus={closeShop}
              onNavigateTab={navigateShopTab} onConfirmationChange={setPurchaseConfirming} />
          </div>
        ) : viewMode === 'ranking' ? (
          <div className={viewWrap}>
            <MockLoadGate key="ranking" name="순위" onBack={goToMySchool}>
              <RankingScreenPhone
                currentSchoolScore={schoolScore} onVisitSchool={openSchool} visitBlocked={visitBlocked}
                onGoToLogin={() => setViewMode('login')}
                onNavigateTab={(tab) => {
                  if (tab === 'campus') {
                    setActiveTab('campus');
                    setViewMode('gameplay');
                  } else if (tab === 'shop') {
                    openShop();
                  } else if (tab === 'map') {
                    goToMap();
                  }
                }}
              />
            </MockLoadGate>
          </div>
        ) : viewMode === 'alliance_apply' || viewMode === 'current_alliance' || viewMode === 'battle_apply' || viewMode === 'battle_status' ? (
          <div className={viewWrap}>
            <GameAllianceFlow screen={viewMode} onScreenChange={setViewMode} onNavigateTab={navigateTab}
              onBack={() => setViewMode('gameplay')} onGoToLogin={() => setViewMode('login')} />
          </div>
        ) : viewMode === 'support_guest' ? (
          <div className={viewWrap}>
            <SupportGuestScreenPhone onBack={goToMySchool} />
          </div>
        ) : viewMode === 'my_info' ? (
          <div className={viewWrap}>
            <MyInfoScreenPhone onBack={() => setViewMode('login')} onGoToLogin={() => setViewMode('login')}
              onOpenPurchaseHistory={() => setViewMode('purchase_history')} onOpenSupports={() => setViewMode('member_support')}
              onOpenAdmin={() => setViewMode('admin')} />
          </div>
        ) : viewMode === 'purchase_history' ? (
          <div className={viewWrap}>
            <PurchaseHistoryScreenPhone onBack={() => setViewMode('my_info')} onGoToLogin={() => setViewMode('login')} />
          </div>
        ) : viewMode === 'member_support' ? (
          <div className={viewWrap}>
            <SupportMemberScreenPhone onBack={() => setViewMode('my_info')} onGoToLogin={() => setViewMode('login')} />
          </div>
        ) : viewMode === 'admin' ? (
          <div className={viewWrap}>
            <AdminScreenPhone onBack={() => setViewMode('my_info')} onGoToLogin={() => setViewMode('login')} schoolName={schoolNameTest} />
          </div>
        ) : viewMode === 'spritesheet' ? (
          <SpriteSheetViewer
            currentLevel={campusLevel}
            onSelectStageForGame={(level) => {
              game.previewCampus(level);
            }}
            onGoToGame={() => setViewMode('gameplay')}
          />
        ) : viewMode === 'onboarding' ? (
          <OnboardingFlow onStartSequential={() => setViewMode('signup')} />
        ) : viewMode === 'signup' ? (
          <SignupScreen onGoToCampus={goToMySchool} onGoToLogin={() => setViewMode('login')} />
        ) : viewMode === 'login' ? (
          <ApiLoginPhone onGoToSignup={() => setViewMode('signup')} onGoToCampus={goToMySchool} onOpenMyInfo={() => setViewMode('my_info')} />
        ) : (
          <div className={wideCampus ? 'h-full' : viewWrap}>
            <GameScreenLayout
              kind="my-campus"
              embedded={wideCampus}
              mainRef={centerMain}
              fillMain={centerTab === 'campus'}
              header={
                <TopHUD
                  schoolName={demoSchoolName}
                  schoolId={mySchool.id}
                  schoolBadgeColor={mySchool.badgeColor}
                  schoolInitial={demoSchoolNameId === 'default' ? mySchool.firstSyllable : undefined}
                  schoolRegion={`${mySchool.city} · ${mySchool.filterRegion}`}
                  schoolScore={schoolScore}
                  rank={rank}
                  rankDelta={rankDelta}
                  campusLevel={campusLevel}
                  soundEnabled={soundEnabled}
                  onToggleSound={handleToggleSound}
                  onOpenRankings={() => openGameTab('rankings')}
                  onOpenMap={() => openGameTab('map')}
                />
              }
              previewBar={<>
                {centerTab === 'campus' && campusClicks.library && <LibraryBonusControl control={campusClicks.library} />}
                {!wideCampus && <ChatPreviewBar
                  latestSender={API_MODE === 'api' ? '교내 채팅' : '새벽코딩러'}
                  latestMessage={API_MODE === 'api' ? '열어서 대화하기' : '도서관 앞 클릭 모임 ㄱㄱ'}
                  unreadCount={API_MODE === 'api' ? 0 : 12}
                  onClick={() => {
                    setIsChatSheetOpen(true);
                    soundManager.playTabSound();
                  }}
                />}
              </>}
              nav={wideCampus ? (
                <nav aria-label="캠퍼스 메뉴" className="game-center-nav">
                  <WorkspaceShortcut icon="campus" current onClick={() => setActiveTab('campus')}>내 캠퍼스</WorkspaceShortcut>
                  <WorkspaceShortcut icon="shop" onClick={openShop}>상점</WorkspaceShortcut>
                </nav>
              ) : (
                <BottomNav
                  activeTab={activeTab}
                  onChangeTab={openGameTab}
                  hasShopUpgrade={hasAffordableShopItem}
                  hasRankChange={rankDelta?.startsWith('▲') ?? false}
                />
              )}
              overlays={
                !wideCampus && <>
                  {/* Service sheets use the native top layer; short viewports must not clip controls. */}
                  {isChatSheetOpen && (
                    <BottomSheetDialog label="채팅" onClose={() => {
                      setIsChatSheetOpen(false);
                      soundManager.playTabSound();
                    }}>
                      {API_MODE === 'api' ? <React.Suspense fallback={<p role="status" className="p-3 font-pixel text-sm">채팅 화면을 여는 중이에요.</p>}>
                        <LazyApiChat
                          onClose={() => { setIsChatSheetOpen(false); soundManager.playTabSound(); }}
                          onGoToLogin={() => { setIsChatSheetOpen(false); setViewMode('login'); }} />
                      </React.Suspense> : <ChatBottomSheet
                        onClose={() => {
                          setIsChatSheetOpen(false);
                          soundManager.playTabSound();
                        }}
                        mySchoolInitial={mySchool.firstSyllable}
                        myNickname={playerInfo.nickname}
                        myDepartment={playerInfo.department}
                      />}
                    </BottomSheetDialog>
                  )}

                  {isStatsSheetOpen && (
                    <BottomSheetDialog label="상세 스탯" onClose={() => {
                      setIsStatsSheetOpen(false);
                      soundManager.playTabSound();
                    }}>
                      <CampusStatsSheet onGoToShop={() => {
                        setIsStatsSheetOpen(false);
                        openShop();
                        soundManager.playTabSound();
                      }} />
                    </BottomSheetDialog>
                  )}

                  {isOurGuestbookSheetOpen && (
                    <BottomSheetDialog label="우리 학교 방명록" onClose={() => {
                      setIsOurGuestbookSheetOpen(false);
                      soundManager.playTabSound();
                    }}>
                      {API_MODE === 'api' ? <ApiGuestbookBottomSheet onClose={() => {
                        setIsOurGuestbookSheetOpen(false);
                        soundManager.playTabSound();
                      }} onGoToMap={() => {
                        setIsOurGuestbookSheetOpen(false);
                        setActiveTab('map');
                      }} onGoToLogin={() => { setIsOurGuestbookSheetOpen(false); setViewMode('login'); }} /> : <OurSchoolGuestbookBottomSheet onClose={() => {
                        setIsOurGuestbookSheetOpen(false);
                        soundManager.playTabSound();
                      }} onGoToMap={() => {
                        setIsOurGuestbookSheetOpen(false);
                        setActiveTab('map');
                      }} mySchoolId={mySchool.id} schoolName={mySchool.name} />}
                    </BottomSheetDialog>
                  )}
                </>
              }
            >
              {centerTab === 'campus' && (
                <>
                  <CampusView
                    sign={campusSign}
                    guestbookBadge={API_MODE === 'api' ? null : 'N 5'}
                    onTap={handleCampusTap}
                    clickPower={clickPower}
                    basePower={basePower}
                    activeStudents={activeStudents}
                    onOpenGuestbook={() => {
                      if (wideCampus) setSchoolPanelTab('guestbook');
                      else setIsOurGuestbookSheetOpen(true);
                      soundManager.playTabSound();
                    }}
                    showGuestbookBoard={true}
                    onOpenStats={() => wideCampus ? setSchoolPanelTab('detail') : setIsStatsSheetOpen(true)}
                    tapPrompt={wideCampus ? '클릭!' : '터치!'}
                    fill
                  />

                </>
              )}

              {centerTab === 'rankings' && (
                <MockLoadGate name="순위" variant="panel" onBack={() => setActiveTab('campus')} backLabel="캠퍼스로">
                  <RankingsView
                    currentSchoolScore={schoolScore} onVisitSchool={openSchool} visitBlocked={visitBlocked}
                    onGoToLogin={() => setViewMode('login')}
                    onBackToCampus={() => setActiveTab('campus')}
                  />
                </MockLoadGate>
              )}

              {centerTab === 'map' && (
                <CampusMapView
                  currentLevel={campusLevel}
                  currentScore={schoolScore}
                  onBackToCampus={() => setActiveTab('campus')}
                  onOpenKoreaMap={goToMap}
                />
              )}
            </GameScreenLayout>
          </div>
        )}
        </GameWorkspace>
        </div>
        </PhoneShellVariantContext.Provider>
        {/* SCRUM-121: one bot check for every screen that sends my campus clicks. */}
        {API_MODE === 'mock' && game.botCheckOpen && <BotCheckDialog onVerified={game.completeBotCheck} onClose={game.closeBotCheck} />}
        {campusClicks.botCheck?.open && <ApiBotCheckDialog onVerified={campusClicks.botCheck.onVerified} onClose={campusClicks.botCheck.onClose} />}
        {campusAttacks?.botCheck?.open && <ApiBotCheckDialog onVerified={campusAttacks.botCheck.onVerified} onClose={campusAttacks.botCheck.onClose} />}
      </div>
    </AllianceNavProvider>
    </UniversityActivityContext.Provider>
    </ItemShopProvider>
    </ClickerProvider>
  );
}

function CampusGame() {
  return (
    <CampusAmbienceProvider>
      <AllianceProvider>
        <BattleProvider>
          <AppContent />
        </BattleProvider>
      </AllianceProvider>
    </CampusAmbienceProvider>
  );
}

function ApiApp() {
  return <ApiEntry><MockGameProvider><CampusGame /></MockGameProvider></ApiEntry>;
}

export default function App() {
  return API_MODE === 'api' ? <ApiApp /> : <CampusGame />;
}
