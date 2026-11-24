import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { assertAbsent } from './helpers/domAssert';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';

// DOM 통합 테스트용 자산 로더
const assetHooks = registerHooks({
  load(url, context, nextLoad) {
    if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) {
      return { format: 'module', source: `export default ${JSON.stringify(url)};`, shortCircuit: true };
    }
    return nextLoad(url, context);
  },
});

// DOM 환경 설정
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window,
  document: dom.window.document,
  navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement,
  Node: dom.window.Node,
  requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window),
  cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window),
  IS_REACT_ACT_ENVIRONMENT: true,
})) {
  Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
}
dom.window.HTMLElement.prototype.scrollIntoView = function () {};

const { render, screen, fireEvent, act, cleanup } = await import('@testing-library/react');
const { testViewButton } = await import('./helpers/mockTools');
const {
  ALLIANCE_SIZE,
  applyAllianceRequest,
  cancelAllianceRequest,
  acceptAllianceRequest,
  rejectAllianceRequest,
  getAllianceForSchool,
  getLatestExpiredAllianceForSchool,
  isAllianceActive,
  formatAllianceRemainingTime,
  createInitialMockAlliances,
  getSchoolInfo,
  INITIAL_ALLIANCE_STATE,
  INITIAL_MOCK_ALLIANCES,
} = await import('../src/data/allianceModel.ts');
const { BattleProvider, useBattle } = await import('../src/hooks/useMockBattle.tsx');
const { AllianceProvider, useAlliance } = await import('../src/hooks/useMockAlliance.tsx');
const { MockGameProvider } = await import('../src/hooks/useMockGame.tsx');
const { AllianceApplyScreenPhone } = await import('../src/components/AllianceApplyScreenPhone.tsx');
const { CurrentAllianceScreenPhone } = await import('../src/components/CurrentAllianceScreenPhone.tsx');
const { default: App } = await import('../src/App.tsx');

afterEach(() => cleanup());
after(() => {
  assetHooks.deregister();
  dom.window.close();
});

test('연합 신청 유효성 검사: 자기 학교 신청, 유효하지 않은 학교 ID, 중복 대기 신청을 거절한다', () => {
  const state = INITIAL_ALLIANCE_STATE;

  // 자기 학교 신청 거절
  const selfRes = applyAllianceRequest(state, SCHOOL_ID.hanyang, SCHOOL_ID.hanyang);
  assert.equal(selfRes.result.success, false);
  if (!selfRes.result.success) {
    assert.match(selfRes.result.error, /자기 학교/);
  }

  // 존재하지 않는 학교 ID 거절
  const invalidRes = applyAllianceRequest(state, SCHOOL_ID.hanyang, 999_999); // no university row has this ID
  assert.equal(invalidRes.result.success, false);
  if (!invalidRes.result.success) {
    assert.match(invalidRes.result.error, /유효하지 않은 학교 ID/);
  }

  // 정상 신청
  const validRes = applyAllianceRequest(state, SCHOOL_ID.hanyang, SCHOOL_ID.pusan, 1000);
  assert.equal(validRes.result.success, true);
  assert.equal(validRes.nextState.requests.length, 1);
  const pendingReq = validRes.nextState.requests[0];
  assert.equal(pendingReq.requesterSchoolId, SCHOOL_ID.hanyang);
  assert.equal(pendingReq.recipientSchoolId, SCHOOL_ID.pusan);
  assert.equal(pendingReq.status, 'pending');

  // 동일 학교 쌍 간 중복 대기 신청 거절
  const dupRes1 = applyAllianceRequest(validRes.nextState, SCHOOL_ID.hanyang, SCHOOL_ID.pusan, 1001);
  assert.equal(dupRes1.result.success, false);
  if (!dupRes1.result.success) {
    assert.match(dupRes1.result.error, /이미 대기 중인/);
  }

  // 역방향 동일 학교 쌍 간 중복 대기 신청도 거절
  const dupRes2 = applyAllianceRequest(validRes.nextState, SCHOOL_ID.pusan, SCHOOL_ID.hanyang, 1002);
  assert.equal(dupRes2.result.success, false);
  if (!dupRes2.result.success) {
    assert.match(dupRes2.result.error, /이미 대기 중인/);
  }
});

