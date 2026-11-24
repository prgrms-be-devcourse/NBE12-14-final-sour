// 대결 상태 모델 및 순수 전이 함수 (N4 대결 신청 및 현황)
// 기획서 4-2, 6장, 10·11장 및 팀장 범위 갱신 계획 5절 기반
import { SCHOOL_ID, type SchoolId } from './schoolIds.ts';
import { MOCK_SHOP_CONFIG, type MockBattleConfig } from './mockShopConfig.ts';
import { ALLIANCE_SIZE, isAllianceActive, type Alliance } from './allianceModel.ts';

export type BattleConfig = MockBattleConfig;

/** 'expired': 저장 상태가 아닌 실효 상태. 대기 신청이 수락 기한(희망 시작 − 최소 리드)을 넘긴 경우 */
export type BattleStatus = 'pending' | 'scheduled' | 'active' | 'finished' | 'rejected' | 'cancelled' | 'expired';

export interface BattleScore {
  readonly requesterScore: number;
  readonly recipientScore: number;
}

export interface Battle {
  readonly id: string;
  readonly requesterAllianceId: string;
  readonly recipientAllianceId: string;
  readonly requesterSchoolIds: readonly SchoolId[]; // 참여 학교 ID 스냅샷
  readonly recipientSchoolIds: readonly SchoolId[]; // 참여 학교 ID 스냅샷
  readonly durationHours: number;
  readonly requestedStartTime: number;
  readonly status: BattleStatus;
  readonly createdAt: number;
  readonly scheduledStartTime?: number;
  readonly scheduledEndTime?: number;
  readonly scores?: BattleScore;
  readonly winnerAllianceId?: string | 'draw';
  readonly updatedAt: number;
}

export interface BattleActionResult {
  readonly success: boolean;
  readonly error?: string;
  readonly battle?: Battle;
}

export interface BattleState {
  readonly battles: readonly Battle[];
  readonly simulateRequestFailure: boolean;
  readonly simulateLoadFailure: boolean;
  readonly config: BattleConfig;
}

/** 대기 신청을 수락할 수 있는 마지막 시각. 이 시각이 지나면 실효 상태가 'expired'다. */
export function getBattleAcceptDeadline(battle: Battle, config: BattleConfig = MOCK_SHOP_CONFIG.battle): number {
  return battle.requestedStartTime - config.startTime.minLeadMinutes * 60_000;
}

// 고정 시계 및 기간 경과에 따른 실효적 상태 계산
// 종료 시각이 지난 경기는 어떤 경우에도 active로 표시되지 않고 finished로 판정된다.
// 수락 기한이 지난 대기 신청은 expired로 판정된다. 연합 만료는 따로 보지 않는다: 신청·수락 때
// '종료 ≤ 양측 연합 만료'를 검증하므로, 연합이 만료됐다면 그 대기 신청의 수락 기한도 이미 지났다.
export function getEffectiveBattleStatus(battle: Battle, now: number, config: BattleConfig = MOCK_SHOP_CONFIG.battle): BattleStatus {
  if (battle.status === 'pending') {
    return now > getBattleAcceptDeadline(battle, config) ? 'expired' : 'pending';
  }
  if (battle.status === 'scheduled') {
    if (battle.scheduledEndTime != null && now >= battle.scheduledEndTime) {
      return 'finished';
    }
    if (battle.scheduledStartTime != null && now >= battle.scheduledStartTime) {
      return 'active';
    }
    return 'scheduled';
  }
  if (battle.status === 'active') {
    if (battle.scheduledEndTime != null && now >= battle.scheduledEndTime) {
      return 'finished';
    }
    return 'active';
  }
  return battle.status;
}

// P4-2: 결정론적 시각 포맷팅 함수 (KST UTC+9 기준 고정 변환, 로케일/타임존 독립)
export function formatBattleTimestamp(timestamp?: number): string {
  if (timestamp == null || !Number.isFinite(timestamp)) return '-';
  const kstOffsetMs = 9 * 3600_000;
  const d = new Date(timestamp + kstOffsetMs);
  const month = String(d.getUTCMonth() + 1).padStart(2, '0');
  const day = String(d.getUTCDate()).padStart(2, '0');
  const hours = String(d.getUTCHours()).padStart(2, '0');
  const minutes = String(d.getUTCMinutes()).padStart(2, '0');
  return `${month}/${day} ${hours}:${minutes}`;
}

