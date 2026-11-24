import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds.ts';
import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { assertAbsent } from './helpers/domAssert';
import type { BattleState } from '../src/data/battleModel.ts';

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
  applyBattleRequest,
  getDefaultRequestedStartTime,
  validateRequestedStartTime,
  parseKstDateTimeLocal,
  formatKstDateTimeLocal,
  cancelBattleRequest,
  acceptBattleRequest,
  rejectBattleRequest,
  demoCancelConfirmedBattle,
  determineBattleWinner,
  getBattleResultLabel,
  getEffectiveBattleStatus,
  formatBattleTimestamp,
  formatBattleRemainingTime,
  isAllianceOccupied,
  createInitialBattleState,
  getBattleAcceptDeadline,
  getBattleLatestEnd,
  getMaxBattleDurationHours,
  INITIAL_BATTLE_STATE,
  INITIAL_MOCK_BATTLES,
} = await import('../src/data/battleModel.ts');
const { MOCK_SHOP_CONFIG } = await import('../src/data/mockShopConfig.ts');
const { INITIAL_MOCK_ALLIANCES, INITIAL_ALLIANCE_STATE, createInitialMockAlliances, createInitialAllianceState } = await import('../src/data/allianceModel.ts');
const { BattleProvider, useBattle } = await import('../src/hooks/useMockBattle.tsx');
const { AllianceProvider } = await import('../src/hooks/useMockAlliance.tsx');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame.tsx');
const { SharedImageProvider, useImageStore } = await import('../src/hooks/useSharedImages.tsx');
const { BattleApplyScreenPhone } = await import('../src/components/BattleApplyScreenPhone.tsx');
const { BattleStatusScreenPhone } = await import('../src/components/BattleStatusScreenPhone.tsx');
const { default: App } = await import('../src/App.tsx');

afterEach(() => cleanup());
after(() => {
  assetHooks.deregister();
  dom.window.close();
});

test('1. 대결 신청 및 현황 화면 진입·복귀와 네비게이션 간 상태(대결, 연합, 게임) 보존', () => {
  cleanup();
  render(React.createElement(MockGameProvider, null, React.createElement(App)));

  // 1. 데모 상단 런처에서 '대결 신청' 진입 버튼 클릭 (초기 소속 연합 없음 상태 확인)
  const launcherBattleApplyBtn = testViewButton('⚔️ 대결 신청 (Battle Apply)');
  act(() => {
    fireEvent.click(launcherBattleApplyBtn);
  });
  assert.ok(screen.getByRole('heading', { name: '대결 신청' }));
  assert.ok(screen.getByText('소속 연합 없음'));

  // 2. 상단 런처 또는 헤더에서 '대결 현황' 진입 버튼 클릭
  const launcherBattleStatusBtn = screen.getByRole('button', { name: '대결 현황' });
  act(() => {
    fireEvent.click(launcherBattleStatusBtn);
  });
  assert.ok(screen.getByRole('heading', { name: '대결 현황' }));
  assert.ok(screen.getAllByText(/연합별 2개교/).length > 0);
  assert.equal(screen.queryAllByText(/연합별 3개교/).length, 0, '3:3 대결은 없다');

  // 3. '🛡️ 현재 연합 (Current Alliance)' 런처 버튼으로 현재 연합 화면 이동
  const launcherCurAllianceBtn = testViewButton('🛡️ 현재 연합 (Current Alliance)');
  act(() => {
    fireEvent.click(launcherCurAllianceBtn);
  });
  assert.ok(screen.getByRole('heading', { name: '현재 연합' }));

  // 4. 뒤로가기 클릭 -> 메인 게임 화면 복귀
  const backToGameBtn = screen.getByRole('button', { name: '뒤로가기' });
  act(() => {
    fireEvent.click(backToGameBtn);
  });
  assert.ok(screen.getAllByText(/한양대학교/).length > 0);

  // 5. 독립 화면 마운트 검증
  cleanup();
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        null,
        React.createElement(BattleProvider, null, React.createElement(BattleApplyScreenPhone)),
      ),
    ),
  );
  assert.ok(screen.getByRole('heading', { name: '대결 신청' }));
});

test('2. 2개교 연합끼리만(2:2) 대결 가능, 전이(pending→accept→active→finished), 무승부 샘플, 오류 재시도', () => {
  const state = INITIAL_BATTLE_STATE;
  const alliances = INITIAL_MOCK_ALLIANCES;

  // (a) 2개교가 아닌 연합은 신청·수락 모두 거절 (3개교 연합은 구현하지 않으며, 방어 검증만 남긴다)
  const oversized = { id: 'alliance-oversized', name: '3개교 연합(비정상)', schoolIds: [SCHOOL_ID.kaist, SCHOOL_ID.postech, SCHOOL_ID.unist], createdAt: 0, durationHours: 72, expiresAt: Date.now() + 72 * 3600_000 };
  const withOversized = [...alliances.filter((a) => a.id !== 'alliance-tech'), oversized];
  const mismatchRes = applyBattleRequest(state, withOversized, 'alliance-oversized', 'alliance-capital', 24, getDefaultRequestedStartTime(1000, state.config), 1000);
  assert.equal(mismatchRes.result.success, false);
  assert.match(mismatchRes.result.error!, /연합 규모 오류: 대결은 2개교 연합끼리만/);

  const seedPending = state.battles.find((b) => b.id === 'battle-sample-pending-2v2')!;
  const acceptOversized = acceptBattleRequest(state, withOversized.map((a) => (a.id === 'alliance-oversized' ? { ...a, id: 'alliance-tech' } : a)), seedPending.id, seedPending.createdAt);
  assert.equal(acceptOversized.result.success, false);
  assert.match(acceptOversized.result.error!, /연합 규모 오류/);

  // (b) 정상 신청 (sky -> capital, 2:2, 24h)
  const applyRes = applyBattleRequest(state, alliances, 'alliance-sky', 'alliance-capital', 24, getDefaultRequestedStartTime(2000, state.config), 2000);
  assert.equal(applyRes.result.success, true);
  const pendingBattle = applyRes.result.battle!;
  assert.equal(pendingBattle.status, 'pending');
  assert.equal(pendingBattle.requesterSchoolIds.length, 2);
  assert.equal(pendingBattle.recipientSchoolIds.length, 2);

  // (c) 시연 수락 -> 예정(scheduled) 및 고정 시계 경과에 따른 진행(active) 전이 (P3-1)
  const acceptRes = acceptBattleRequest(applyRes.nextState, alliances, pendingBattle.id, 2100);
  assert.equal(acceptRes.result.success, true);
  const acceptedBattle = acceptRes.result.battle!;
  assert.equal(acceptedBattle.status, 'scheduled');
  const delayMs = pendingBattle.requestedStartTime - 2100;
  assert.equal(acceptedBattle.scheduledStartTime, pendingBattle.requestedStartTime);
  assert.equal(acceptedBattle.scheduledEndTime, pendingBattle.requestedStartTime + 24 * 3600_000);

  // (d) 고정 시계 검증: 시작 전 scheduled, 진행 중 active, 종료 후 finished
  const effBeforeStart = getEffectiveBattleStatus(acceptedBattle, 2100);
  assert.equal(effBeforeStart, 'scheduled');

  const effInActive = getEffectiveBattleStatus(acceptedBattle, 2100 + delayMs + 1000);
  assert.equal(effInActive, 'active');

  const effAfterEnd = getEffectiveBattleStatus(acceptedBattle, 2100 + delayMs + 24 * 3600_000 + 1000);
  assert.equal(effAfterEnd, 'finished');

  // (e) 시드에 무승부 및 종료 샘플 존재 검증
  const drawSample = INITIAL_MOCK_BATTLES.find((b) => b.winnerAllianceId === 'draw');
  assert.ok(drawSample);
  assert.equal(drawSample.requesterSchoolIds.length, 2);
  assert.equal(drawSample.recipientSchoolIds.length, 2);
  assert.equal(drawSample.scores?.requesterScore, drawSample.scores?.recipientScore);

  // (f) 네트워크 오류 시뮬레이션 및 복구
  const failState = { ...state, simulateRequestFailure: true };
  const failRes = applyBattleRequest(failState, alliances, 'alliance-sky', 'alliance-capital', 24, getDefaultRequestedStartTime(3000, failState.config), 3000);
  assert.equal(failRes.result.success, false);
  assert.match(failRes.result.error!, /네트워크 연결 오류/);

  // (g) 취소 및 거절 전이 검증
  const cancelTestApp = applyBattleRequest(state, alliances, 'alliance-sky', 'alliance-capital', 24, getDefaultRequestedStartTime(4000, state.config), 4000);
  const cancelRes = cancelBattleRequest(cancelTestApp.nextState, cancelTestApp.result.battle!.id, 4100);
  assert.equal(cancelRes.result.success, true);
  assert.equal(cancelRes.result.battle!.status, 'cancelled');

  const rejectTestApp = applyBattleRequest(state, alliances, 'alliance-sky', 'alliance-capital', 24, getDefaultRequestedStartTime(5000, state.config), 5000);
  const rejectRes = rejectBattleRequest(rejectTestApp.nextState, rejectTestApp.result.battle!.id, 5100);
  assert.equal(rejectRes.result.success, true);
  assert.equal(rejectRes.result.battle!.status, 'rejected');
});