test('상태 전이 및 확정된 요청의 재처리(2회 승인/거절/취소) 차단을 검증한다', () => {
  const state = INITIAL_ALLIANCE_STATE;
  const { nextState: state1, result: appRes } = applyAllianceRequest(state, SCHOOL_ID.hanyang, SCHOOL_ID.pusan, 2000);
  assert.ok(appRes.success);
  assert.ok(appRes.request);
  const reqId = appRes.request.id;

  // 1. 수락 전이: 새로운 연합 생성 및 요청 accepted 상태 갱신
  const { nextState: acceptedState, result: acceptRes } = acceptAllianceRequest(state1, reqId, 2100);
  assert.equal(acceptRes.success, true);
  if (acceptRes.success) {
    assert.ok(acceptRes.alliance);
    assert.deepEqual([...acceptRes.alliance.schoolIds], [SCHOOL_ID.hanyang, SCHOOL_ID.pusan]);
  }
  const acceptedReq = acceptedState.requests.find((r) => r.id === reqId);
  assert.equal(acceptedReq?.status, 'accepted');

  // 이미 수락된 요청에 대해 재처리(수락/거절/취소) 시 거절
  const reAccept = acceptAllianceRequest(acceptedState, reqId, 2200);
  assert.equal(reAccept.result.success, false);
  assert.match((reAccept.result as { error: string }).error, /이미 처리된/);

  const reReject = rejectAllianceRequest(acceptedState, reqId, 2200);
  assert.equal(reReject.result.success, false);
  assert.match((reReject.result as { error: string }).error, /이미 처리된/);

  const reCancel = cancelAllianceRequest(acceptedState, reqId, 2200);
  assert.equal(reCancel.result.success, false);
  assert.match((reCancel.result as { error: string }).error, /이미 처리된/);

  // 2. 거절 전이: 요청 rejected 상태 갱신 및 재처리 차단
  const { nextState: state2 } = applyAllianceRequest(state, SCHOOL_ID.hanyang, SCHOOL_ID.knu, 3000);
  const req2 = state2.requests[0];
  const { nextState: rejectedState, result: rejectRes } = rejectAllianceRequest(state2, req2.id, 3100);
  assert.equal(rejectRes.success, true);
  assert.equal(rejectedState.requests.find((r) => r.id === req2.id)?.status, 'rejected');

  const reReject2 = rejectAllianceRequest(rejectedState, req2.id, 3200);
  assert.equal(reReject2.result.success, false);
  assert.match((reReject2.result as { error: string }).error, /이미 처리된/);

  // 3. 취소 전이: 요청 cancelled 상태 갱신 및 재처리 차단
  const { nextState: state3 } = applyAllianceRequest(state, SCHOOL_ID.hanyang, SCHOOL_ID.gachon, 4000);
  const req3 = state3.requests[0];
  const { nextState: cancelledState, result: cancelRes } = cancelAllianceRequest(state3, req3.id, 4100);
  assert.equal(cancelRes.success, true);
  assert.equal(cancelledState.requests.find((r) => r.id === req3.id)?.status, 'cancelled');

  const reCancel2 = cancelAllianceRequest(cancelledState, req3.id, 4200);
  assert.equal(reCancel2.result.success, false);
  assert.match((reCancel2.result as { error: string }).error, /이미 처리된/);
});

test('네트워크 오류 시뮬레이션 및 복구(재시도) 동작을 검증한다', () => {
  const failureState = { ...INITIAL_ALLIANCE_STATE, simulateRequestFailure: true };
  const failRes = applyAllianceRequest(failureState, SCHOOL_ID.hanyang, SCHOOL_ID.pusan);
  assert.equal(failRes.result.success, false);
  assert.match((failRes.result as { error: string }).error, /네트워크 연결 오류/);

  // 오류 플래그 해제 후 재시도 성공
  const recoveredState = { ...failureState, simulateRequestFailure: false };
  const okRes = applyAllianceRequest(recoveredState, SCHOOL_ID.hanyang, SCHOOL_ID.pusan);
  assert.equal(okRes.result.success, true);
});

test('연합 신청 화면: 대상 학교 검색, 빈 검색 결과, 신청 및 취소 흐름을 검증한다', () => {
  cleanup();

  let allianceValue: ReturnType<typeof useAlliance>;
  function AllianceProbe() {
    allianceValue = useAlliance();
    return null;
  }

  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        null,
        React.createElement(AllianceProbe),
        React.createElement(AllianceApplyScreenPhone),
      ),
    ),
  );

  // 검색 인풋 찾기
  const searchInput = screen.getByRole('textbox', { name: '대상 학교 검색' });
  assert.ok(searchInput);

  // 1. 결과가 없는 검색어 입력 시 '검색 결과가 없습니다' 표시
  act(() => {
    fireEvent.change(searchInput, { target: { value: '없는대학교12345' } });
  });
  assert.ok(screen.getByText('검색 결과가 없습니다'));

  // 2. 존재하는 검색어 '부산' 입력 시 학교 버튼 표시 및 선택
  act(() => {
    fireEvent.change(searchInput, { target: { value: '부산' } });
  });
  const pusanButton = screen.getByRole('button', { name: /부산대학교/ });
  assert.ok(pusanButton);

  act(() => {
    fireEvent.click(pusanButton);
  });

  // 신청 요약 및 '연합 신청 보내기' 버튼 노출 확인
  const sendButton = screen.getByRole('button', { name: '연합 신청 보내기' });
  assert.ok(sendButton);

  // 신청 전송
  act(() => {
    fireEvent.click(sendButton);
  });

  // 신청 완료 메시지 및 보낸 신청 항목 노출 확인
  assert.ok(screen.getByText(/연합 신청을 성공적으로 보냈습니다/));
  assert.equal(allianceValue!.sentRequests.length, 1);
  assert.equal(allianceValue!.sentRequests[0].status, 'pending');

  // 일반 사용자 화면에는 '신청 취소' 버튼만 있고, 일반 영역에 '수락' 버튼은 존재하지 않음
  const cancelButton = screen.getByRole('button', { name: '신청 취소' });
  assert.ok(cancelButton);

  // 시연 영역([시연 제어]) 내에만 수락/거절 버튼이 존재
  const demoAcceptButton = screen.getByRole('button', { name: '[시연] 상대 학교 명의로 수락' });
  const demoRejectButton = screen.getByRole('button', { name: '[시연] 상대 학교 명의로 거절' });
  assert.ok(demoAcceptButton);
  assert.ok(demoRejectButton);

  // 사용자가 신청 취소 실행
  act(() => {
    fireEvent.click(cancelButton);
  });
  assert.equal(allianceValue!.sentRequests[0].status, 'cancelled');
  assert.ok(screen.getByText('취소됨'));
});