const KST_OFFSET = 9 * 3600_000;
const DAY_MS = 24 * 3600_000;

export function formatKstDateTimeLocal(timestamp: number): string {
  if (!Number.isFinite(timestamp)) return '';
  const d = new Date(timestamp + KST_OFFSET);
  return Number.isNaN(d.getTime()) ? '' : d.toISOString().slice(0, 16);
}

export function parseKstDateTimeLocal(value: string): number {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return NaN;
  const timestamp = Date.parse(`${value}:00Z`) - KST_OFFSET;
  return formatKstDateTimeLocal(timestamp) === value ? timestamp : NaN;
}

export function validateRequestedStartTime(time: number, now: number, config: BattleConfig): BattleActionResult {
  const { minLeadMinutes, maxLeadDays, allowedStartHoursKst, stepMinutes } = config.startTime;
  if (!Number.isFinite(time)) return { success: false, error: '유효한 시작 일시를 입력해주세요.' };
  if (time < now + minLeadMinutes * 60_000) return { success: false, error: `최소 ${minLeadMinutes}분 이후로 선택해주세요.` };
  if (time > now + maxLeadDays * DAY_MS) return { success: false, error: `최대 ${maxLeadDays}일 이내로 선택해주세요.` };
  const local = new Date(time + KST_OFFSET);
  const minutes = local.getUTCHours() * 60 + local.getUTCMinutes();
  if (minutes < allowedStartHoursKst.from * 60 || minutes > allowedStartHoursKst.to * 60) return { success: false, error: '허용된 KST 시작 시간대를 선택해주세요.' };
  if (minutes % stepMinutes !== 0 || local.getUTCSeconds() !== 0 || local.getUTCMilliseconds() !== 0) return { success: false, error: `${stepMinutes}분 단위로 선택해주세요.` };
  return { success: true };
}

/**
 * 기본 희망 시작 시각: 허용 시간대의 가장 빠른 슬롯. latestEnd(두 연합 중 빠른 만료)를 주면
 * 그 슬롯에서 durationHours만큼 진행해도 latestEnd 안에 끝나야 한다. 더 늦은 슬롯은 더 늦게 끝나므로
 * 첫 슬롯이 넘치면 가능한 슬롯이 없다(NaN).
 */
export function getDefaultRequestedStartTime(now: number, config: BattleConfig, latestEnd?: number, durationHours?: number): number {
  const first = getFirstRequestedStartSlot(now, config);
  if (latestEnd != null && Number.isFinite(first) && first + (durationHours ?? config.durationHours.min) * HOUR_MS > latestEnd) return NaN;
  return first;
}

function getFirstRequestedStartSlot(now: number, config: BattleConfig): number {
  const earliest = now + config.startTime.minLeadMinutes * 60_000;
  const localDay = Math.floor((earliest + KST_OFFSET) / DAY_MS) * DAY_MS - KST_OFFSET;
  const step = config.startTime.stepMinutes * 60_000;
  for (let day = 0; day <= config.startTime.maxLeadDays + 1; day++) {
    const start = localDay + day * DAY_MS + config.startTime.allowedStartHoursKst.from * 3600_000;
    const end = localDay + day * DAY_MS + config.startTime.allowedStartHoursKst.to * 3600_000;
    const first = Math.max(start, earliest);
    const hour = Math.ceil((first + KST_OFFSET) / 3600_000) * 3600_000 - KST_OFFSET;
    if (hour <= end && validateRequestedStartTime(hour, now, config).success) return hour;
    const aligned = Math.ceil((first + KST_OFFSET) / step) * step - KST_OFFSET;
    if (aligned <= end && validateRequestedStartTime(aligned, now, config).success) return aligned;
  }
  return NaN;
}

const HOUR_MS = 3600_000;

/** 대결은 두 연합 중 빠른 만료 시각 전에 끝나야 한다. */
export function getBattleLatestEnd(a: Alliance, b: Alliance): number {
  return Math.min(a.expiresAt, b.expiresAt);
}