test('4. 기간 설정(min, max, 범위 밖 거절), 남은 시간 계산 및 종료 후 절대 active 미노출', () => {
  const { min, max } = MOCK_SHOP_CONFIG.battle.durationHours;
  const alliances = INITIAL_MOCK_ALLIANCES;
  const state = INITIAL_BATTLE_STATE;

  // 최소 미만 거절
  const underMin = applyBattleRequest(state, alliances, 'alliance-sky', 'alliance-capital', min - 1, getDefaultRequestedStartTime(1000, state.config), 1000);
  assert.equal(underMin.result.success, false);
  assert.match(underMin.result.error!, /유효하지 않은 대결 기간/);

  // 최대 초과 거절
  const overMax = applyBattleRequest(state, alliances, 'alliance-sky', 'alliance-capital', max + 1, getDefaultRequestedStartTime(1000, state.config), 1000);
  assert.equal(overMax.result.success, false);
  assert.match(overMax.result.error!, /유효하지 않은 대결 기간/);

  // 경계값(min, max)은 성공
  const atMin = applyBattleRequest(state, alliances, 'alliance-sky', 'alliance-capital', min, getDefaultRequestedStartTime(1000, state.config), 1000);
  assert.equal(atMin.result.success, true);

  // 종료 시각 이후 effective status는 결코 active가 아닌 finished
  const b = atMin.result.battle!;
  const accepted = acceptBattleRequest(atMin.nextState, alliances, b.id, 1000);
  assert.equal(accepted.result.success, true);

  const activeB = accepted.result.battle!;
  const startTime = activeB.scheduledStartTime!;
  const endTime = activeB.scheduledEndTime!;
  assert.equal(getEffectiveBattleStatus(activeB, startTime - 1), 'scheduled');
  assert.equal(getEffectiveBattleStatus(activeB, startTime + 1000), 'active');
  assert.equal(getEffectiveBattleStatus(activeB, endTime - 1), 'active');
  assert.equal(getEffectiveBattleStatus(activeB, endTime), 'finished');
  assert.equal(getEffectiveBattleStatus(activeB, endTime + 10000), 'finished');
});

test('5. 학교 이미지 변경 시 대결 화면의 로고가 즉시 갱신된다', () => {
  cleanup();
  let imageStore: ReturnType<typeof useImageStore>;
  function ImageProbe() {
    imageStore = useImageStore();
    return null;
  }

  render(
    React.createElement(
      SharedImageProvider,
      null,
      React.createElement(
        MockGameProvider,
        null,
        React.createElement(
          AllianceProvider,
          null,
          React.createElement(
            BattleProvider,
            null,
            React.createElement(ImageProbe),
            React.createElement(BattleStatusScreenPhone),
          ),
        ),
      ),
    ),
  );

  // KAIST('kaist') 로고 확인 (초기 폴백 '카')
  assert.ok(screen.getAllByText('카').length > 0);

  // 공유 이미지 저장소에 KAIST 소속 사용자('external-viewer') 권한으로 새 로고 적용
  act(() => {
    const success = imageStore.apply(
      'external-viewer',
      { type: 'school', id: SCHOOL_ID.kaist },
      'https://example.com/custom_kaist.svg',
      'req-kaist-battle-1',
    );
    assert.equal(success, true);
  });

  // 커스텀 이미지로 SharedImage가 즉각 갱신되어 alt="KAIST 이미지" 태그가 모든 해당 경기 카드에 렌더링됨
  const kaistImgs = screen.getAllByRole('img', { name: 'KAIST 이미지' });
  assert.ok(kaistImgs.length >= 2);
  for (const img of kaistImgs) {
    assert.equal(img.getAttribute('src'), 'https://example.com/custom_kaist.svg');
  }
});

test('6. 대결 시연 및 상태 조작은 실제 기여도, 학교/학과 점수, 클리커, 버프를 일체 변경하지 않는다', () => {
  cleanup();
  let gameValue: ReturnType<typeof useMockGame>;
  let battleValue: ReturnType<typeof useBattle>;

  function Probe() {
    gameValue = useMockGame();
    battleValue = useBattle();
    return null;
  }

  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        null,
        React.createElement(BattleProvider, null, React.createElement(Probe)),
      ),
    ),
  );

  const initialContribution = gameValue!.state.contribution;
  const initialSchoolScore = gameValue!.state.schoolScore;
  const initialClickerLevel = gameValue!.clicker.level;
  const initialMultiplier = gameValue!.normalPower;

  // 대결 수락 및 가상 시계 조작 실행
  act(() => {
    battleValue.demoAcceptRequest('battle-sample-pending-2v2');
    battleValue.fastForwardClock(48);
  });

  // 게임 상태 불변성 검증
  assert.equal(gameValue!.state.contribution, initialContribution);
  assert.equal(gameValue!.state.schoolScore, initialSchoolScore);
  assert.equal(gameValue!.clicker.level, initialClickerLevel);
  assert.equal(gameValue!.normalPower, initialMultiplier);
});