test('현재 연합 화면: 소속 연합 없음 상태, 시연 수락 후 결성 상태, 조회 실패 및 재시도를 검증한다', () => {
  cleanup();

  let allianceValue: ReturnType<typeof useAlliance>;
  function AllianceProbe() {
    allianceValue = useAlliance();
    return null;
  }

  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        null,
        React.createElement(AllianceProbe),
        React.createElement(CurrentAllianceScreenPhone),
      ),
    ),
  );

  // 1. 소속 연합 없음 상태 확인
  assert.ok(screen.getByText('소속 연합 없음'));
  assert.ok(screen.getByRole('button', { name: /연합 신청하러 가기/ }));

  // 2. 조회 실패 시뮬레이션 토글
  act(() => {
    allianceValue.toggleSimulateLoadFailure();
  });
  assert.ok(screen.getByRole('alert'));
  assert.ok(screen.getByText('연합 정보 조회 실패'));

  // 3. '다시 시도' 버튼 클릭 시 정상 복구
  const retryBtn = screen.getByRole('button', { name: /다시 시도/ });
  act(() => {
    fireEvent.click(retryBtn);
  });
  assertAbsent(screen.queryByRole('alert'));
  assert.ok(screen.getByText('소속 연합 없음'));

  // 4. 연합 신청 후 시연 수락 시 결성 상태로 전이
  let targetReqId = '';
  act(() => {
    const res = allianceValue.applyRequest(SCHOOL_ID.pusan);
    assert.ok(res.success);
    targetReqId = res.request!.id;
  });

  act(() => {
    const accRes = allianceValue.demoAcceptRequest(targetReqId);
    assert.ok(accRes.success);
  });

  // 결성 완료 표시, 참여 학교 목록, 연합 남은 기간(기본 24시간), 대결 진입 안내 확인
  assert.ok(screen.getByText(/결성 완료 \(활성\)/));
  assert.ok(screen.getByText(/한양대·부산대 연합/));
  assert.ok(screen.getByText('참여 학교 목록 (2개교)'));
  assert.match(screen.getByLabelText('연합 기간').textContent ?? '', /만료까지 2[34]시간 \d+분 남음/);
  assert.match(screen.getByLabelText('연합 기간').textContent ?? '', /24시간 \(1일\)/);
  assert.ok(screen.getByText('대결 경기'));
  assert.ok(screen.getByText(/대결은 연합끼리 2:2로만/));
  assertAbsent(screen.queryByText(/N4/), 'N4 미구현 안내는 삭제됨');
});

test('화면 전환 시 연합 상태가 보존되며 기존 게임 상태(점수, 기여도) 불변을 유지한다', () => {
  cleanup();

  render(React.createElement(MockGameProvider, null, React.createElement(App)));

  // 1. 연합 신청 화면으로 이동하여 연합 신청 및 수락
  const applyNavBtn = testViewButton(/연합 신청 \(Alliance Apply\)/);
  act(() => {
    fireEvent.click(applyNavBtn);
  });

  // 검색하여 '부산대학교' 선택 후 신청
  const searchInput = screen.getByRole('textbox', { name: '대상 학교 검색' });
  act(() => {
    fireEvent.change(searchInput, { target: { value: '부산' } });
  });
  const pusanBtn = screen.getByRole('button', { name: /부산대학교/ });
  act(() => {
    fireEvent.click(pusanBtn);
  });
  const sendBtn = screen.getByRole('button', { name: '연합 신청 보내기' });
  act(() => {
    fireEvent.click(sendBtn);
  });

  // 시연 수락
  const demoAcceptBtn = screen.getByRole('button', { name: '[시연] 상대 학교 명의로 수락' });
  act(() => {
    fireEvent.click(demoAcceptBtn);
  });

  // 2. 상단 헤더의 '뒤로' 버튼을 클릭하여 메인 게임 화면(gameplay)으로 복귀
  const backBtn = screen.getByRole('button', { name: '뒤로가기' });
  act(() => {
    fireEvent.click(backBtn);
  });

  // 메인 게임 화면의 캠퍼스 탭 및 TopHUD 노출 확인
  assert.ok(screen.getAllByText(/한양대학교/).length > 0);

  // 하단 내비의 '연합·대결' 버튼을 클릭하여 현재 연합 화면으로 진입 (2026-11-08 사용자 지시로 헤더에서 이동)
  const hudAllianceBtn = screen.getByRole('button', { name: '연합·대결' });
  act(() => {
    fireEvent.click(hudAllianceBtn);
  });

  // 3. 현재 연합 화면에서 연합 상태가 그대로 보존되어 결성된 연합이 표시되는지 검증
  assert.ok(screen.getByText(/한양대·부산대 연합/));
  assert.ok(screen.getByText(/결성 완료 \(활성\)/));
  assert.ok(screen.getByText('참여 학교 목록 (2개교)'));

  // 4. '연합 신청' 버튼을 클릭하여 연합 신청 화면으로 이동 시 보낸 내역도 보존됨
  const navToApplyBtn = screen.getByRole('button', { name: /^연합 신청$/ });
  act(() => {
    fireEvent.click(navToApplyBtn);
  });
  assert.ok(screen.getByText(/부산대학교 \(보낸 신청\)/));
  assert.ok(screen.getByText('수락됨'));
});