/** 시작 시각과 연합 만료 상한으로 정해지는 최대 대결 기간(시간). config.min보다 작으면 이 시작 시각으로는 신청할 수 없다. */
export function getMaxBattleDurationHours(startTime: number, latestEnd: number, config: BattleConfig): number {
  return Math.min(config.durationHours.max, Math.floor((latestEnd - startTime) / HOUR_MS));
}

/** 신청·수락 공통: 양측 연합이 활성이고, 대결이 빠른 만료 전에 끝나는지 검사한다. */
export function getBattleAllianceWindowError(
  reqAlliance: Alliance,
  recAlliance: Alliance,
  startTime: number,
  durationHours: number,
  now: number,
): string | undefined {
  if (!isAllianceActive(reqAlliance, now)) return `만료된 연합: 신청 연합(${reqAlliance.name})의 기간이 끝났습니다.`;
  if (!isAllianceActive(recAlliance, now)) return `만료된 연합: 상대 연합(${recAlliance.name})의 기간이 끝났습니다.`;
  const latestEnd = getBattleLatestEnd(reqAlliance, recAlliance);
  const end = startTime + durationHours * HOUR_MS;
  if (end > latestEnd) {
    return `연합 만료 초과: 대결 종료(${formatBattleTimestamp(end)})가 두 연합 중 빠른 만료(${formatBattleTimestamp(latestEnd)}) KST보다 늦습니다.`;
  }
  return undefined;
}

// P4-2: 실효적 상태에 따른 남은 시간/일정 안내 레이블 순수 함수
export function formatBattleRemainingTime(battle: Battle, now: number, config: BattleConfig = MOCK_SHOP_CONFIG.battle): string {
  const effStatus = getEffectiveBattleStatus(battle, now, config);
  if (effStatus === 'finished') {
    return '종료됨';
  }
  if (effStatus === 'scheduled') {
    if (battle.scheduledStartTime == null) return '시작 대기';
    const diff = battle.scheduledStartTime - now;
    if (diff <= 0) return '시작 직전';
    const totalMinutes = Math.floor(diff / 60_000);
    const hours = Math.floor(totalMinutes / 60);
    const mins = totalMinutes % 60;
    return `시작까지 ${hours}시간 ${mins}분 남음`;
  }
  if (effStatus === 'active') {
    if (battle.scheduledEndTime == null) return '진행 중';
    const diff = battle.scheduledEndTime - now;
    if (diff <= 0) return '종료 직전';
    const totalMinutes = Math.floor(diff / 60_000);
    const hours = Math.floor(totalMinutes / 60);
    const mins = totalMinutes % 60;
    return `종료까지 ${hours}시간 ${mins}분 남음`;
  }
  if (effStatus === 'pending') {
    return '수락 대기 중';
  }
  if (effStatus === 'expired') {
    return '수락 기한 만료';
  }
  if (effStatus === 'cancelled') {
    return '취소됨';
  }
  if (effStatus === 'rejected') {
    return '거절됨';
  }
  return '-';
}

// P3-3: 대결 결과 및 승자 판정 순수 함수 (미지정 필드 fallthrough 방지)
export type BattleWinner = 'requester' | 'recipient' | 'draw' | 'undecided';

export function determineBattleWinner(battle: Battle): BattleWinner {
  if (battle.winnerAllianceId === 'draw') return 'draw';
  if (battle.winnerAllianceId && battle.winnerAllianceId === battle.requesterAllianceId) return 'requester';
  if (battle.winnerAllianceId && battle.winnerAllianceId === battle.recipientAllianceId) return 'recipient';

  if (battle.scores) {
    if (battle.scores.requesterScore > battle.scores.recipientScore) return 'requester';
    if (battle.scores.recipientScore > battle.scores.requesterScore) return 'recipient';
    return 'draw'; // 점수 동점(0:0 포함)은 무승부
  }

  return 'undecided';
}

export function getBattleResultLabel(battle: Battle, requesterName: string, recipientName: string): string {
  const winner = determineBattleWinner(battle);
  if (winner === 'draw') return '무승부 (DRAW)';
  if (winner === 'requester') return `${requesterName} 승리`;
  if (winner === 'recipient') return `${recipientName} 승리`;
  return '결과 미정';
}

