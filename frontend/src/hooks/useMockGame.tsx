import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { DEFAULT_PLAYER, initialSchoolScore, makeStoreItems, resolveDepartmentId, type MockPlayerInfo, type MockStoreItem } from '../data/gameRules';
import { MockAuthProvider } from './useMockAuth.tsx';
import { MockRequestProvider } from './useMockRequest.tsx';
import { TurnstileLoaderProvider } from './useTurnstileLoader.tsx';
import type { TurnstileLoader } from '../utils/turnstile.ts';
import { describeClickRejection, INITIAL_CLICK_GUARD, judgeClick, MOCK_CLICK_RULES, type ClickGuardRules, type ClickRejectReason } from '../utils/clickGuard.ts';
import type { MockRequestMode } from '../utils/mockRequest.ts';
import type { EnrolledProfile } from '../utils/mockAuth.ts';
import type { AccountProfile } from '../api/users.ts';
import { MOCK_SHOP_CONFIG, type MockShopConfig } from '../data/mockShopConfig.ts';
import { SharedImageProvider, MOCK_IMAGE_DIRECTORY, type SharedImageStore } from './useSharedImages.tsx';
import { createSharedImageStore } from '../utils/sharedImages.ts';
import { gameReducer, getActiveBuff, getCampusStage, getClicker, getNextClicker, getTapPoints, INITIAL_GAME_STATE, type GameAction, type MockGameState } from '../utils/gameModel';

