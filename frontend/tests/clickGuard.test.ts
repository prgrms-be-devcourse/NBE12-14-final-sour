import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  INITIAL_CLICK_GUARD,
  INITIAL_KEY_GATE,
  judgeClick,
  keyPressDown,
  keyPressUp,
  MOCK_CLICK_RULES,
  type ClickGuardState,
} from '../src/utils/clickGuard.ts';
import { requestWaitMs, settleRequest, MOCK_REQUEST_RULES } from '../src/utils/mockRequest.ts';

// Mock click checks, keyboard press rule and request modes (M5). Plan: docs/261108-2051-m5_failure_retry_click_guard_plan.md 7-1.
const R = MOCK_CLICK_RULES;

function clicks(times: number[], start: ClickGuardState = INITIAL_CLICK_GUARD) {
  let state = start;
  return times.map((time) => {
    const judged = judgeClick(state, time, R);
    state = judged.state;
    return judged.accepted ? 'ok' : judged.reason;
  });
}

test('기준 3: 1초 창의 한도까지는 받고 넘으면 거절한다', () => {
  const spacing = R.windowMs / R.maxPerWindow; // 50ms: within the interval rule
  const times = Array.from({ length: R.maxPerWindow + 1 }, (_, i) => 1000 + i * (spacing - 1));
  const results = clicks(times);
  assert.deepEqual(results.slice(0, R.maxPerWindow), Array(R.maxPerWindow).fill('ok'));
  assert.equal(results[R.maxPerWindow], 'rate');
  // Same pace but slow enough that the oldest click leaves the window: never refused.
  assert.ok(clicks(Array.from({ length: 60 }, (_, i) => 1000 + i * (spacing + 1))).every((r) => r === 'ok'));
});

test('기준 3: 최소 간격보다 짧은 클릭은 거절하고 경계값은 받는다', () => {
  assert.deepEqual(clicks([1000, 1000 + R.minIntervalMs - 1]), ['ok', 'interval']);
  assert.deepEqual(clicks([1000, 1000 + R.minIntervalMs]), ['ok', 'ok']);
  assert.deepEqual(clicks([1000, 1000]), ['ok', 'interval'], '같은 밀리초 두 번째 클릭');
});

test('기준 3: 거절 뒤 차단 시간 동안 모두 막고, 끝나면 정상으로 돌아온다', () => {
  const t0 = 1000;
  const rejectedAt = t0 + 1;
  const results = clicks([t0, rejectedAt, rejectedAt + 1000, rejectedAt + R.blockMs - 1, rejectedAt + R.blockMs]);
  assert.deepEqual(results, ['ok', 'interval', 'blocked', 'blocked', 'ok']);
});

test('기준 3: 시연 거절은 상태를 바꾸지 않아 토글을 끄면 바로 정상이다', () => {
  const first = judgeClick(INITIAL_CLICK_GUARD, 1000, R);
  const forced = judgeClick(first.state, 2000, R, true);
  assert.equal(forced.accepted, false);
  assert.equal(forced.accepted ? null : forced.reason, 'forced');
  assert.equal(forced.state, first.state);
  assert.equal(judgeClick(forced.state, 2001, R).accepted, true);
});

test('키보드: 눌렀다 뗀 한 번만 클릭이고, 누르고 있는 동안의 반복 입력은 세지 않는다', () => {
  let gate = keyPressDown(INITIAL_KEY_GATE, 'Enter', false);
  for (let i = 0; i < 10; i++) gate = keyPressDown(gate, 'Enter', true);
  const released = keyPressUp(gate, 'Enter');
  assert.equal(released.click, true);
  assert.deepEqual(released.gate, INITIAL_KEY_GATE);
  assert.equal(keyPressUp(released.gate, 'Enter').click, false, '이미 뗀 키를 다시 떼도 클릭이 아니다');
});

test('키보드: 누르지 않은 키를 떼거나, 다른 키를 함께 누르거나, 반복으로만 들어온 키는 클릭이 아니다', () => {
  assert.equal(keyPressUp(INITIAL_KEY_GATE, 'Enter').click, false, '누른 기록 없이 뗌');
  assert.deepEqual(keyPressDown(INITIAL_KEY_GATE, 'Enter', true), INITIAL_KEY_GATE, '반복 이벤트로는 눌림이 시작되지 않는다');
  assert.deepEqual(keyPressDown(INITIAL_KEY_GATE, 'a', false), INITIAL_KEY_GATE, '클릭 키가 아님');
  const held = keyPressDown(INITIAL_KEY_GATE, 'Enter', false);
  const both = keyPressDown(held, ' ', false);
  assert.deepEqual(both, held, '누르고 있는 동안 다른 키는 무시');
  assert.equal(keyPressUp(both, ' ').click, false);
  assert.equal(keyPressUp(both, 'Enter').click, true);
});

test('요청 모드: 정상은 바로, 지연은 기다린 뒤 성공, 1회 실패는 한 번 실패 후 정상으로 돌아간다', () => {
  assert.equal(requestWaitMs('normal'), 0);
  assert.equal(requestWaitMs('delay'), MOCK_REQUEST_RULES.delayMs);
  assert.equal(requestWaitMs('fail'), MOCK_REQUEST_RULES.failDelayMs);
  assert.deepEqual(settleRequest('normal'), { ok: true, nextMode: 'normal' });
  assert.deepEqual(settleRequest('delay'), { ok: true, nextMode: 'delay' });
  assert.deepEqual(settleRequest('fail-once'), { ok: false, nextMode: 'normal' });
  assert.deepEqual(settleRequest('fail'), { ok: false, nextMode: 'fail' });
});