// 동시 상대 제한 검사 (단일 동시 상대 원칙 - 확정 정책):
// 예정(scheduled) 또는 진행(active) 중인 경기가 있는 연합은 다른 대결을 확정할 수 없음.
export function isAllianceOccupied(battles: readonly Battle[], allianceId: string, now: number): boolean {
  return battles.some((b) => {
    if (b.requesterAllianceId !== allianceId && b.recipientAllianceId !== allianceId) {
      return false;
    }
    const effStatus = getEffectiveBattleStatus(b, now);
    return effStatus === 'scheduled' || effStatus === 'active';
  });
}

export function getOccupiedBattleForAlliance(
  battles: readonly Battle[],
  allianceId: string,
  now: number,
): Battle | undefined {
  return battles.find((b) => {
    if (b.requesterAllianceId !== allianceId && b.recipientAllianceId !== allianceId) {
      return false;
    }
    const effStatus = getEffectiveBattleStatus(b, now);
    return effStatus === 'scheduled' || effStatus === 'active';
  });
}

// P3-3: 기준 시각(baseTime)에 비례한 초기 모의 대결 시드 생성 (실제 시계 기준 active 상태 보장)
// 모든 대결은 양측 연합 수명(createInitialMockAlliances의 시각표) 안에서 신청·진행·종료된다 (계획서 §4).
export function createInitialMockBattles(baseTime: number = Date.now(), config: BattleConfig = MOCK_SHOP_CONFIG.battle): readonly Battle[] {
  return Object.freeze([
    // 샘플 1: 종료된 경기 (연고 vs 수도권, 연고 승리)
    Object.freeze({
      id: 'battle-sample-finished-2v2',
      requesterAllianceId: 'alliance-sky',
      recipientAllianceId: 'alliance-capital',
      requesterSchoolIds: Object.freeze([SCHOOL_ID.yonsei, SCHOOL_ID.korea]),
      recipientSchoolIds: Object.freeze([SCHOOL_ID.snu, SCHOOL_ID.skku]),
      durationHours: 24,
      status: 'finished' as const,
      createdAt: baseTime - 42 * 3600_000,
      requestedStartTime: baseTime - 40 * 3600_000,
      scheduledStartTime: baseTime - 40 * 3600_000,
      scheduledEndTime: baseTime - 16 * 3600_000,
      scores: Object.freeze({ requesterScore: 18400, recipientScore: 16920 }),
      winnerAllianceId: 'alliance-sky',
      updatedAt: baseTime - 16 * 3600_000,
    }),
    // 샘플 2: 종료된 무승부 경기 (과기원 vs 서울 중앙, 무승부)
    Object.freeze({
      id: 'battle-sample-draw-2v2',
      requesterAllianceId: 'alliance-tech',
      recipientAllianceId: 'alliance-korea-central',
      requesterSchoolIds: Object.freeze([SCHOOL_ID.kaist, SCHOOL_ID.postech]),
      recipientSchoolIds: Object.freeze([SCHOOL_ID.cau, SCHOOL_ID.khu]),
      durationHours: 12,
      status: 'finished' as const,
      createdAt: baseTime - 29.5 * 3600_000,
      requestedStartTime: baseTime - 28 * 3600_000,
      scheduledStartTime: baseTime - 28 * 3600_000,
      scheduledEndTime: baseTime - 16 * 3600_000,
      scores: Object.freeze({ requesterScore: 25000, recipientScore: 25000 }),
      winnerAllianceId: 'draw' as const,
      updatedAt: baseTime - 16 * 3600_000,
    }),
    // 샘플 3: 진행 중인 경기 (경인 vs 신촌, 경인 리드 중)
    // 시작: 과거 2시간 전, 종료: 미래 22시간 후 -> baseTime 시점에 정확히 active 상태
    Object.freeze({
      id: 'battle-sample-active-2v2',
      requesterAllianceId: 'alliance-metro',
      recipientAllianceId: 'alliance-sinchon',
      requesterSchoolIds: Object.freeze([SCHOOL_ID.inha, SCHOOL_ID.ajou]),
      recipientSchoolIds: Object.freeze([SCHOOL_ID.sogang, SCHOOL_ID.ewha]),
      durationHours: 24,
      status: 'scheduled' as const,
      createdAt: baseTime - 3 * 3600_000,
      requestedStartTime: baseTime - 2 * 3600_000,
      scheduledStartTime: baseTime - 2 * 3600_000,
      scheduledEndTime: baseTime + 22 * 3600_000,
      scores: Object.freeze({ requesterScore: 12450, recipientScore: 11800 }),
      updatedAt: baseTime - 2 * 3600_000,
    }),
    // 샘플 4: 대기 중인 경기 신청 (호남 -> 과기원). 빠른 만료는 과기원 +42h
    Object.freeze({
      requestedStartTime: getDefaultRequestedStartTime(baseTime, config, baseTime + 42 * 3600_000, 3),
      id: 'battle-sample-pending-2v2',
      requesterAllianceId: 'alliance-honam',
      recipientAllianceId: 'alliance-tech',
      requesterSchoolIds: Object.freeze([SCHOOL_ID.chonnam, SCHOOL_ID.jbnu]),
      recipientSchoolIds: Object.freeze([SCHOOL_ID.kaist, SCHOOL_ID.postech]),
      durationHours: 3,
      status: 'pending' as const,
      createdAt: baseTime - 1800_000,
      updatedAt: baseTime - 1800_000,
    }),
  ]);
}