test('P2-1 (a): 이미 같은 연합에 소속된 두 학교 간의 재신청 및 수락을 거절한다', () => {
  // 초기 시드 연합 중 'yonsei'와 'korea'는 이미 'alliance-sky'에 소속됨
  const res1 = applyAllianceRequest(INITIAL_ALLIANCE_STATE, SCHOOL_ID.yonsei, SCHOOL_ID.korea);
  assert.equal(res1.result.success, false);
  if (!res1.result.success) {
    assert.match(res1.result.error, /이미 같은 연합/);
  }

  // 역방향 신청도 거절
  const res2 = applyAllianceRequest(INITIAL_ALLIANCE_STATE, SCHOOL_ID.korea, SCHOOL_ID.yonsei);
  assert.equal(res2.result.success, false);
  if (!res2.result.success) {
    assert.match(res2.result.error, /이미 같은 연합/);
  }

  // 신규 결성 후 동일 학교 쌍 재신청 거절
  const step1 = applyAllianceRequest(INITIAL_ALLIANCE_STATE, SCHOOL_ID.hanyang, SCHOOL_ID.pusan);
  assert.equal(step1.result.success, true);
  if (!step1.result.success || !step1.result.request) throw new Error('step1 failed');
  const step2 = acceptAllianceRequest(step1.nextState, step1.result.request.id);
  assert.equal(step2.result.success, true);

  const reApply = applyAllianceRequest(step2.nextState, SCHOOL_ID.hanyang, SCHOOL_ID.pusan);
  assert.equal(reApply.result.success, false);
  if (!reApply.result.success) {
    assert.match(reApply.result.error, /이미 같은 연합/);
  }
});

test('P2-1 (b): 어떠한 연합 신청/수락/취소 시퀀스 후에도 연합 ID의 중복이 발생하지 않는다 (ID 유일성 불변식)', () => {
  let s = INITIAL_ALLIANCE_STATE;
  const actions: Array<[SchoolId, SchoolId]> = [
    [SCHOOL_ID.hanyang, SCHOOL_ID.pusan],
    [SCHOOL_ID.hanyang, SCHOOL_ID.pusan], // 재신청 시도
    [SCHOOL_ID.cau, SCHOOL_ID.khu],
    [SCHOOL_ID.ewha, SCHOOL_ID.sogang],
  ];

  for (const [a, b] of actions) {
    const app = applyAllianceRequest(s, a, b);
    s = app.nextState;
    if (app.result.success && app.result.request) {
      const acc = acceptAllianceRequest(s, app.result.request.id);
      s = acc.nextState;
    }
  }

  const ids = s.alliances.map((a) => a.id);
  const uniqueIds = new Set(ids);
  assert.equal(uniqueIds.size, ids.length, '모든 연합 ID는 유일해야 합니다');
});