function useGameController(
  config: MockShopConfig,
  initialState: MockGameState,
  extraBuffs: readonly MockStoreItem[],
  imageStore: SharedImageStore,
  clickRules: ClickGuardRules,
) {
  const [shopConfig] = useState(() => config);
  const [storeItems] = useState(() => makeStoreItems(shopConfig));
  // Non-sale engine fixtures may be supplied for regression tests; buy() uses storeItems only.
  const [engineBuffs] = useState(() => [...storeItems, ...extraBuffs]);
  const [state, setState] = useState(initialState);
  const stateRef = useRef(state);
  const [now, setNow] = useState(Date.now);
  const [player, setPlayerState] = useState<MockPlayerInfo>(DEFAULT_PLAYER);

  // Pure updater: computes and returns next state, no side effects or mutations
  const setPlayer = useCallback((update: Partial<MockPlayerInfo> | ((prev: MockPlayerInfo) => MockPlayerInfo)) => {
    setPlayerState((prev) => {
      const partial = typeof update === 'function' ? update(prev) : update;
      const schoolId = partial.schoolId || prev.schoolId;
      const departmentChanged = partial.department !== undefined && partial.department !== prev.department;
      const departmentId = (partial.departmentId && (!departmentChanged || partial.departmentId !== prev.departmentId))
        ? partial.departmentId
        : departmentChanged
        ? resolveDepartmentId(partial.department!, schoolId)
        : (partial.departmentId || prev.departmentId);

      return {
        ...prev,
        ...partial,
        userId: partial.userId || prev.userId,
        schoolId,
        departmentId,
      };
    });
  }, []);

  // Membership directory syncs outside the state updater in an effect
  useEffect(() => {
    imageStore.updateMember({
      userId: player.userId,
      schoolId: player.schoolId,
      departmentId: player.departmentId,
    });
  }, [imageStore, player.userId, player.schoolId, player.departmentId]);

  // 같은 프레임의 연속 입력도 최신 잔액을 기준으로 각각 판정한다.
  const commit = useCallback((action: GameAction) => {
    const previous = stateRef.current;
    const next = gameReducer(previous, action, engineBuffs, storeItems);
    stateRef.current = next;
    if (next !== previous) setState(next);
    return next !== previous;
  }, [engineBuffs, storeItems]);

  useEffect(() => {
    if (!state.activeBuff) return;
    const update = () => {
      const timestamp = Date.now();
      setNow(timestamp);
      commit({ type: 'expire', now: timestamp });
    };
    update();
    const timer = setInterval(update, 250);
    return () => clearInterval(timer);
  }, [state.activeBuff, commit]);

  // Mock click checks (M5-A). A refused click never reaches the reducer, so no score, buff or campus changes.
  const [guardRules] = useState(() => clickRules);
  const guardRef = useRef(INITIAL_CLICK_GUARD);
  const rejectDemoRef = useRef(false);
  const [clickRejectDemo, setClickRejectDemoState] = useState(false);
  const [clickRejection, setClickRejection] = useState<{ reason: ClickRejectReason; blockedUntil: number } | null>(null);

  const setClickRejectDemo = useCallback((on: boolean) => {
    rejectDemoRef.current = on;
    setClickRejectDemoState(on);
    if (!on) setClickRejection((current) => (current?.reason === 'forced' ? null : current));
  }, []);

  // Abuse flag and bot check (SCRUM-121). The server flags a member who passed a click limit and answers every click
  // with 403 ABUSE_VERIFICATION_REQUIRED until a bot check passes. [목업] The demo switch stands in for that flag.
  const abuseFlagRef = useRef(false);
  const [abuseFlagged, setAbuseFlaggedState] = useState(false);
  const [botCheckOpen, setBotCheckOpen] = useState(false);
  const setAbuseFlagged = useCallback((on: boolean) => {
    abuseFlagRef.current = on;
    setAbuseFlaggedState(on);
    if (!on) setBotCheckOpen(false);
  }, []);
  const closeBotCheck = useCallback(() => setBotCheckOpen(false), []);
  const completeBotCheck = useCallback(() => setAbuseFlagged(false), [setAbuseFlagged]);

  /** Returns the points added, or null when the click was refused. */
  const tap = useCallback((isCrit: boolean): number | null => {
    const timestamp = Date.now();
    setNow(timestamp);
    // A flagged click is refused before the click checks, as the server checks the flag first.
    if (abuseFlagRef.current) {
      setBotCheckOpen(true);
      return null;
    }
    const judged = judgeClick(guardRef.current, timestamp, guardRules, rejectDemoRef.current);
    guardRef.current = judged.state;
    if (!judged.accepted) {
      const { reason } = judged;
      // While blocked, keep the cause of the block in the notice.
      setClickRejection((current) => (reason === 'blocked' && current ? current : { reason, blockedUntil: judged.state.blockedUntil }));
      return null;
    }
    setClickRejection((current) => (current ? null : current));
    const points = getTapPoints(stateRef.current, isCrit, timestamp, engineBuffs);
    commit({ type: 'tap', isCrit, now: timestamp });
    return points;
  }, [commit, engineBuffs, guardRules]);

  // Count down a block and clear the notice when it ends.
  const blocking = clickRejection !== null && clickRejection.reason !== 'forced';
  useEffect(() => {
    if (!blocking) return;
    const timer = setInterval(() => {
      const timestamp = Date.now();
      setNow(timestamp);
      if (timestamp >= guardRef.current.blockedUntil) setClickRejection((current) => (current?.reason === 'forced' ? current : null));
    }, 250);
    return () => clearInterval(timer);
  }, [blocking]);
  const clickNotice = clickRejection === null
    ? null
    : describeClickRejection(clickRejection.reason, Math.max(0, Math.ceil((clickRejection.blockedUntil - now) / 1000)));

  const buy = useCallback((itemId: string) => {
    const timestamp = Date.now();
    setNow(timestamp);
    return commit({ type: 'buy', itemId, now: timestamp });
  }, [commit]);

  const upgrade = useCallback(() => commit({ type: 'upgrade' }), [commit]);
  const previewCampus = useCallback((level: number) => commit({ type: 'preview-campus', level }), [commit]);

  /**
   * Apply a finished onboarding (M4 decision D2). Another school always starts over, so one school's score is
   * never carried into another's. The same school starts over only when asked. Returns whether it started over.
   */
  const enroll = useCallback((profile: EnrolledProfile, mode: 'keep' | 'reset') => {
    const startOver = mode === 'reset' || profile.schoolId !== player.schoolId;
    if (startOver) commit({ type: 'reset', schoolScore: initialSchoolScore(profile.schoolId) });
    setPlayer({ ...profile });
    return startOver;
  }, [commit, setPlayer, player.schoolId]);
  /**
   * Apply the signed-in member's own values from GET /users/me (SCRUM-153). Another school starts over there as in
   * `enroll`; the server's contribution and clicker level replace the local ones.
   */
  const applyAccount = useCallback((account: AccountProfile) => {
    const startOver = account.schoolId !== player.schoolId;
    commit({
      type: 'account',
      contribution: account.contribution,
      clickerId: account.clickerLevel,
      schoolScore: startOver ? initialSchoolScore(account.schoolId) : null,
    });
    setPlayer({
      userId: account.userId,
      schoolId: account.schoolId,
      departmentId: resolveDepartmentId(account.department, account.schoolId),
      department: account.department,
      nickname: account.nickname,
      email: account.email,
      schoolName: account.schoolName,
    });
  }, [commit, setPlayer, player.schoolId]);
  const applyApiTap = useCallback((points: number) => commit({ type: 'api-tap', points }), [commit]);
  const applyApiScores = useCallback((scores: Pick<MockGameState, 'contribution' | 'schoolScore' | 'departmentScore'>) => {
    commit({ type: 'api-scores', ...scores });
  }, [commit]);
  const applyApiSchoolScore = useCallback((schoolScore: number) => commit({ type: 'api-school-score', schoolScore }), [commit]);
  const activeBuff = getActiveBuff(state, now, engineBuffs);

  return {
    state, player, setPlayer, enroll, applyAccount, applyApiTap, applyApiScores, applyApiSchoolScore, tap, buy, upgrade, previewCampus, shopConfig, storeItems,
    clickRejectDemo, setClickRejectDemo, clickNotice,
    abuseFlagged, setAbuseFlagged, botCheckOpen, closeBotCheck, completeBotCheck,
    imageStore,
    clicker: getClicker(state.clickerId),
    nextClicker: getNextClicker(state.clickerId),
    campus: getCampusStage(state.schoolScore),
    activeBuff,
    remainingSeconds: activeBuff && state.activeBuff ? Math.max(0, Math.ceil((state.activeBuff.expiresAt - now) / 1000)) : 0,
    normalPower: getTapPoints(state, false, now, engineBuffs),
    criticalPower: getTapPoints(state, true, now, engineBuffs),
  };
}