export const INITIAL_MOCK_BATTLES: readonly Battle[] = createInitialMockBattles(Date.now());

export function createInitialBattleState(
  baseTime: number = Date.now(),
  config: BattleConfig = MOCK_SHOP_CONFIG.battle,
): BattleState {
  return Object.freeze({
    battles: createInitialMockBattles(baseTime, config),
    simulateRequestFailure: false,
    simulateLoadFailure: false,
    config,
  });
}

export const INITIAL_BATTLE_STATE: BattleState = createInitialBattleState(Date.now(), MOCK_SHOP_CONFIG.battle);

// 대결은 연합끼리, 그리고 2개교 연합끼리(2:2)만 가능하다. 연합 없는 1:1 대결은 없다.
function getAllianceSizeError(reqAlliance: Alliance, recAlliance: Alliance): string | undefined {
  if (reqAlliance.schoolIds.length !== ALLIANCE_SIZE || recAlliance.schoolIds.length !== ALLIANCE_SIZE) {
    return `연합 규모 오류: 대결은 ${ALLIANCE_SIZE}개교 연합끼리만 가능합니다 (신청: ${reqAlliance.schoolIds.length}개교, 대상: ${recAlliance.schoolIds.length}개교).`;
  }
  return undefined;
}

export function applyBattleRequest(
  state: BattleState,
  alliances: readonly Alliance[],
  requesterAllianceId: string,
  recipientAllianceId: string,
  durationHours: number,
  requestedStartTime: number,
  now: number = Date.now(),
  config: BattleConfig = state.config ?? MOCK_SHOP_CONFIG.battle,
): { nextState: BattleState; result: BattleActionResult } {
  if (state.simulateRequestFailure) {
    return {
      nextState: state,
      result: { success: false, error: '네트워크 연결 오류: 대결 신청에 실패했습니다.' },
    };
  }

  if (requesterAllianceId === recipientAllianceId) {
    return {
      nextState: state,
      result: { success: false, error: '자기 연합에는 대결을 신청할 수 없습니다.' },
    };
  }

  const reqAlliance = alliances.find((a) => a.id === requesterAllianceId);
  if (!reqAlliance) {
    return {
      nextState: state,
      result: { success: false, error: '신청 연합 정보를 찾을 수 없습니다.' },
    };
  }

  const recAlliance = alliances.find((a) => a.id === recipientAllianceId);
  if (!recAlliance) {
    return {
      nextState: state,
      result: { success: false, error: '대상 상대 연합 정보를 찾을 수 없습니다.' },
    };
  }

  // 중복 참여 학교 검사
  const hasSchoolOverlap = reqAlliance.schoolIds.some((id) => recAlliance.schoolIds.includes(id));
  if (hasSchoolOverlap) {
    return {
      nextState: state,
      result: { success: false, error: '양측 연합에 중복된 참여 학교가 존재합니다.' },
    };
  }

  // 연합 규모 검사: 대결은 2개교 연합끼리(2:2)만 가능하다
  const sizeError = getAllianceSizeError(reqAlliance, recAlliance);
  if (sizeError) {
    return { nextState: state, result: { success: false, error: sizeError } };
  }

  // 기간 검사 (주입된 config.durationHours 동적 반영)
  const { min, max } = config.durationHours;
  if (!Number.isInteger(durationHours) || durationHours < min || durationHours > max) {
    return {
      nextState: state,
      result: {
        success: false,
        error: `유효하지 않은 대결 기간입니다 (${min}시간 ~ ${max}시간 허용, 입력: ${durationHours}시간).`,
      },
    };
  }

  // 동시 상대 제한: 이미 진행 또는 예정 중인 대결이 있는 연합은 신청 불가
  if (isAllianceOccupied(state.battles, requesterAllianceId, now)) {
    return {
      nextState: state,
      result: {
        success: false,
        error: '신청 연합은 이미 진행 중이거나 예정된 대결이 있습니다 (단일 동시 상대 제한).',
      },
    };
  }

  if (isAllianceOccupied(state.battles, recipientAllianceId, now)) {
    return {
      nextState: state,
      result: {
        success: false,
        error: '대상 상대 연합은 이미 진행 중이거나 예정된 대결이 있습니다 (단일 동시 상대 제한).',
      },
    };
  }

  // 동일 연합 간 중복 pending 요청 검사
  const hasPending = state.battles.some(
    (b) =>
      getEffectiveBattleStatus(b, now, config) === 'pending' &&
      ((b.requesterAllianceId === requesterAllianceId && b.recipientAllianceId === recipientAllianceId) ||
        (b.requesterAllianceId === recipientAllianceId && b.recipientAllianceId === requesterAllianceId)),
  );
  if (hasPending) {
    return {
      nextState: state,
      result: { success: false, error: '이미 해당 연합 간 대기 중인 대결 신청이 존재합니다.' },
    };
  }

  const validation = validateRequestedStartTime(requestedStartTime, now, config);
  if (!validation.success) return { nextState: state, result: validation };

  const windowError = getBattleAllianceWindowError(reqAlliance, recAlliance, requestedStartTime, durationHours, now);
  if (windowError) return { nextState: state, result: { success: false, error: windowError } };

  const newBattle: Battle = Object.freeze({
    id: `battle-${requesterAllianceId}-${recipientAllianceId}-${now}`,
    requesterAllianceId,
    recipientAllianceId,
    requesterSchoolIds: Object.freeze([...reqAlliance.schoolIds]),
    recipientSchoolIds: Object.freeze([...recAlliance.schoolIds]),
    durationHours,
    requestedStartTime,
    status: 'pending' as const,
    createdAt: now,
    updatedAt: now,
  });

  return {
    nextState: Object.freeze({
      ...state,
      battles: Object.freeze([newBattle, ...state.battles]),
    }),
    result: { success: true, battle: newBattle },
  };
}

