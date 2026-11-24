import { FICTIONAL_SCHOOL_ID, SCHOOL_ID, type SchoolId } from './schoolIds.ts';
import { MOCK_SHOP_CONFIG, type MockAllianceConfig } from './mockShopConfig.ts';
import { REAL_UNIVERSITIES } from './universitiesData.ts';

// 연합 규칙 (.agents/01-PLAN/261112-1206-alliance_battle_rule_update_plan.md, ERD 결정 9·10·18):
// - 연합은 정확히 2개교. 한 학교는 활성 연합 하나에만 소속된다.
// - 연합장 같은 리더는 없다. 제안받은 학교의 학생 누구든 1명이 수락하면 즉시 성립한다.
// - 제안하는 쪽이 기간(6~72시간)을 정하고, 수락 시각부터 그 기간이 지나면 만료된다.
// - 한 학교가 여러 학교에 동시에 제안할 수 있다. 하나가 성립하면 두 학교에 걸린 나머지 대기 제안은 자동 거절된다.

export type AllianceConfig = MockAllianceConfig;

export type AllianceRequestStatus = 'pending' | 'accepted' | 'rejected' | 'cancelled';

export interface AllianceRequest {
  id: string;
  requesterSchoolId: SchoolId;
  recipientSchoolId: SchoolId;
  /** 제안 측이 정한 연합 기간(시간). 수락되면 이 기간으로 연합이 만들어진다. */
  durationHours: number;
  status: AllianceRequestStatus;
  /** 'capacity': 두 학교 중 하나가 다른 연합을 맺어 자동 거절됨 */
  closedReason?: 'capacity';
  createdAt: number;
  updatedAt: number;
}

export interface Alliance {
  id: string;
  name: string;
  schoolIds: readonly SchoolId[];
  /** 성립(수락) 시각 */
  createdAt: number;
  durationHours: number;
  /** createdAt + durationHours. 이 시각부터 만료(비활성) */
  expiresAt: number;
}

export function isValidSchoolId(schoolId: SchoolId): boolean {
  if (schoolId === FICTIONAL_SCHOOL_ID) return true;
  return REAL_UNIVERSITIES.some((u) => u.id === schoolId);
}

export function getSchoolInfo(schoolId: SchoolId) {
  if (schoolId === FICTIONAL_SCHOOL_ID) {
    return { id: FICTIONAL_SCHOOL_ID, name: '푸른바다대학교', shortName: '푸른바다대', firstSyllable: '푸', badgeColor: '#0d9488', region: '경상' };
  }
  const found = REAL_UNIVERSITIES.find((u) => u.id === schoolId);
  if (found) return found;
  return { id: schoolId, name: String(schoolId), shortName: String(schoolId), firstSyllable: '?', badgeColor: '#888888', region: '기타' };
}

/**
 * 연합은 정확히 2개교로 구성된다 (3개교 이상 연합은 구현하지 않음). 대결도 2:2만 가능하다.
 * 근거: ERD 결정 18 (.agents/261111-1356-project_plan_reference.md), 사용자 지시 2026-11-12.
 */
export const ALLIANCE_SIZE = 2;

const HOUR_MS = 3600_000;

function seedAlliance(id: string, name: string, schoolIds: [SchoolId, SchoolId], createdAt: number, durationHours: number): Alliance {
  return Object.freeze({
    id,
    name,
    schoolIds: Object.freeze(schoolIds),
    createdAt,
    durationHours,
    expiresAt: createdAt + durationHours * HOUR_MS,
  });
}

/**
 * 기준 시각(baseTime)에 비례한 시드 연합. 모두 baseTime에 활성이며 가장 빠른 만료는 +18h다.
 * 시각표: 계획서 §4.
 */