test('3. 단일 동시 상대 제한: A-B 예정·진행 중에는 A-C/B-D 신청과 오래된 수락을 거절하고, 종료·취소 후 해제한다', () => {
  const alliances = INITIAL_MOCK_ALLIANCES;
  let s: BattleState = { ...INITIAL_BATTLE_STATE, battles: [] };
  const now = 20000;

  // Stale request 사전 생성: A(sky)와 C(metro) 간의 pending 요청
  const staleApp = applyBattleRequest(s, alliances, 'alliance-sky', 'alliance-metro', 24, getDefaultRequestedStartTime(now, s.config), now);
  assert.equal(staleApp.result.success, true);
  s = staleApp.nextState;
  const staleReqId = staleApp.result.battle!.id;

  // A-B 신청 및 수락
  const abApp = applyBattleRequest(s, alliances, 'alliance-sky', 'alliance-capital', 24, getDefaultRequestedStartTime(now + 10, s.config), now + 10);
  assert.equal(abApp.result.success, true);
  s = abApp.nextState;

  const abAcc = acceptBattleRequest(s, alliances, abApp.result.battle!.id, now + 20);
  assert.equal(abAcc.result.success, true);
  s = abAcc.nextState;
  const abBattle = abAcc.result.battle!;

  // 1. 수락 직후: 상태는 'scheduled', 실효 상태도 'scheduled'
  assert.equal(abBattle.status, 'scheduled');
  assert.equal(getEffectiveBattleStatus(abBattle, now + 20), 'scheduled');

  // 2. 예정(scheduled) 상태에서 A와 B 모두 점유됨
  assert.equal(isAllianceOccupied(s.battles, 'alliance-sky', now + 20), true);
  assert.equal(isAllianceOccupied(s.battles, 'alliance-capital', now + 20), true);

  // 3. 예정 상태에서 신규 A-C 및 B-D 신청 거절
  const acAppScheduled = applyBattleRequest(s, alliances, 'alliance-sky', 'alliance-metro', 24, getDefaultRequestedStartTime(now + 30, s.config), now + 30);
  assert.equal(acAppScheduled.result.success, false);
  assert.match(acAppScheduled.result.error!, /단일 동시 상대 제한/);

  const bdAppScheduled = applyBattleRequest(s, alliances, 'alliance-sinchon', 'alliance-capital', 24, getDefaultRequestedStartTime(now + 30, s.config), now + 30);
  assert.equal(bdAppScheduled.result.success, false);
  assert.match(bdAppScheduled.result.error!, /단일 동시 상대 제한/);

  // 4. 예정 상태에서 기존 stale A-C 수락 거절
  const staleAccScheduled = acceptBattleRequest(s, alliances, staleReqId, now + 40);
  assert.equal(staleAccScheduled.result.success, false);
  assert.match(staleAccScheduled.result.error!, /단일 동시 상대 제한/);

  // 5. 시작 시각 도달 후 진행(active) 상태 전이 검증
  const activeTime = abBattle.scheduledStartTime! + 1000;
  assert.equal(getEffectiveBattleStatus(abBattle, activeTime), 'active');
  assert.equal(isAllianceOccupied(s.battles, 'alliance-sky', activeTime), true);
  assert.equal(isAllianceOccupied(s.battles, 'alliance-capital', activeTime), true);

  // 5-1. 진행(active) 상태에서도 신규 A-C 및 B-D 신청과 stale A-C 수락을 거절
  const acAppActive = applyBattleRequest(s, alliances, 'alliance-sky', 'alliance-metro', 24, getDefaultRequestedStartTime(activeTime, s.config), activeTime);
  assert.equal(acAppActive.result.success, false);
  assert.match(acAppActive.result.error!, /단일 동시 상대 제한/);

  const bdAppActive = applyBattleRequest(s, alliances, 'alliance-sinchon', 'alliance-capital', 24, getDefaultRequestedStartTime(activeTime, s.config), activeTime);
  assert.equal(bdAppActive.result.success, false);
  assert.match(bdAppActive.result.error!, /단일 동시 상대 제한/);

  const staleAccActive = acceptBattleRequest(s, alliances, staleReqId, activeTime);
  assert.equal(staleAccActive.result.success, false);
  assert.match(staleAccActive.result.error!, /단일 동시 상대 제한/);

  // 5-2. 취소 없이 종료 시각이 지나면 점유가 해제되어 새 A-C 신청이 가능
  const afterEnd = abBattle.scheduledEndTime! + 1000;
  assert.equal(getEffectiveBattleStatus(abBattle, afterEnd), 'finished');
  assert.equal(isAllianceOccupied(s.battles, 'alliance-sky', afterEnd), false);
  assert.equal(isAllianceOccupied(s.battles, 'alliance-capital', afterEnd), false);
  const finishedState = cancelBattleRequest(s, staleReqId, afterEnd).nextState;
  const acAppAfterFinish = applyBattleRequest(finishedState, alliances, 'alliance-sky', 'alliance-metro', 24, getDefaultRequestedStartTime(afterEnd + 10, finishedState.config), afterEnd + 10);
  assert.equal(acAppAfterFinish.result.success, true);

  // 6. 시연용 확정 대결 취소(demoCancelConfirmedBattle)로 점유 해제
  const cancelConfirmedRes = demoCancelConfirmedBattle(s, abBattle.id, activeTime);
  assert.equal(cancelConfirmedRes.result.success, true);
  assert.equal(cancelConfirmedRes.result.battle!.status, 'cancelled');
  s = cancelConfirmedRes.nextState;

  // 7. 취소 후 양측 점유 해제 확인
  assert.equal(isAllianceOccupied(s.battles, 'alliance-sky', activeTime), false);
  assert.equal(isAllianceOccupied(s.battles, 'alliance-capital', activeTime), false);

  // 8. 점유 해제 후 stale 요청 취소 및 신규 A-C 신청 성공
  s = cancelBattleRequest(s, staleReqId, activeTime).nextState;
  const acAppAfterCancel = applyBattleRequest(s, alliances, 'alliance-sky', 'alliance-metro', 24, getDefaultRequestedStartTime(activeTime + 10, s.config), activeTime + 10);
  assert.equal(acAppAfterCancel.result.success, true);
});