const EXPIRED_REQUEST_ERROR = '수락 기한이 지나 만료된 대결 신청입니다.';

export function cancelBattleRequest(
  state: BattleState,
  battleId: string,
  now: number = Date.now(),
  config: BattleConfig = state.config ?? MOCK_SHOP_CONFIG.battle,
): { nextState: BattleState; result: BattleActionResult } {
  const target = state.battles.find((b) => b.id === battleId);
  if (!target) {
    return { nextState: state, result: { success: false, error: '존재하지 않는 대결 요청입니다.' } };
  }
  if (getEffectiveBattleStatus(target, now, config) === 'expired') {
    return { nextState: state, result: { success: false, error: EXPIRED_REQUEST_ERROR } };
  }

  if (target.status !== 'pending') {
    return {
      nextState: state,
      result: { success: false, error: `대기 중인 신청만 취소할 수 있습니다 (현재 상태: ${target.status}).` },
    };
  }

  const updatedBattle = Object.freeze({
    ...target,
    status: 'cancelled' as const,
    updatedAt: now,
  });

  return {
    nextState: Object.freeze({
      ...state,
      battles: Object.freeze(state.battles.map((b) => (b.id === battleId ? updatedBattle : b))),
    }),
    result: { success: true, battle: updatedBattle },
  };
}