export function createInitialMockAlliances(baseTime: number = Date.now()): readonly Alliance[] {
  return Object.freeze([
    seedAlliance('alliance-sky', '연고 메이저 연합', [SCHOOL_ID.yonsei, SCHOOL_ID.korea], baseTime - 48 * HOUR_MS, 72),
    seedAlliance('alliance-capital', '수도권 동맹', [SCHOOL_ID.snu, SCHOOL_ID.skku], baseTime - 50 * HOUR_MS, 72),
    seedAlliance('alliance-metro', '경인 메트로 연합', [SCHOOL_ID.inha, SCHOOL_ID.ajou], baseTime - 10 * HOUR_MS, 48),
    seedAlliance('alliance-sinchon', '신촌 연합', [SCHOOL_ID.sogang, SCHOOL_ID.ewha], baseTime - 6 * HOUR_MS, 48),
    seedAlliance('alliance-tech', '과학기술원 연합', [SCHOOL_ID.kaist, SCHOOL_ID.postech], baseTime - 30 * HOUR_MS, 72),
    seedAlliance('alliance-korea-central', '서울 중앙 연합', [SCHOOL_ID.cau, SCHOOL_ID.khu], baseTime - 30 * HOUR_MS, 48),
    seedAlliance('alliance-honam', '호남 플래그십 연합', [SCHOOL_ID.chonnam, SCHOOL_ID.jbnu], baseTime - 2 * HOUR_MS, 72),
  ]);
}

export const INITIAL_MOCK_ALLIANCES: readonly Alliance[] = createInitialMockAlliances(Date.now());

export function isAllianceActive(alliance: Alliance, now: number): boolean {
  return now < alliance.expiresAt;
}

/** 학교가 지금 소속된 활성 연합. 만료된 연합의 학교는 다시 미소속이다. */
export function getAllianceForSchool(alliances: readonly Alliance[], schoolId: SchoolId, now: number): Alliance | undefined {
  return alliances.find((a) => a.schoolIds.includes(schoolId) && isAllianceActive(a, now));
}

/** 학교가 소속됐다가 가장 최근에 만료된 연합 (안내 문구용) */
export function getLatestExpiredAllianceForSchool(alliances: readonly Alliance[], schoolId: SchoolId, now: number): Alliance | undefined {
  return alliances
    .filter((a) => a.schoolIds.includes(schoolId) && !isAllianceActive(a, now))
    .sort((a, b) => b.expiresAt - a.expiresAt)[0];
}

export function formatAllianceRemainingTime(alliance: Alliance, now: number): string {
  const diff = alliance.expiresAt - now;
  if (diff <= 0) return '만료됨';
  const totalMinutes = Math.floor(diff / 60_000);
  return `만료까지 ${Math.floor(totalMinutes / 60)}시간 ${totalMinutes % 60}분 남음`;
}

export function validateAllianceDurationHours(durationHours: number, config: AllianceConfig = MOCK_SHOP_CONFIG.alliance): string | undefined {
  const { min, max } = config.durationHours;
  if (!Number.isInteger(durationHours) || durationHours < min || durationHours > max) {
    return `유효하지 않은 연합 기간입니다 (${min}시간 ~ ${max}시간, 1시간 단위 허용, 입력: ${durationHours}시간).`;
  }
  return undefined;
}

export interface AllianceState {
  alliances: readonly Alliance[];
  requests: readonly AllianceRequest[];
  simulateRequestFailure: boolean;
  simulateLoadFailure: boolean;
}

export function createInitialAllianceState(baseTime: number = Date.now()): AllianceState {
  return Object.freeze({
    alliances: createInitialMockAlliances(baseTime),
    requests: Object.freeze([]),
    simulateRequestFailure: false,
    simulateLoadFailure: false,
  });
}

export const INITIAL_ALLIANCE_STATE: AllianceState = Object.freeze({
  alliances: INITIAL_MOCK_ALLIANCES,
  requests: Object.freeze([]),
  simulateRequestFailure: false,
  simulateLoadFailure: false,
});

export type AllianceActionResult =
  | { success: true; request?: AllianceRequest; alliance?: Alliance; autoRejectedRequestIds?: readonly string[] }
  | { success: false; error: string };

let requestCounter = 0;

function fail(state: AllianceState, error: string): { nextState: AllianceState; result: AllianceActionResult } {
  return { nextState: state, result: { success: false, error } };
}