test('P2-2 (a): 목업에서는 1개 학교가 최대 1개 연합에만 소속될 수 있다 (단일 연합 소속 불변식)', () => {
  // korea는 이미 alliance-sky 소속
  const res1 = applyAllianceRequest(INITIAL_ALLIANCE_STATE, SCHOOL_ID.hanyang, SCHOOL_ID.korea);
  assert.equal(res1.result.success, false);
  if (!res1.result.success) {
    assert.match(res1.result.error, /이미 다른 연합/);
  }

  // snu는 이미 alliance-capital 소속
  const res2 = applyAllianceRequest(INITIAL_ALLIANCE_STATE, SCHOOL_ID.snu, SCHOOL_ID.hanyang);
  assert.equal(res2.result.success, false);
  if (!res2.result.success) {
    assert.match(res2.result.error, /이미 다른 연합/);
  }

  // hanyang과 pusan 결성 후, hanyang이 제3 학교(cau)에 신청 시 거절
  const step1 = applyAllianceRequest(INITIAL_ALLIANCE_STATE, SCHOOL_ID.hanyang, SCHOOL_ID.pusan);
  assert.equal(step1.result.success, true);
  if (!step1.result.success || !step1.result.request) throw new Error('step1 failed');
  const step2 = acceptAllianceRequest(step1.nextState, step1.result.request.id);
  assert.equal(step2.result.success, true);

  const res3 = applyAllianceRequest(step2.nextState, SCHOOL_ID.hanyang, SCHOOL_ID.cau);
  assert.equal(res3.result.success, false);
  if (!res3.result.success) {
    assert.match(res3.result.error, /신청 학교는 이미 다른 연합/);
  }

  // 수락 시점에도 re-validation에 의해 중복 소속 거절
  const stalePendingState = {
    ...step2.nextState,
    requests: [
      {
        id: 'stale-req-1',
        requesterSchoolId: SCHOOL_ID.hanyang,
        recipientSchoolId: SCHOOL_ID.cau,
        durationHours: 24,
        status: 'pending' as const,
        createdAt: 1000,
        updatedAt: 1000,
      },
      ...step2.nextState.requests,
    ],
  };
  const acceptReval = acceptAllianceRequest(stalePendingState, 'stale-req-1');
  assert.equal(acceptReval.result.success, false);
  if (!acceptReval.result.success) {
    assert.match(acceptReval.result.error, /이미 다른 연합/);
  }

  // 모든 연합 내 학교 소속 카운트 검증: 어떤 학교도 2개 이상의 연합에 소속될 수 없음
  const schoolCountMap = new Map<SchoolId, number>();
  for (const a of step2.nextState.alliances) {
    for (const sid of a.schoolIds) {
      schoolCountMap.set(sid, (schoolCountMap.get(sid) ?? 0) + 1);
    }
  }
  for (const [sid, count] of schoolCountMap.entries()) {
    assert.equal(count, 1, `학교 ${sid}의 소속 연합 수가 1이어야 합니다 (현재: ${count})`);
  }
});

test('P2-2 (b): 초기 시드 연합(INITIAL_MOCK_ALLIANCES) 내 모든 학교는 유일하며 hanyang은 소속 연합이 없다', () => {
  const seedSchoolCounts = new Map<SchoolId, number>();
  for (const a of INITIAL_MOCK_ALLIANCES) {
    for (const sid of a.schoolIds) {
      seedSchoolCounts.set(sid, (seedSchoolCounts.get(sid) ?? 0) + 1);
    }
  }

  for (const [sid, count] of seedSchoolCounts.entries()) {
    assert.equal(count, 1, `시드 연합 내 학교 ${sid}는 유일해야 합니다 (출현 횟수: ${count})`);
  }

  assert.equal(getAllianceForSchool(INITIAL_MOCK_ALLIANCES, SCHOOL_ID.hanyang, Date.now()), undefined);
});

test('연합 정원 불변식: 연합은 정확히 2개교다 (시드와 수락으로 만든 연합 모두, 3개교 이상 없음)', () => {
  assert.equal(ALLIANCE_SIZE, 2);
  for (const a of INITIAL_MOCK_ALLIANCES) {
    assert.equal(a.schoolIds.length, ALLIANCE_SIZE, `${a.id}는 ${a.schoolIds.length}개교`);
  }

  const applied = applyAllianceRequest(INITIAL_ALLIANCE_STATE, SCHOOL_ID.hanyang, SCHOOL_ID.pusan, 1000);
  assert.equal(applied.result.success, true);
  const accepted = acceptAllianceRequest(applied.nextState, applied.result.request!.id, 2000);
  assert.equal(accepted.result.success, true);
  assert.deepEqual(accepted.result.alliance!.schoolIds, [SCHOOL_ID.hanyang, SCHOOL_ID.pusan]);

  // 성립한 연합에 3번째 학교는 들어갈 수 없다
  const third = applyAllianceRequest(accepted.nextState, SCHOOL_ID.gachon, SCHOOL_ID.hanyang, 3000);
  assert.equal(third.result.success, false);
  for (const a of accepted.nextState.alliances) assert.equal(a.schoolIds.length, ALLIANCE_SIZE);
});

test('P2-3 (a): 동일 틱(same tick / 단일 act) 내 동일 학교 쌍 중복 신청 시 정확히 1건만 성공하고 2번째는 거절된다', () => {
  cleanup();
  let allianceValue: ReturnType<typeof useAlliance>;
  function Probe() {
    allianceValue = useAlliance();
    return null;
  }

  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(AllianceProvider, null, React.createElement(Probe)),
    ),
  );

  let res1: any;
  let res2: any;
  act(() => {
    res1 = allianceValue.applyRequest(SCHOOL_ID.pusan);
    res2 = allianceValue.applyRequest(SCHOOL_ID.pusan);
  });

  assert.equal(res1.success, true);
  assert.equal(res2.success, false);
  assert.match(res2.error, /이미 대기 중인/);
  assert.equal(allianceValue!.sentRequests.length, 1);
});