test('3-1. 같은 tick에 같은 대결 요청을 두 번 수락하면 정확히 한 번만 성공한다', () => {
  cleanup();
  let battleValue: ReturnType<typeof useBattle>;
  function Probe() {
    battleValue = useBattle();
    return null;
  }

  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        null,
        React.createElement(BattleProvider, null, React.createElement(Probe)),
      ),
    ),
  );

  const pendingId = 'battle-sample-pending-2v2';
  let acc1: any;
  let acc2: any;
  act(() => {
    acc1 = battleValue.demoAcceptRequest(pendingId);
    acc2 = battleValue.demoAcceptRequest(pendingId);
  });
  assert.equal(acc1.success, true);
  assert.equal(acc2.success, false);
  assert.match(acc2.error, /이미 처리된/);
  assert.equal(battleValue!.state.battles.filter((b) => b.id === pendingId).length, 1);
});

test('8. P3-2: 대결 설정 주입 가능성: 대체 설정 반영, 범위 밖 거절, UI 동적 반영', () => {
  const alliances = INITIAL_MOCK_ALLIANCES;
  const customConfig = {
    durationHours: { min: 1, max: 5, default: 2, presets: [1, 3, 6] },
    startTime: { ...MOCK_SHOP_CONFIG.battle.startTime, allowedStartHoursKst: { from: 10, to: 20 }, stepMinutes: 60 },
  };
  const customState = createInitialBattleState(10000, customConfig);
  const start = getDefaultRequestedStartTime(10000, customConfig);

  // (a) 6시간은 기본 설정(1~24h)에서는 허용되나 customConfig(max 5)에서는 거절
  const overCustomMax = applyBattleRequest(customState, alliances, 'alliance-sky', 'alliance-capital', 6, start, 10000, customConfig);
  assert.equal(overCustomMax.result.success, false);
  assert.match(overCustomMax.result.error!, /1시간 ~ 5시간 허용/);

  // (b) 1시간은 customConfig(min 1)에서 정상 신청
  const atCustomMin = applyBattleRequest(customState, alliances, 'alliance-sky', 'alliance-capital', 1, start, 10000, customConfig);
  assert.equal(atCustomMin.result.success, true);

  // (c) 희망 시작 시각이 그대로 확정되는지 검증
  const acceptedCustom = acceptBattleRequest(atCustomMin.nextState, alliances, atCustomMin.result.battle!.id, 10000, customConfig);
  assert.equal(acceptedCustom.result.success, true);
  const cb = acceptedCustom.result.battle!;
  assert.equal(cb.scheduledStartTime, atCustomMin.result.battle!.requestedStartTime);
  assert.equal(cb.scheduledEndTime, cb.scheduledStartTime! + 3600_000);

  // (d) UI: 주입한 범위와 프리셋을 쓰고, 범위 밖 프리셋(6h)은 숨긴다
  cleanup();
  const allianceState = { ...INITIAL_ALLIANCE_STATE, alliances: [...INITIAL_ALLIANCE_STATE.alliances, { id: 'alliance-hanyang-pusan', name: '한양대·부산대 연합', schoolIds: [SCHOOL_ID.hanyang, SCHOOL_ID.pusan], createdAt: 1000, durationHours: 72, expiresAt: Date.now() + 72 * 3600_000 }] };
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        { initialState: allianceState } as any,
        React.createElement(BattleProvider, { config: customConfig }, React.createElement(BattleApplyScreenPhone)),
      ),
    ),
  );
  assert.ok(screen.getByText(/허용: 1h ~ 5h/));
  assert.ok(screen.getByRole('button', { name: '1h' }));
  assert.ok(screen.getByRole('button', { name: '3h' }));
  assertAbsent(screen.queryByRole('button', { name: '6h' }));
  assert.equal(screen.getByRole('status', { name: '경기 진행 기간 선택값' }).textContent, '2시간');
});

test('2:2 고정 불변식: 대결 시드는 양측 2개교·기간 1~24h이고, 신청 화면에 형식 선택이 없으며 프리셋 기간으로 신청된다', () => {
  const { min, max } = MOCK_SHOP_CONFIG.battle.durationHours;
  assert.deepEqual([min, max], [1, 24]);
  for (const b of INITIAL_MOCK_BATTLES) {
    assert.equal(b.requesterSchoolIds.length, 2, b.id);
    assert.equal(b.recipientSchoolIds.length, 2, b.id);
    assert.ok(b.durationHours >= min && b.durationHours <= max, `${b.id}: ${b.durationHours}h`);
    assert.equal('format' in b, false, `${b.id}에 format 필드가 남아 있음`);
  }

  cleanup();
  const allianceState = { ...INITIAL_ALLIANCE_STATE, alliances: [...INITIAL_ALLIANCE_STATE.alliances, { id: 'alliance-hanyang-pusan', name: '한양대·부산대 연합', schoolIds: [SCHOOL_ID.hanyang, SCHOOL_ID.pusan], createdAt: 1000, durationHours: 72, expiresAt: Date.now() + 72 * 3600_000 }] };
  let battleValue: ReturnType<typeof useBattle>;
  function Probe() { battleValue = useBattle(); return null; }
  render(React.createElement(MockGameProvider, null, React.createElement(AllianceProvider, { initialState: allianceState } as any, React.createElement(BattleProvider, null, React.createElement(Probe), React.createElement(BattleApplyScreenPhone)))));

  assertAbsent(screen.queryByText(/대결 형식 선택/));
  assertAbsent(screen.queryByRole('button', { name: /3:3 대결/ }));
  assertAbsent(screen.queryByRole('spinbutton', { name: '경기 진행 기간' }));
  assert.ok(screen.getByRole('group', { name: '경기 진행 기간' }));

  fireEvent.click(screen.getByRole('button', { name: /연고 메이저 연합/ }));
  fireEvent.click(screen.getByRole('button', { name: '12h' }));
  fireEvent.click(screen.getByRole('button', { name: '경기 진행 기간 1시간 늘리기' }));
  assert.match(screen.getByText(/• 형식:/).textContent ?? '', /2:2 \(연합당 2개교\)/);
  const before = battleValue!.state.battles.length;
  fireEvent.click(screen.getByRole('button', { name: '대결 신청 보내기' }));
  assert.equal(battleValue!.state.battles.length, before + 1);
  assert.equal(battleValue!.state.battles[0].durationHours, 13);
});