// 제안과 수락이 공통으로 확인하는 조건 (수락 시점에도 최신 상태로 다시 확인한다)
function getPairError(state: AllianceState, requesterSchoolId: SchoolId, recipientSchoolId: SchoolId, now: number, phase: '신청' | '수락'): string | undefined {
  if (!isValidSchoolId(requesterSchoolId) || !isValidSchoolId(recipientSchoolId)) return '유효하지 않은 학교 ID입니다.';
  if (requesterSchoolId === recipientSchoolId) {
    return phase === '신청' ? '자기 학교에는 연합을 신청할 수 없습니다.' : '자기 학교 간 연합은 불가능합니다.';
  }
  const areAlreadyAllied = state.alliances.some(
    (a) => isAllianceActive(a, now) && a.schoolIds.includes(requesterSchoolId) && a.schoolIds.includes(recipientSchoolId),
  );
  if (areAlreadyAllied) return '두 학교는 이미 같은 연합에 소속되어 있습니다.';

  const prefix = phase === '수락' ? '수락 불가: ' : '';
  const requesterAlliance = getAllianceForSchool(state.alliances, requesterSchoolId, now);
  if (requesterAlliance) return `${prefix}신청 학교는 이미 다른 연합(${requesterAlliance.name})에 소속되어 있습니다.`;
  const recipientAlliance = getAllianceForSchool(state.alliances, recipientSchoolId, now);
  if (recipientAlliance) return `${prefix}대상 학교는 이미 다른 연합(${recipientAlliance.name})에 소속되어 있습니다.`;
  return undefined;
}

export function applyAllianceRequest(
  state: AllianceState,
  requesterSchoolId: SchoolId,
  recipientSchoolId: SchoolId,
  now: number = Date.now(),
  durationHours: number = MOCK_SHOP_CONFIG.alliance.durationHours.default,
  config: AllianceConfig = MOCK_SHOP_CONFIG.alliance,
): { nextState: AllianceState; result: AllianceActionResult } {
  if (state.simulateRequestFailure) return fail(state, '네트워크 연결 오류로 연합 신청에 실패했습니다.');

  const pairError = getPairError(state, requesterSchoolId, recipientSchoolId, now, '신청');
  if (pairError) return fail(state, pairError);

  const durationError = validateAllianceDurationHours(durationHours, config);
  if (durationError) return fail(state, durationError);

  // 같은 두 학교 사이의 대기 제안은 방향과 관계없이 1건만 허용한다. 다른 학교에 동시에 제안하는 것은 허용한다.
  const hasDuplicatePending = state.requests.some(
    (r) =>
      r.status === 'pending' &&
      ((r.requesterSchoolId === requesterSchoolId && r.recipientSchoolId === recipientSchoolId) ||
        (r.requesterSchoolId === recipientSchoolId && r.recipientSchoolId === requesterSchoolId)),
  );
  if (hasDuplicatePending) return fail(state, '이미 대기 중인 연합 신청이 존재합니다.');

  const newRequest: AllianceRequest = Object.freeze({
    id: `req-${now}-${++requestCounter}`,
    requesterSchoolId,
    recipientSchoolId,
    durationHours,
    status: 'pending',
    createdAt: now,
    updatedAt: now,
  });

  return {
    nextState: Object.freeze({ ...state, requests: Object.freeze([newRequest, ...state.requests]) }),
    result: { success: true, request: newRequest },
  };
}

export function cancelAllianceRequest(
  state: AllianceState,
  requestId: string,
  now: number = Date.now(),
): { nextState: AllianceState; result: AllianceActionResult } {
  const target = state.requests.find((r) => r.id === requestId);
  if (!target) return fail(state, '존재하지 않는 요청입니다.');
  if (target.status !== 'pending') return fail(state, '이미 처리된 요청입니다.');

  const updatedRequests = state.requests.map((r) =>
    r.id === requestId ? Object.freeze({ ...r, status: 'cancelled' as const, updatedAt: now }) : r,
  );
  return {
    nextState: Object.freeze({ ...state, requests: Object.freeze(updatedRequests) }),
    result: { success: true },
  };
}

/**
 * 제안을 받은 학교 쪽에서 수락한다. 리더가 없으므로 그 학교 학생 누구든 1명이 수락하면 즉시 성립한다.
 * 성립하면 두 학교 중 하나라도 걸린 다른 대기 제안은 정원 초과로 자동 거절된다.
 */