test('P2-3 (b): 동일 틱 내 동일 요청 2회 연속 수락 시 정확히 1건만 성공하고 2번째는 거절된다', () => {
  cleanup();
  let allianceValue: ReturnType<typeof useAlliance>;
  function Probe() {
    allianceValue = useAlliance();
    return null;
  }

  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(AllianceProvider, null, React.createElement(Probe)),
    ),
  );

  let reqId = '';
  act(() => {
    const res = allianceValue.applyRequest(SCHOOL_ID.pusan);
    assert.equal(res.success, true);
    reqId = res.request!.id;
  });

  let acc1: any;
  let acc2: any;
  act(() => {
    acc1 = allianceValue.demoAcceptRequest(reqId);
    acc2 = allianceValue.demoAcceptRequest(reqId);
  });

  assert.equal(acc1.success, true);
  assert.equal(acc2.success, false);
  assert.match(acc2.error, /이미 처리된/);
  assert.ok(allianceValue!.currentAlliance);
});

test('P2-3 (c): 동일 틱 내 수락 직후 거절 호출 시 수락만 성공하고 거절은 이미 처리되어 거절된다', () => {
  cleanup();
  let allianceValue: ReturnType<typeof useAlliance>;
  function Probe() {
    allianceValue = useAlliance();
    return null;
  }

  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(AllianceProvider, null, React.createElement(Probe)),
    ),
  );

  let reqId = '';
  act(() => {
    const res = allianceValue.applyRequest(SCHOOL_ID.pusan);
    assert.equal(res.success, true);
    reqId = res.request!.id;
  });

  let accRes: any;
  let rejRes: any;
  act(() => {
    accRes = allianceValue.demoAcceptRequest(reqId);
    rejRes = allianceValue.demoRejectRequest(reqId);
  });

  assert.equal(accRes.success, true);
  assert.equal(rejRes.success, false);
  assert.match(rejRes.error, /이미 처리된/);
  assert.ok(allianceValue!.currentAlliance);
});

// ---------------------------------------------------------------------------
// 2단계: 연합 기간·만료, 리더 없음(1명 수락 시 성립), 동시 제안과 자동 거절
// (.agents/01-PLAN/261112-1206-alliance_battle_rule_update_plan.md §7 2단계)
// ---------------------------------------------------------------------------
const HOUR = 3600_000;

test('연합 기간: 6~72시간 정수만 허용하고, 기본값은 24시간이다', () => {
  for (const [hours, ok] of [[5, false], [6, true], [72, true], [73, false], [6.5, false]] as const) {
    const res = applyAllianceRequest(INITIAL_ALLIANCE_STATE, SCHOOL_ID.hanyang, SCHOOL_ID.pusan, 1000, hours);
    assert.equal(res.result.success, ok, `${hours}시간`);
    if (!res.result.success) assert.match(res.result.error, /유효하지 않은 연합 기간/);
    else assert.equal(res.result.request!.durationHours, hours);
  }
  const byDefault = applyAllianceRequest(INITIAL_ALLIANCE_STATE, SCHOOL_ID.hanyang, SCHOOL_ID.pusan, 1000);
  assert.equal(byDefault.result.success && byDefault.result.request!.durationHours, 24);
});

test('동시 제안 허용: 하나가 수락되면 두 학교에 걸린 나머지 대기 제안만 자동 거절(capacity)된다', () => {
  let s = INITIAL_ALLIANCE_STATE;
  const send = (from: SchoolId, to: SchoolId, at: number) => {
    const r = applyAllianceRequest(s, from, to, at, 12);
    assert.equal(r.result.success, true, `${from}→${to}`);
    s = r.nextState;
    return r.result.success ? r.result.request!.id : '';
  };
  const accepted = send(SCHOOL_ID.hanyang, SCHOOL_ID.pusan, 1000);
  const sameRequester = send(SCHOOL_ID.hanyang, SCHOOL_ID.knu, 1001); // 한 학교가 여러 곳에 동시에 제안
  const sameRecipient = send(SCHOOL_ID.gachon, SCHOOL_ID.pusan, 1002);
  const unrelated = send(SCHOOL_ID.hufs, SCHOOL_ID.gachon, 1003);

  const res = acceptAllianceRequest(s, accepted, 2000);
  assert.equal(res.result.success, true);
  if (!res.result.success) return;
  assert.deepEqual([...res.result.autoRejectedRequestIds!].sort(), [sameRequester, sameRecipient].sort());

  const byId = (id: string) => res.nextState.requests.find((r) => r.id === id)!;
  assert.equal(byId(accepted).status, 'accepted');
  for (const id of [sameRequester, sameRecipient]) {
    assert.equal(byId(id).status, 'rejected');
    assert.equal(byId(id).closedReason, 'capacity');
  }
  assert.equal(byId(unrelated).status, 'pending', '두 학교와 무관한 제안은 그대로 둔다');

  // 성립한 연합: 수락 시각부터 제안 측이 정한 기간
  const alliance = res.result.alliance!;
  assert.equal(alliance.createdAt, 2000);
  assert.equal(alliance.durationHours, 12);
  assert.equal(alliance.expiresAt, 2000 + 12 * HOUR);
});