const MockGameContext = createContext<ReturnType<typeof useGameController> | null>(null);

export function MockGameProvider({
  children,
  config = MOCK_SHOP_CONFIG,
  initialState = INITIAL_GAME_STATE,
  extraBuffs = [],
  imageStore,
  generateAuthCode,
  clickRules = MOCK_CLICK_RULES,
  initialRequestMode,
  turnstileLoader,
}: {
  children: React.ReactNode;
  /** Turnstile script loader for the bot check (SCRUM-121). Tests pass a fake; the app loads the Cloudflare script. */
  turnstileLoader?: TurnstileLoader;
  /** Fixed verification codes for tests. The app uses random six-digit codes. */
  generateAuthCode?: () => string;
  /** Mock click limits (M5 D2). Tests may pass other values; the app uses the defaults. */
  clickRules?: ClickGuardRules;
  /** Starting demo request mode (M5 D3). The app starts in normal mode. */
  initialRequestMode?: MockRequestMode;
  config?: MockShopConfig;
  initialState?: MockGameState;
  extraBuffs?: readonly MockStoreItem[];
  imageStore?: SharedImageStore;
}) {
  const [store] = useState(() => imageStore ?? createSharedImageStore(MOCK_IMAGE_DIRECTORY));
  const game = useGameController(config, initialState, extraBuffs, store, clickRules);
  return (
    <SharedImageProvider store={store}>
      <MockGameContext.Provider value={game}>
        <MockRequestProvider initialMode={initialRequestMode}>
          <TurnstileLoaderProvider loader={turnstileLoader}>
            <MockAuthProvider generateCode={generateAuthCode}>{children}</MockAuthProvider>
          </TurnstileLoaderProvider>
        </MockRequestProvider>
      </MockGameContext.Provider>
    </SharedImageProvider>
  );
}

export function useMockGame() {
  const game = useContext(MockGameContext);
  if (!game) throw new Error('MockGameProvider 안에서 useMockGame을 사용하세요.');
  return game;
}