test('9. P3-3: 시계에 의해 종료된 대결 및 무승부/리드 결과 판정, 상대 연합 승리 오표기 방지, 실제 시계 기준 시드 active 검증', () => {
  // 1. determineBattleWinner 순수 함수 검증
  const dummyRequester = 'alliance-req';
  const dummyRecipient = 'alliance-rec';

  const reqWinBattle: any = {
    id: 'test-req-win',
    requesterAllianceId: dummyRequester,
    recipientAllianceId: dummyRecipient,
    scores: { requesterScore: 12000, recipientScore: 11000 },
  };
  assert.equal(determineBattleWinner(reqWinBattle), 'requester');
  assert.equal(getBattleResultLabel(reqWinBattle, '신청연합', '상대연합'), '신청연합 승리');

  const recWinBattle: any = {
    id: 'test-rec-win',
    requesterAllianceId: dummyRequester,
    recipientAllianceId: dummyRecipient,
    scores: { requesterScore: 9000, recipientScore: 15000 },
  };
  assert.equal(determineBattleWinner(recWinBattle), 'recipient');
  assert.equal(getBattleResultLabel(recWinBattle, '신청연합', '상대연합'), '상대연합 승리');

  // 0:0 동점 대결 -> 결코 recipient wins가 아니라 'draw'
  const zeroZeroBattle: any = {
    id: 'test-zero-zero',
    requesterAllianceId: dummyRequester,
    recipientAllianceId: dummyRecipient,
    scores: { requesterScore: 0, recipientScore: 0 },
  };
  assert.equal(determineBattleWinner(zeroZeroBattle), 'draw');
  assert.equal(getBattleResultLabel(zeroZeroBattle, '신청연합', '상대연합'), '무승부 (DRAW)');

  // 2. 실제 시계(Date.now()) 기준 시드 active 샘플 검증
  const realNow = Date.now();
  const seedActive = INITIAL_MOCK_BATTLES.find((b) => b.id === 'battle-sample-active-2v2');
  assert.ok(seedActive);
  assert.equal(getEffectiveBattleStatus(seedActive, realNow), 'active');
  assert.equal(determineBattleWinner(seedActive), 'requester'); // 12450 > 11800

  // 3. BattleStatusScreenPhone 화면 렌더링 검증
  cleanup();
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        null,
        React.createElement(BattleProvider, null, React.createElement(BattleStatusScreenPhone)),
      ),
    ),
  );

  // '리드 중' (경인연합 우세 진행 중) 렌더링 확인
  assert.ok(screen.getAllByText(/리드 중/).length > 0);

  // '무승부 (DRAW)' 및 '결과: 샘플 점수 기준' 렌더링 확인
  assert.ok(screen.getAllByText('무승부 (DRAW)').length > 0);
  assert.ok(screen.getAllByText('결과: 샘플 점수 기준').length > 0);
});

test('P4-2: formatBattleTimestamp 및 formatBattleRemainingTime 순수 헬퍼 고정 시계 검증', () => {
  // 1. formatBattleTimestamp 결정론적 포맷팅 (UTC+9 고정)
  // Date.UTC(2026, 10, 7, 3, 30, 0) => KST 2026-11-07 12:30
  const fixedTs = Date.UTC(2026, 10, 7, 3, 30, 0);
  assert.equal(formatBattleTimestamp(fixedTs), '11/07 12:30');
  assert.equal(formatBattleTimestamp(undefined), '-');
  assert.equal(formatBattleTimestamp(Number.NaN), '-');

  // 2. formatBattleRemainingTime 시계 경계값 검증
  // startTime = 10_000_000, endTime = 20_000_000 (duration = 10_000_000 ms ~= 2.77 hours)
  const scheduledStartTime = 10_000_000;
  const scheduledEndTime = 20_000_000;
  const testBattle: any = {
    id: 'test-timing-battle',
    status: 'scheduled',
    scheduledStartTime,
    scheduledEndTime,
    durationHours: 24,
  };

  // (a) before start (now = 5_000_000): scheduled, diff = 5_000_000 ms (1시간 23분)
  const beforeStart = formatBattleRemainingTime(testBattle, 5_000_000);
  assert.match(beforeStart, /^시작까지 \d+시간 \d+분 남음$/);
  assert.equal(beforeStart, '시작까지 1시간 23분 남음');

  // (b) exactly at start (now = 10_000_000): active, diff = 10_000_000 ms (2시간 46분)
  const atStart = formatBattleRemainingTime(testBattle, 10_000_000);
  assert.match(atStart, /^종료까지 \d+시간 \d+분 남음$/);
  assert.equal(atStart, '종료까지 2시간 46분 남음');

  // (c) between start and end (now = 15_000_000): active, diff = 5_000_000 ms (1시간 23분)
  const between = formatBattleRemainingTime(testBattle, 15_000_000);
  assert.match(between, /^종료까지 \d+시간 \d+분 남음$/);
  assert.equal(between, '종료까지 1시간 23분 남음');

  // (d) exactly at end (now = 20_000_000): finished
  const atEnd = formatBattleRemainingTime(testBattle, 20_000_000);
  assert.equal(atEnd, '종료됨');

  // (e) after end (now = 25_000_000): finished
  const afterEnd = formatBattleRemainingTime(testBattle, 25_000_000);
  assert.equal(afterEnd, '종료됨');

  // (f) pending battle
  const pendingBattle: any = { id: 'pending-1', status: 'pending' };
  assert.equal(formatBattleRemainingTime(pendingBattle, 5_000_000), '수락 대기 중');
});

test('P4-2: BattleStatusScreenPhone 화면에서 시작/종료 시각 및 예정(시작까지)/진행(종료까지) 안내 렌더링', (t) => {
  const baseTime = Date.UTC(2026, 10, 7, 0, 0, 0); // KST 09:00
  t.mock.method(Date, 'now', () => baseTime);

  const scheduledStart = baseTime + 3600_000; // KST 10:00
  const scheduledEnd = scheduledStart + 24 * 3600_000; // Next day KST 10:00

  const customState = {
    ...createInitialBattleState(baseTime, MOCK_SHOP_CONFIG.battle),
    battles: [
      {
        id: 'test-scheduled-card',
        requesterAllianceId: 'alliance-seoul-univs',
        recipientAllianceId: 'alliance-gyeongin',
        requesterSchoolIds: [SCHOOL_ID.hanyang, SCHOOL_ID.pusan],
        recipientSchoolIds: [SCHOOL_ID.yonsei, SCHOOL_ID.korea],
        durationHours: 24,
        status: 'scheduled' as const,
        createdAt: baseTime,
        requestedStartTime: scheduledStart,
        scheduledStartTime: scheduledStart,
        scheduledEndTime: scheduledEnd,
        updatedAt: baseTime,
      },
    ],
  };

  cleanup();
  // 1. baseTime 시점: 경기 예정 상태 (시작 전)
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        null,
        React.createElement(
          BattleProvider,
          { initialState: customState },
          React.createElement(BattleStatusScreenPhone),
        ),
      ),
    ),
  );

  // 시작 / 종료 시각 렌더링 확인
  assert.ok(screen.getAllByText(/시작: 11\/07 10:00/).length > 0);
  assert.ok(screen.getAllByText(/종료: 11\/08 10:00/).length > 0);
  // 예정 상태: '시작까지' 카운트다운 확인
  assert.ok(screen.getByText('시작까지 1시간 0분 남음'));

  // 2. baseTime + 2시간 경과: 진행 중 상태
  cleanup();
  // 공용 가상 시계(연합 만료와 같은 시계)를 2시간 앞당긴다: now = baseTime + 2h (scheduledStart + 1h)
  const activeState = customState;

  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        { initialClockOffsetMs: 2 * 3600_000 },
        React.createElement(
          BattleProvider,
          { initialState: activeState },
          React.createElement(BattleStatusScreenPhone),
        ),
      ),
    ),
  );

  // 진행 중 상태: '종료까지' 카운트다운 확인 (23시간 0분 남음)
  assert.ok(screen.getByText('종료까지 23시간 0분 남음'));
  assert.ok(screen.getAllByText(/시작: 11\/07 10:00/).length > 0);
  assert.ok(screen.getAllByText(/종료: 11\/08 10:00/).length > 0);
});