test('연합 만료: 만료 시각부터 미소속이 되고, 같은 학교끼리 다시 맺으면 새 연합(다른 ID)이 생긴다', () => {
  const t0 = 10_000;
  const applied = applyAllianceRequest(INITIAL_ALLIANCE_STATE, SCHOOL_ID.hanyang, SCHOOL_ID.pusan, t0, 6);
  const first = acceptAllianceRequest(applied.nextState, applied.result.success ? applied.result.request!.id : '', t0);
  assert.equal(first.result.success, true);
  const alliance = first.result.success ? first.result.alliance! : undefined!;
  const expiry = t0 + 6 * HOUR;

  assert.equal(getAllianceForSchool(first.nextState.alliances, SCHOOL_ID.hanyang, expiry - 1)?.id, alliance.id);
  assert.equal(formatAllianceRemainingTime(alliance, expiry - 90 * 60_000), '만료까지 1시간 30분 남음');
  const beforeExpiry = applyAllianceRequest(first.nextState, SCHOOL_ID.hanyang, SCHOOL_ID.pusan, expiry - 1);
  assert.equal(beforeExpiry.result.success, false);
  if (!beforeExpiry.result.success) assert.match(beforeExpiry.result.error, /이미 같은 연합/);

  assert.equal(isAllianceActive(alliance, expiry), false);
  assert.equal(getAllianceForSchool(first.nextState.alliances, SCHOOL_ID.hanyang, expiry), undefined);
  assert.equal(getLatestExpiredAllianceForSchool(first.nextState.alliances, SCHOOL_ID.hanyang, expiry)?.id, alliance.id);
  assert.equal(formatAllianceRemainingTime(alliance, expiry), '만료됨');

  const again = applyAllianceRequest(first.nextState, SCHOOL_ID.pusan, SCHOOL_ID.hanyang, expiry, 72);
  assert.equal(again.result.success, true);
  const second = acceptAllianceRequest(again.nextState, again.result.success ? again.result.request!.id : '', expiry + 1);
  assert.equal(second.result.success, true);
  if (second.result.success) {
    assert.notEqual(second.result.alliance!.id, alliance.id);
    assert.equal(getAllianceForSchool(second.nextState.alliances, SCHOOL_ID.hanyang, expiry + 1)?.id, second.result.alliance!.id);
  }
});

test('시드 연합 불변식: 모두 기준 시각에 활성이고 기간은 6~72시간 정수, expiresAt = createdAt + 기간', () => {
  const base = Date.UTC(2026, 10, 12, 3);
  for (const a of createInitialMockAlliances(base)) {
    assert.ok(Number.isInteger(a.durationHours) && a.durationHours >= 6 && a.durationHours <= 72, `${a.id}: ${a.durationHours}h`);
    assert.equal(a.expiresAt - a.createdAt, a.durationHours * HOUR, a.id);
    assert.ok(a.createdAt <= base && isAllianceActive(a, base), `${a.id}는 기준 시각에 활성`);
  }
});

test('연합 신청 화면: 기간 선택·제안 대기 표시, 받은 제안을 일반 UI에서 수락하면 즉시 성립하고 내 대기 제안은 자동 거절된다', () => {
  cleanup();
  let allianceValue: ReturnType<typeof useAlliance>;
  function Probe() {
    allianceValue = useAlliance();
    return null;
  }
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(AllianceProvider, null, React.createElement(Probe), React.createElement(AllianceApplyScreenPhone)),
    ),
  );

  // 리더(관리자) 개념이 화면에 남아 있지 않다
  assertAbsent(screen.queryByText(/관리자/));

  // 부산대에 12시간 연합 제안
  act(() => {
    fireEvent.change(screen.getByRole('textbox', { name: '대상 학교 검색' }), { target: { value: '부산' } });
  });
  act(() => {
    fireEvent.click(screen.getByRole('button', { name: /부산대학교/ }));
  });
  act(() => {
    fireEvent.click(screen.getByRole('button', { name: '12h' }));
  });
  act(() => {
    fireEvent.click(screen.getByRole('button', { name: '연합 신청 보내기' }));
  });
  assert.equal(allianceValue!.sentRequests[0].durationHours, 12);
  const pusanButton = screen.getByRole('button', { name: /부산대학교/ }) as HTMLButtonElement;
  assert.equal(pusanButton.disabled, true, '대기 제안이 있는 학교는 다시 고를 수 없다');
  assert.match(pusanButton.textContent ?? '', /제안 대기 중/);

  // 다른 학교의 제안 도착 → 받은 제안 섹션과 리더 없음 규칙 안내
  act(() => {
    fireEvent.click(screen.getByRole('button', { name: '[시연] 다른 학교의 연합 제안 받기' }));
  });
  const received = screen.getByRole('region', { name: '받은 연합 제안' });
  assert.match(received.textContent ?? '', /1명이 수락하면 즉시 연합이 성립/);
  const proposal = allianceValue!.receivedRequests[0];
  assert.equal(proposal.status, 'pending');

  // 일반 UI에서 수락 → 즉시 성립, 부산대에 보낸 제안은 자동 거절
  act(() => {
    fireEvent.click(screen.getByRole('button', { name: /의 연합 제안 수락$/ }));
  });
  assert.ok(allianceValue!.currentAlliance);
  assert.deepEqual([...allianceValue!.currentAlliance!.schoolIds], [proposal.requesterSchoolId, SCHOOL_ID.hanyang]);
  assert.equal(allianceValue!.currentAlliance!.durationHours, proposal.durationHours);
  assert.ok(screen.getByText(/이 결성되었습니다/));
  assert.ok(screen.getByText('자동 거절 (다른 연합 성립)'));
  assertAbsent(screen.queryByRole('region', { name: '받은 연합 제안' }), '처리된 제안은 목록에서 빠진다');
  assert.ok(screen.getByRole('note'), '이미 연합에 소속되어 있다는 안내');
});