export function acceptAllianceRequest(
  state: AllianceState,
  requestId: string,
  now: number = Date.now(),
): { nextState: AllianceState; result: AllianceActionResult } {
  const target = state.requests.find((r) => r.id === requestId);
  if (!target) return fail(state, '존재하지 않는 요청입니다.');
  if (target.status !== 'pending') return fail(state, '이미 처리된 요청입니다.');

  const pairError = getPairError(state, target.requesterSchoolId, target.recipientSchoolId, now, '수락');
  if (pairError) return fail(state, pairError);

  const newAllianceId = `alliance-${target.requesterSchoolId}-${target.recipientSchoolId}-${now}`;
  if (state.alliances.some((a) => a.id === newAllianceId)) return fail(state, '이미 동일한 ID를 가진 연합이 존재합니다.');

  const members = [target.requesterSchoolId, target.recipientSchoolId];
  const autoRejectedRequestIds: string[] = [];
  const updatedRequests = state.requests.map((r) => {
    if (r.id === requestId) return Object.freeze({ ...r, status: 'accepted' as const, updatedAt: now });
    if (r.status === 'pending' && (members.includes(r.requesterSchoolId) || members.includes(r.recipientSchoolId))) {
      autoRejectedRequestIds.push(r.id);
      return Object.freeze({ ...r, status: 'rejected' as const, closedReason: 'capacity' as const, updatedAt: now });
    }
    return r;
  });

  const reqSchool = getSchoolInfo(target.requesterSchoolId);
  const recSchool = getSchoolInfo(target.recipientSchoolId);
  const newAlliance: Alliance = Object.freeze({
    id: newAllianceId,
    name: `${reqSchool.shortName}·${recSchool.shortName} 연합`,
    schoolIds: Object.freeze(members),
    createdAt: now,
    durationHours: target.durationHours,
    expiresAt: now + target.durationHours * HOUR_MS,
  });

  return {
    nextState: Object.freeze({
      ...state,
      alliances: Object.freeze([newAlliance, ...state.alliances]),
      requests: Object.freeze(updatedRequests),
    }),
    result: { success: true, alliance: newAlliance, autoRejectedRequestIds: Object.freeze(autoRejectedRequestIds) },
  };
}

export function rejectAllianceRequest(
  state: AllianceState,
  requestId: string,
  now: number = Date.now(),
): { nextState: AllianceState; result: AllianceActionResult } {
  const target = state.requests.find((r) => r.id === requestId);
  if (!target) return fail(state, '존재하지 않는 요청입니다.');
  if (target.status !== 'pending') return fail(state, '이미 처리된 요청입니다.');

  const updatedRequests = state.requests.map((r) =>
    r.id === requestId ? Object.freeze({ ...r, status: 'rejected' as const, updatedAt: now }) : r,
  );
  return {
    nextState: Object.freeze({ ...state, requests: Object.freeze(updatedRequests) }),
    result: { success: true },
  };
}

/**
 * 시연용: 다른 학교가 내 학교에 연합을 제안한 상황을 만든다. 활성 연합이 없고, 내 학교와 대기 제안이 없는
 * 학교를 목록 순서대로 고른다(결정적). 기간은 12시간·24시간을 번갈아 쓴다.
 */
export function demoReceiveAllianceProposal(
  state: AllianceState,
  mySchoolId: SchoolId,
  now: number = Date.now(),
): { nextState: AllianceState; result: AllianceActionResult } {
  const hasPendingWithMe = (schoolId: SchoolId) =>
    state.requests.some(
      (r) =>
        r.status === 'pending' &&
        ((r.requesterSchoolId === schoolId && r.recipientSchoolId === mySchoolId) ||
          (r.requesterSchoolId === mySchoolId && r.recipientSchoolId === schoolId)),
    );
  const proposer = REAL_UNIVERSITIES.find(
    (u) => u.id !== mySchoolId && !getAllianceForSchool(state.alliances, u.id, now) && !hasPendingWithMe(u.id),
  );
  if (!proposer) return fail(state, '제안을 보낼 수 있는 다른 학교가 없습니다.');
  const received = state.requests.filter((r) => r.recipientSchoolId === mySchoolId).length;
  const durationHours = received % 2 === 0 ? 12 : 24;
  return applyAllianceRequest({ ...state, simulateRequestFailure: false }, proposer.id, mySchoolId, now, durationHours);
}