test('대결 신청 UI의 시간 경계와 유효하지 않은 입력 및 정상 신청', (t) => {
  const now = Date.UTC(2026, 10, 7, 0);
  t.mock.method(Date, 'now', () => now);
  const allianceState = { ...INITIAL_ALLIANCE_STATE, alliances: [...INITIAL_ALLIANCE_STATE.alliances, { id: 'alliance-hanyang-pusan', name: '한양대·부산대 연합', schoolIds: [SCHOOL_ID.hanyang, SCHOOL_ID.pusan], createdAt: now, durationHours: 72, expiresAt: now + 72 * 3600_000 }] };
  let battleValue: ReturnType<typeof useBattle>;
  function Probe() { battleValue = useBattle(); return null; }
  render(React.createElement(MockGameProvider, null, React.createElement(AllianceProvider, { initialState: allianceState } as any, React.createElement(BattleProvider, null, React.createElement(Probe), React.createElement(BattleApplyScreenPhone)))));
  const input = screen.getByLabelText('시작 일시 (KST)') as HTMLInputElement;
  assert.equal(input.min, '2026-11-07T10:00');
  // 상한은 최대 7일(11/14 09:00)과 '내 연합 만료(11/10 09:00) − 기본 3시간' 중 빠른 쪽
  assert.equal(input.max, '2026-11-10T06:00');
  assert.equal(input.step, '1800');
  const opponent = screen.getByRole('button', { name: /연고 메이저 연합/ });
  fireEvent.click(opponent);
  const before = battleValue!.state.battles.length;
  fireEvent.change(input, { target: { value: '2026-11-07T08:30' } });
  assert.ok(screen.getByRole('alert'));
  assert.equal((screen.getByRole('button', { name: '대결 신청 보내기' }) as HTMLButtonElement).disabled, true);
  assert.equal(battleValue!.state.battles.length, before);
  fireEvent.change(input, { target: { value: '2026-11-07T11:00' } });
  fireEvent.click(screen.getByRole('button', { name: '대결 신청 보내기' }));
  assert.equal(battleValue!.state.battles.length, before + 1);
  assert.equal(battleValue!.state.battles[0].requestedStartTime, parseKstDateTimeLocal('2026-11-07T11:00'));
});

test('희망 시작 시각 KST 경계, 기본값, 설정 주입 및 만료 수락', () => {
  const config = MOCK_SHOP_CONFIG.battle;
  const now = parseKstDateTimeLocal('2026-11-07T08:00');
  const at = (value: string) => parseKstDateTimeLocal(value);
  for (const value of [NaN, Infinity, at('2026-11-07T08:30'), at('2026-11-07T22:30'), at('2026-11-07T10:15'), at('2026-11-15T09:00')]) {
    assert.equal(validateRequestedStartTime(value, now, config).success, false);
  }
  for (const value of ['2026-11-07T09:00', '2026-11-07T22:00']) {
    assert.equal(validateRequestedStartTime(at(value), now, config).success, true);
  }
  const upperNow = at('2026-11-07T10:00');
  assert.equal(validateRequestedStartTime(at('2026-11-14T10:00'), upperNow, config).success, true);
  assert.equal(validateRequestedStartTime(at('2026-11-14T10:30'), upperNow, config).success, false);
  assert.equal(formatKstDateTimeLocal(getDefaultRequestedStartTime(at('2026-11-07T21:50'), config)), '2026-11-08T09:00');
  assert.equal(formatKstDateTimeLocal(getDefaultRequestedStartTime(at('2026-11-07T12:20'), config)), '2026-11-07T14:00');
  assert.equal(at('2026-11-07T09:00'), Date.UTC(2026, 10, 7, 0));
  const alternate = { ...config, startTime: { ...config.startTime, allowedStartHoursKst: { from: 10, to: 20 }, stepMinutes: 60 } };
  assert.equal(validateRequestedStartTime(at('2026-11-07T09:00'), now, alternate).success, false);
  assert.equal(formatKstDateTimeLocal(getDefaultRequestedStartTime(now, alternate)), '2026-11-07T10:00');
  const state = { ...createInitialBattleState(now), battles: [] };
  const start = at('2026-11-07T10:00');
  const applied = applyBattleRequest(state, INITIAL_MOCK_ALLIANCES, 'alliance-sky', 'alliance-capital', 24, start, now);
  assert.equal(applied.result.success, true);
  const late = acceptBattleRequest(applied.nextState, INITIAL_MOCK_ALLIANCES, applied.result.battle!.id, start - 60 * 60_000 + 1);
  assert.equal(late.result.success, false);
  assert.equal(late.nextState, applied.nextState);
  const accepted = acceptBattleRequest(applied.nextState, INITIAL_MOCK_ALLIANCES, applied.result.battle!.id, start - 60 * 60_000);
  assert.equal(accepted.result.battle!.scheduledStartTime, start);
  assert.equal(accepted.result.battle!.scheduledEndTime, start + 24 * 3600_000);
  assert.equal(getEffectiveBattleStatus(accepted.result.battle!, start), 'active');
  assert.equal(getEffectiveBattleStatus(accepted.result.battle!, start + 24 * 3600_000), 'finished');
});

test('P4-3: BattleApplyScreenPhone 설정 기반 시작 대기 및 진행 기간 동적 표기 검증', () => {
  cleanup();
  const testAllianceState = {
    ...INITIAL_ALLIANCE_STATE,
    alliances: [
      ...INITIAL_ALLIANCE_STATE.alliances,
      {
        id: 'alliance-hanyang-pusan',
        name: '한양대·부산대 연합',
        schoolIds: [SCHOOL_ID.hanyang, SCHOOL_ID.pusan],
        createdAt: 1000,
        durationHours: 72,
        expiresAt: Date.now() + 72 * 3600_000,
      },
    ],
  };

  // 1. 기본 시작 시간 설정
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        { initialState: testAllianceState } as any,
        React.createElement(BattleProvider, null, React.createElement(BattleApplyScreenPhone)),
      ),
    ),
  );

  // 기본 시작 시간 안내 확인
  assert.ok(screen.getByText(/09:00~22:00 시작 · 30분 단위/));

  // 2. 대체 시작 시간과 기간 설정 주입
  cleanup();
  const alternateBattleConfig = {
    ...MOCK_SHOP_CONFIG.battle,
    startTime: { ...MOCK_SHOP_CONFIG.battle.startTime, allowedStartHoursKst: { from: 10, to: 20 }, stepMinutes: 60 },
    durationHours: { min: 2, max: 20, default: 12, presets: [2, 12, 20] },
  };

  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        { initialState: testAllianceState } as any,
        React.createElement(
          BattleProvider,
          { config: alternateBattleConfig },
          React.createElement(BattleApplyScreenPhone),
        ),
      ),
    ),
  );

  // 대체 시작 시간 안내 확인
  assert.ok(screen.getByText(/10:00~20:00 시작 · 60분 단위/));
});