test('연합 신청 화면: 나에게 제안한 학교는 목록에서 고를 수 없고 받은 제안으로 안내한다 (같은 두 학교 사이 대기 제안 1건)', () => {
  cleanup();
  let allianceValue: ReturnType<typeof useAlliance>;
  function Probe() {
    allianceValue = useAlliance();
    return null;
  }
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(AllianceProvider, null, React.createElement(Probe), React.createElement(AllianceApplyScreenPhone)),
    ),
  );

  act(() => {
    fireEvent.click(screen.getByRole('button', { name: '[시연] 다른 학교의 연합 제안 받기' }));
  });
  const proposerId = allianceValue!.receivedRequests[0].requesterSchoolId;
  const proposerName = getSchoolInfo(proposerId).name;
  act(() => {
    fireEvent.change(screen.getByRole('textbox', { name: '대상 학교 검색' }), { target: { value: proposerName } });
  });
  // 받은 제안 섹션의 수락/거절 버튼도 학교 이름을 담으므로 후보 목록 버튼만 고른다
  const proposerButton = screen
    .getAllByRole('button', { name: new RegExp(proposerName) })
    .find((b) => !/연합 제안 (수락|거절)$/.test(b.getAttribute('aria-label') ?? '')) as HTMLButtonElement;
  assert.equal(proposerButton.disabled, true, '받은 대기 제안이 있는 학교에 다시 제안하면 중복 오류가 나므로 고를 수 없다');
  assert.match(proposerButton.textContent ?? '', /받은 제안 있음/);
});

test('받은 제안만 수락할 수 있다: 내가 보낸 제안은 일반 수락 API로 수락되지 않는다', () => {
  cleanup();
  let allianceValue: ReturnType<typeof useAlliance>;
  function Probe() {
    allianceValue = useAlliance();
    return null;
  }
  render(React.createElement(MockGameProvider, null, React.createElement(AllianceProvider, null, React.createElement(Probe))));

  let result: any;
  act(() => {
    const sent = allianceValue.applyRequest(SCHOOL_ID.pusan);
    result = allianceValue.acceptRequest(sent.success ? sent.request!.id : '');
  });
  assert.equal(result.success, false);
  assert.match(result.error, /우리 학교가 받은 제안만/);
  assertAbsent(allianceValue!.currentAlliance);
});

test('현재 연합 화면: 남은 기간을 보여 주고, 공용 가상 시계를 앞당기면 만료되어 미소속이 된다 (대결과 같은 시계)', () => {
  cleanup();
  const now = Date.now();
  const initialState = {
    ...INITIAL_ALLIANCE_STATE,
    alliances: [
      { id: 'alliance-hanyang-pusan', name: '한양대·부산대 연합', schoolIds: [SCHOOL_ID.hanyang, SCHOOL_ID.pusan], createdAt: now, durationHours: 6, expiresAt: now + 6 * HOUR },
      ...INITIAL_ALLIANCE_STATE.alliances,
    ],
  };
  let battleValue: ReturnType<typeof useBattle>;
  function BattleProbe() {
    battleValue = useBattle();
    return null;
  }
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        { initialState },
        React.createElement(BattleProvider, null, React.createElement(BattleProbe), React.createElement(CurrentAllianceScreenPhone)),
      ),
    ),
  );

  assert.match(screen.getByLabelText('연합 기간').textContent ?? '', /만료까지 [56]시간 \d+분 남음/);
  assert.equal(battleValue!.myAllianceId, 'alliance-hanyang-pusan');

  act(() => {
    fireEvent.click(screen.getByRole('button', { name: '+12h' }));
  });
  assert.equal(battleValue!.clockOffsetMs, 12 * HOUR, '대결 화면도 같은 가상 시계를 쓴다');
  assert.equal(battleValue!.myAllianceId, null, '만료된 연합으로는 대결에 참여하지 않는다');
  assert.ok(screen.getByText('소속 연합 없음'));
  assert.match(screen.getByRole('note').textContent ?? '', /한양대·부산대 연합은 .* 만료되었습니다/);
});
