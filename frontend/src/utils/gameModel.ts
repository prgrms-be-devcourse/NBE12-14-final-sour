import { CAMPUS_STAGES, CLICKERS, INITIAL_CONTRIBUTION, INITIAL_DEPARTMENT_SCORE, INITIAL_SCHOOL_SCORE, STORE_ITEMS, type MockStoreItem } from '../data/gameRules.ts';

export interface ActiveBuff {
  itemId: string;
  expiresAt: number;
}

export interface MockGameState {
  contribution: number;
  clickerId: number;
  schoolScore: number;
  departmentScore: number;
  activeBuff: ActiveBuff | null;
}

export const INITIAL_GAME_STATE: MockGameState = {
  contribution: INITIAL_CONTRIBUTION,
  clickerId: 3,
  schoolScore: INITIAL_SCHOOL_SCORE,
  departmentScore: INITIAL_DEPARTMENT_SCORE,
  activeBuff: null,
};

export type GameAction =
  | { type: 'tap'; isCrit: boolean; now: number }
  | { type: 'api-tap'; points: number }
  | { type: 'api-scores'; contribution: number; schoolScore: number; departmentScore: number }
  /** The server's own-school total from the ranking API (SCRUM-188), already including unconfirmed clicks. */
  | { type: 'api-school-score'; schoolScore: number }
  | { type: 'upgrade' }
  | { type: 'buy'; itemId: string; now: number }
  | { type: 'expire'; now: number }
  | { type: 'preview-campus'; level: number }
  /** Start over at a school (M4 D2): everything back to the start, school score from that school. */
  | { type: 'reset'; schoolScore: number }
  /**
   * The member's own values from the server (SCRUM-153). With `schoolScore` the player is at another school than
   * before, so the game starts over there as in `reset`; without it only contribution and clicker change.
   */
  | { type: 'account'; contribution: number; clickerId: number; schoolScore: number | null };

export function getClicker(id: number) {
  return CLICKERS.find((clicker) => clicker.id === id) ?? CLICKERS[0];
}

export function getNextClicker(id: number) {
  return CLICKERS[CLICKERS.findIndex((clicker) => clicker.id === id) + 1] ?? null;
}

export function getCampusStage(score: number) {
  for (let index = CAMPUS_STAGES.length - 1; index >= 0; index--) {
    if (score >= CAMPUS_STAGES[index].requiredScore) return CAMPUS_STAGES[index];
  }
  return CAMPUS_STAGES[0];
}

export function getActiveBuff(state: MockGameState, now: number, buffs: readonly MockStoreItem[] = STORE_ITEMS) {
  if (!state.activeBuff || now >= state.activeBuff.expiresAt) return null;
  return buffs.find((item) => item.id === state.activeBuff?.itemId) ?? null;
}

export function getTapPoints(state: MockGameState, isCrit: boolean, now: number, buffs: readonly MockStoreItem[] = STORE_ITEMS) {
  const buff = getActiveBuff(state, now, buffs);
  // 기존 치명타 연출은 유지한다. 버프는 일반/치명타 양쪽에 적용한다.
  const power = getClicker(state.clickerId).power * (isCrit ? 2 : 1);
  return Math.round(power * (buff?.multiplier ?? 1) + (buff?.bonus ?? 0));
}

export function gameReducer(state: MockGameState, action: GameAction, buffs: readonly MockStoreItem[] = STORE_ITEMS, catalog: readonly MockStoreItem[] = STORE_ITEMS): MockGameState {
  switch (action.type) {
    case 'api-tap':
      return {
        ...state,
        contribution: state.contribution + action.points,
        schoolScore: state.schoolScore + action.points,
        departmentScore: state.departmentScore + action.points,
      };
    case 'api-scores':
      return { ...state, contribution: action.contribution, schoolScore: action.schoolScore, departmentScore: action.departmentScore };
    case 'api-school-score':
      return state.schoolScore === action.schoolScore ? state : { ...state, schoolScore: action.schoolScore };
    case 'tap': {
      const points = getTapPoints(state, action.isCrit, action.now, buffs);
      const buff = getActiveBuff(state, action.now, buffs);
      return {
        ...state,
        contribution: state.contribution + points,
        schoolScore: state.schoolScore + points,
        departmentScore: state.departmentScore + points,
        activeBuff: !buff || buff.singleUse ? null : state.activeBuff,
      };
    }
    case 'upgrade': {
      const current = getClicker(state.clickerId);
      const next = getNextClicker(state.clickerId);
      if (!next || current.upgradeCost === null || state.contribution < current.upgradeCost) return state;
      return { ...state, contribution: state.contribution - current.upgradeCost, clickerId: next.id };
    }
    case 'buy': {
      const item = catalog.find((entry) => entry.id === action.itemId);
      if (!item || item.id !== 'coffee' || item.previewOnly || state.contribution < item.cost) return state;
      return {
        ...state,
        contribution: state.contribution - item.cost,
        activeBuff: { itemId: item.id, expiresAt: action.now + item.durationMs },
      };
    }
    case 'expire':
      return state.activeBuff && action.now >= state.activeBuff.expiresAt ? { ...state, activeBuff: null } : state;
    case 'preview-campus': {
      const stage = CAMPUS_STAGES.find((entry) => entry.level === action.level);
      // 명시적인 목업 시연 제어. 개인 기여도를 바꾸거나 소비하지 않는다.
      return stage ? { ...state, schoolScore: stage.requiredScore } : state;
    }
    case 'reset':
      return { ...INITIAL_GAME_STATE, schoolScore: action.schoolScore };
    case 'account': {
      const base = action.schoolScore === null ? state : { ...INITIAL_GAME_STATE, schoolScore: action.schoolScore };
      return { ...base, contribution: action.contribution, clickerId: action.clickerId };
    }
    default: {
      const unsupported: never = action;
      throw new Error(`지원하지 않는 목업 동작: ${JSON.stringify(unsupported)}`);
    }
  }
}