// ---------------------------------------------------------------------------
// 3단계: 대결 ↔ 연합 만료 결합 (.agents/01-PLAN/261112-1206-alliance_battle_rule_update_plan.md §7 3단계)
// 기준 시각 base = 11/07 08:00 KST. 시드 연합 만료: capital +22h(11/08 06:00), sky +24h, korea-central +18h, tech +42h.
// ---------------------------------------------------------------------------
const HOUR_MS = 3600_000;
const kst = (value: string) => parseKstDateTimeLocal(value);
const BASE = kst('2026-11-07T08:00');
const freshAlliance = (id: string, schoolIds: [SchoolId, SchoolId], createdAt: number, durationHours: number) => ({
  id,
  name: id,
  schoolIds,
  createdAt,
  durationHours,
  expiresAt: createdAt + durationHours * HOUR_MS,
});

test('대결 기간: 1~24시간 정수만 허용한다 (0·25·1.5시간 거절)', () => {
  const alliances = [
    ...createInitialMockAlliances(BASE),
    freshAlliance('alliance-a', [SCHOOL_ID.hanyang, SCHOOL_ID.pusan], BASE, 72),
    freshAlliance('alliance-b', [SCHOOL_ID.gachon, SCHOOL_ID.hufs], BASE, 72),
  ];
  const state = createInitialBattleState(BASE);
  const start = kst('2026-11-07T09:00');
  for (const [hours, ok] of [[0, false], [1, true], [24, true], [25, false], [1.5, false]] as const) {
    const res = applyBattleRequest(state, alliances, 'alliance-a', 'alliance-b', hours, start, BASE);
    assert.equal(res.result.success, ok, `${hours}시간`);
    if (!ok) assert.match(res.result.error!, /유효하지 않은 대결 기간/);
  }
});

test('연합 만료 결합: 종료가 두 연합 중 빠른 만료를 넘으면 신청·수락 거절, 만료된 연합은 대결 불가', () => {
  const alliances = createInitialMockAlliances(BASE);
  const sky = alliances.find((a) => a.id === 'alliance-sky')!;
  const capital = alliances.find((a) => a.id === 'alliance-capital')!;
  const state = createInitialBattleState(BASE);
  const start = kst('2026-11-07T09:00'); // +1h
  const latestEnd = getBattleLatestEnd(sky, capital);
  assert.equal(latestEnd, capital.expiresAt);
  assert.equal(getMaxBattleDurationHours(start, latestEnd, state.config), 21);

  // 경계: 종료 = 빠른 만료는 허용, 1시간 넘으면 거절
  assert.equal(applyBattleRequest(state, alliances, 'alliance-sky', 'alliance-capital', 21, start, BASE).result.success, true);
  const over = applyBattleRequest(state, alliances, 'alliance-sky', 'alliance-capital', 22, start, BASE);
  assert.equal(over.result.success, false);
  assert.match(over.result.error!, /연합 만료 초과: 대결 종료\(11\/08 07:00\)가 두 연합 중 빠른 만료\(11\/08 06:00\)/);

  // 만료된 연합은 신청 대상이 될 수 없다 (capital은 +22h에 만료)
  const later = BASE + 23 * HOUR_MS;
  const expired = applyBattleRequest(state, alliances, 'alliance-sky', 'alliance-capital', 1, kst('2026-11-08T09:00'), later);
  assert.equal(expired.result.success, false);
  assert.match(expired.result.error!, /만료된 연합: 상대 연합\(수도권 동맹\)/);

  // 수락 시점 재검증: 신청 후 상대 연합 만료가 당겨진 경우 (방어 검증)
  const applied = applyBattleRequest(state, alliances, 'alliance-sky', 'alliance-capital', 12, start, BASE);
  const shortened = alliances.map((a) => (a.id === 'alliance-capital' ? { ...a, expiresAt: start + 6 * HOUR_MS } : a));
  const accept = acceptBattleRequest(applied.nextState, shortened, applied.result.battle!.id, BASE);
  assert.equal(accept.result.success, false);
  assert.match(accept.result.error!, /수락 불가: 연합 만료 초과/);

  // 확정된 대결은 빠른 만료 전에 끝난다: 시계를 만료 시각까지 당기면 이미 종료 상태다
  const confirmed = acceptBattleRequest(applied.nextState, alliances, applied.result.battle!.id, BASE);
  assert.equal(confirmed.result.success, true);
  assert.ok(confirmed.result.battle!.scheduledEndTime! <= latestEnd);
  assert.equal(getEffectiveBattleStatus(confirmed.result.battle!, latestEnd), 'finished');
});

test('기본 시작 슬롯: 연합 만료 상한 안에 끝낼 수 없으면 NaN', () => {
  const config = MOCK_SHOP_CONFIG.battle;
  assert.equal(formatKstDateTimeLocal(getDefaultRequestedStartTime(BASE, config, kst('2026-11-07T12:00'), 3)), '2026-11-07T09:00');
  assert.ok(Number.isNaN(getDefaultRequestedStartTime(BASE, config, kst('2026-11-07T11:59'), 3)));
  assert.ok(Number.isNaN(getDefaultRequestedStartTime(BASE, config, kst('2026-11-07T09:30'), 1)));
});

test("수락 기한이 지난 대기 신청은 실효 상태 'expired'이고 취소·거절·수락할 수 없으며 같은 연합끼리 다시 신청할 수 있다", () => {
  const alliances = createInitialMockAlliances(BASE);
  const state = createInitialBattleState(BASE);
  const pending = state.battles.find((b) => b.id === 'battle-sample-pending-2v2')!;
  const deadline = getBattleAcceptDeadline(pending, state.config);
  assert.equal(getEffectiveBattleStatus(pending, deadline, state.config), 'pending');
  assert.equal(getEffectiveBattleStatus(pending, deadline + 1, state.config), 'expired');
  assert.equal(formatBattleRemainingTime(pending, deadline + 1, state.config), '수락 기한 만료');
  assert.match(cancelBattleRequest(state, pending.id, deadline + 1).result.error!, /만료된 대결 신청/);
  assert.match(rejectBattleRequest(state, pending.id, deadline + 1).result.error!, /만료된 대결 신청/);
  assert.equal(acceptBattleRequest(state, alliances, pending.id, deadline + 1).result.success, false);

  const retryNow = deadline + 1;
  const retryStart = getDefaultRequestedStartTime(retryNow, state.config, BASE + 42 * HOUR_MS, 3);
  const retry = applyBattleRequest(state, alliances, 'alliance-honam', 'alliance-tech', 3, retryStart, retryNow);
  assert.equal(retry.result.success, true, retry.result.error);
});