/** 받은 쪽 연합의 수락. 리더가 없으므로 그 연합 소속 학교 학생 누구든 1명이 수락하면 확정된다. */
export function acceptBattleRequest(
  state: BattleState,
  alliances: readonly Alliance[],
  battleId: string,
  now: number = Date.now(),
  config: BattleConfig = state.config ?? MOCK_SHOP_CONFIG.battle,
): { nextState: BattleState; result: BattleActionResult } {
  const target = state.battles.find((b) => b.id === battleId);
  if (!target) {
    return { nextState: state, result: { success: false, error: '존재하지 않는 대결 요청입니다.' } };
  }

  if (target.status !== 'pending') {
    return { nextState: state, result: { success: false, error: '이미 처리된 대결 요청입니다.' } };
  }

  const reqAlliance = alliances.find((a) => a.id === target.requesterAllianceId);
  const recAlliance = alliances.find((a) => a.id === target.recipientAllianceId);

  if (!reqAlliance || !recAlliance) {
    return { nextState: state, result: { success: false, error: '연합 정보를 확인할 수 없습니다.' } };
  }

  // 수락 시점 연합 규모 재검증
  const sizeError = getAllianceSizeError(reqAlliance, recAlliance);
  if (sizeError) {
    return { nextState: state, result: { success: false, error: sizeError } };
  }

  // 수락 시점 연합 기간 재검증 (만료됐거나 종료가 빠른 만료를 넘으면 거절)
  const windowError = getBattleAllianceWindowError(reqAlliance, recAlliance, target.requestedStartTime, target.durationHours, now);
  if (windowError) {
    return { nextState: state, result: { success: false, error: `수락 불가: ${windowError}` } };
  }

  // 동시 상대 제한 재검증: 수락 직전 양측 연합이 다른 경기에 점유되었는지 검사
  if (isAllianceOccupied(state.battles, target.requesterAllianceId, now)) {
    return {
      nextState: state,
      result: {
        success: false,
        error: '수락 불가: 신청 연합은 이미 진행 또는 예정 중인 대결이 있습니다 (단일 동시 상대 제한).',
      },
    };
  }

  if (isAllianceOccupied(state.battles, target.recipientAllianceId, now)) {
    return {
      nextState: state,
      result: {
        success: false,
        error: '수락 불가: 대상 상대 연합은 이미 진행 또는 예정 중인 대결이 있습니다 (단일 동시 상대 제한).',
      },
    };
  }

  if (now > target.requestedStartTime - config.startTime.minLeadMinutes * 60_000) {
    return { nextState: state, result: { success: false, error: '희망 시작 시간이 지났거나 너무 가까워졌습니다. 신청자에게 다시 신청해달라고 요청해주세요.' } };
  }
  const scheduledStartTime = target.requestedStartTime;
  const scheduledEndTime = scheduledStartTime + target.durationHours * 3600_000;

  const acceptedBattle = Object.freeze({
    ...target,
    status: 'scheduled' as const,
    scheduledStartTime,
    scheduledEndTime,
    scores: Object.freeze({ requesterScore: 0, recipientScore: 0 }),
    updatedAt: now,
  });

  return {
    nextState: Object.freeze({
      ...state,
      battles: Object.freeze(state.battles.map((b) => (b.id === battleId ? acceptedBattle : b))),
    }),
    result: { success: true, battle: acceptedBattle },
  };
}

export function rejectBattleRequest(
  state: BattleState,
  battleId: string,
  now: number = Date.now(),
  config: BattleConfig = state.config ?? MOCK_SHOP_CONFIG.battle,
): { nextState: BattleState; result: BattleActionResult } {
  const target = state.battles.find((b) => b.id === battleId);
  if (!target) {
    return { nextState: state, result: { success: false, error: '존재하지 않는 대결 요청입니다.' } };
  }
  if (getEffectiveBattleStatus(target, now, config) === 'expired') {
    return { nextState: state, result: { success: false, error: EXPIRED_REQUEST_ERROR } };
  }

  if (target.status !== 'pending') {
    return { nextState: state, result: { success: false, error: '이미 처리된 대결 요청입니다.' } };
  }

  const rejectedBattle = Object.freeze({
    ...target,
    status: 'rejected' as const,
    updatedAt: now,
  });

  return {
    nextState: Object.freeze({
      ...state,
      battles: Object.freeze(state.battles.map((b) => (b.id === battleId ? rejectedBattle : b))),
    }),
    result: { success: true, battle: rejectedBattle },
  };
}