test('대결 시드 불변식: 모든 대결은 양측 연합 수명 안에서 신청·진행·종료된다', () => {
  const alliances = new Map(createInitialMockAlliances(BASE).map((a) => [a.id, a]));
  for (const b of createInitialBattleState(BASE).battles) {
    const req = alliances.get(b.requesterAllianceId)!;
    const rec = alliances.get(b.recipientAllianceId)!;
    assert.ok(req && rec, `${b.id}: 연합 존재`);
    const start = b.scheduledStartTime ?? b.requestedStartTime;
    const end = b.scheduledEndTime ?? start + b.durationHours * HOUR_MS;
    assert.ok(b.createdAt >= Math.max(req.createdAt, rec.createdAt), `${b.id}: 양측 연합 성립 후 신청`);
    assert.ok(start >= b.createdAt, `${b.id}: 신청 후 시작`);
    assert.ok(end <= getBattleLatestEnd(req, rec), `${b.id}: 빠른 만료 전 종료`);
    assert.ok(Number.isInteger(b.durationHours) && b.durationHours >= 1 && b.durationHours <= 24, `${b.id}: 기간 1~24h`);
  }
  const pending = createInitialBattleState(BASE).battles.find((b) => b.status === 'pending')!;
  assert.equal(getEffectiveBattleStatus(pending, BASE), 'pending');
});

test('대결 신청 화면: 만료 상한으로 기간·시작을 제한하고, 받은 신청을 일반 UI에서 수락하며 기한이 지나면 만료됨으로 표시한다', (t) => {
  t.mock.method(Date, 'now', () => BASE);
  const allianceState = {
    ...createInitialAllianceState(BASE),
    alliances: [
      freshAlliance('alliance-hanyang-pusan', [SCHOOL_ID.hanyang, SCHOOL_ID.pusan], BASE, 72),
      ...createInitialMockAlliances(BASE),
      { ...freshAlliance('alliance-soon', [SCHOOL_ID.gachon, SCHOOL_ID.hufs], BASE + 30 * 60_000 - 6 * HOUR_MS, 6), name: '곧 만료 연합' },
      { ...freshAlliance('alliance-gone', [SCHOOL_ID.knu, SCHOOL_ID.cnu], BASE - 7 * HOUR_MS, 6), name: '만료된 테스트 연합' },
    ],
  };
  let battleValue: ReturnType<typeof useBattle>;
  function Probe() {
    battleValue = useBattle();
    return null;
  }
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(
        AllianceProvider,
        { initialState: allianceState },
        React.createElement(
          BattleProvider,
          { initialState: createInitialBattleState(BASE) },
          React.createElement(Probe),
          React.createElement(BattleApplyScreenPhone),
        ),
      ),
    ),
  );

  // 상대 후보: 만료된 연합은 없고, 최소 기간으로도 끝낼 수 없는 연합은 '만료 임박 (대결 불가)'
  assertAbsent(screen.queryByRole('button', { name: /만료된 테스트 연합/ }));
  const soon = screen.getByRole('button', { name: /곧 만료 연합/ }) as HTMLButtonElement;
  assert.equal(soon.disabled, true);
  assert.match(soon.textContent ?? '', /만료 임박 \(대결 불가\)/);

  // 수도권 동맹(+22h 만료) 선택: 09:00 시작이면 최대 21시간 → 24h 프리셋 숨김
  fireEvent.click(screen.getByRole('button', { name: /수도권 동맹/ }));
  assertAbsent(screen.queryByRole('button', { name: '24h' }));
  assert.ok(screen.getByText(/선택한 시작 시각 기준 최대 21시간/));

  // 만료 뒤 시작은 연합 만료 초과로 막힌다
  fireEvent.change(screen.getByLabelText('시작 일시 (KST)'), { target: { value: '2026-11-08T09:00' } });
  assert.ok(screen.getByText(/연합 만료 초과/));
  assert.equal((screen.getByRole('button', { name: '대결 신청 보내기' }) as HTMLButtonElement).disabled, true);

  // 받은 신청 → 수락 버튼 → 1시간 뒤 수락 기한(희망 시작 − 60분)이 지나 '만료됨'
  fireEvent.click(screen.getByRole('button', { name: '[시연] 다른 연합의 대결 신청 받기' }));
  assert.ok(screen.getByRole('button', { name: '연고 메이저 연합의 대결 신청 수락' }));
  fireEvent.click(screen.getByRole('button', { name: '+1h' }));
  assertAbsent(screen.queryByRole('button', { name: /의 대결 신청 수락$/ }));
  assert.ok(screen.getAllByText('만료됨').length > 0);

  // 다시 받은 신청을 일반 UI에서 수락하면 확정된다 (리더 없음: 우리 연합 학생 누구나)
  fireEvent.click(screen.getByRole('button', { name: '[시연] 다른 연합의 대결 신청 받기' }));
  fireEvent.click(screen.getByRole('button', { name: /의 대결 신청 수락$/ }));
  assert.ok(screen.getByText(/대결 신청을 수락했습니다/));
  assert.equal(battleValue!.isMyAllianceOccupied, true);
  const confirmed = battleValue!.myOccupiedBattle!;
  assert.equal(confirmed.recipientAllianceId, 'alliance-hanyang-pusan');
  assert.equal(confirmed.requesterAllianceId, 'alliance-sky');
  assert.ok(confirmed.scheduledEndTime! <= BASE + 24 * HOUR_MS, '연고 메이저 연합 만료(+24h) 전에 끝난다');
});

test('받은 대결 신청만 일반 수락할 수 있다: 내가 보낸 신청은 수락 API로 확정되지 않는다', () => {
  const now = Date.now();
  const allianceState = {
    ...INITIAL_ALLIANCE_STATE,
    alliances: [freshAlliance('alliance-hanyang-pusan', [SCHOOL_ID.hanyang, SCHOOL_ID.pusan], now, 72), ...INITIAL_ALLIANCE_STATE.alliances],
  };
  let battleValue: ReturnType<typeof useBattle>;
  function Probe() {
    battleValue = useBattle();
    return null;
  }
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(AllianceProvider, { initialState: allianceState }, React.createElement(BattleProvider, null, React.createElement(Probe))),
    ),
  );
  let result: any;
  act(() => {
    const start = getDefaultRequestedStartTime(now, battleValue.config, now + 22 * HOUR_MS, 1);
    const sent = battleValue.applyRequest('alliance-honam', 1, start);
    assert.equal(sent.success, true, sent.error);
    result = battleValue.acceptRequest(sent.battle!.id);
  });
  assert.equal(result.success, false);
  assert.match(result.error, /우리 연합이 받은 대결 신청만/);
});