// P3-1: 시연용 확정 대결(예정 또는 진행) 취소 및 점유 해제 함수
// 일반 사용자에게는 취소 권한이 제공되지 않으며 시연 제어 영역에서만 수행 가능
export function demoCancelConfirmedBattle(
  state: BattleState,
  battleId: string,
  now: number = Date.now(),
): { nextState: BattleState; result: BattleActionResult } {
  const target = state.battles.find((b) => b.id === battleId);
  if (!target) {
    return { nextState: state, result: { success: false, error: '존재하지 않는 대결 요청입니다.' } };
  }

  const effectiveStatus = getEffectiveBattleStatus(target, now);
  if (effectiveStatus !== 'scheduled' && effectiveStatus !== 'active') {
    return {
      nextState: state,
      result: {
        success: false,
        error: `예정되거나 진행 중인 대결만 시연 취소할 수 있습니다 (현재 실효 상태: ${effectiveStatus}).`,
      },
    };
  }

  const cancelledBattle = Object.freeze({
    ...target,
    status: 'cancelled' as const,
    updatedAt: now,
  });

  return {
    nextState: Object.freeze({
      ...state,
      battles: Object.freeze(state.battles.map((b) => (b.id === battleId ? cancelledBattle : b))),
    }),
    result: { success: true, battle: cancelledBattle },
  };
}

/**
 * 시연용: 다른 연합이 내 연합에 대결을 신청한 상황을 만든다. 활성이고, 학교가 겹치지 않고, 예정·진행 중인
 * 대결이 없고, 내 연합과 대기 신청이 없는 연합을 목록 순서대로 고른다(결정적). 기간은 기본값과 연합 만료 상한 중
 * 작은 값, 시작은 그 기간으로 끝낼 수 있는 가장 빠른 슬롯이다.
 */
export function demoReceiveBattleRequest(
  state: BattleState,
  alliances: readonly Alliance[],
  myAllianceId: string,
  now: number = Date.now(),
  config: BattleConfig = state.config ?? MOCK_SHOP_CONFIG.battle,
): { nextState: BattleState; result: BattleActionResult } {
  const mine = alliances.find((a) => a.id === myAllianceId);
  if (!mine || !isAllianceActive(mine, now)) {
    return { nextState: state, result: { success: false, error: '소속된 활성 연합이 없습니다.' } };
  }
  if (isAllianceOccupied(state.battles, myAllianceId, now)) {
    return { nextState: state, result: { success: false, error: '내 연합은 이미 진행 중이거나 예정된 대결이 있습니다.' } };
  }
  const hasPendingWithMe = (allianceId: string) =>
    state.battles.some(
      (b) =>
        getEffectiveBattleStatus(b, now, config) === 'pending' &&
        [b.requesterAllianceId, b.recipientAllianceId].includes(allianceId) &&
        [b.requesterAllianceId, b.recipientAllianceId].includes(myAllianceId),
    );

  for (const opponent of alliances) {
    if (opponent.id === myAllianceId || !isAllianceActive(opponent, now)) continue;
    if (opponent.schoolIds.some((id) => mine.schoolIds.includes(id))) continue;
    if (isAllianceOccupied(state.battles, opponent.id, now) || hasPendingWithMe(opponent.id)) continue;
    const latestEnd = getBattleLatestEnd(mine, opponent);
    const firstSlot = getDefaultRequestedStartTime(now, config, latestEnd, config.durationHours.min);
    if (!Number.isFinite(firstSlot)) continue;
    const durationHours = Math.min(config.durationHours.default, getMaxBattleDurationHours(firstSlot, latestEnd, config));
    return applyBattleRequest({ ...state, simulateRequestFailure: false }, alliances, opponent.id, myAllianceId, durationHours, firstSlot, now, config);
  }
  return { nextState: state, result: { success: false, error: '대결을 신청할 수 있는 다른 연합이 없습니다.' } };
}
