import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CAMPUS_STAGES, CLICKERS, DEFAULT_PLAYER, MY_SCHOOL_ID, STORE_ITEMS } from '../src/data/gameRules';
import { getMockRankings, getSchoolRankings, rankEntries } from '../src/data/mockRankings';
import { gameReducer, getActiveBuff, getCampusStage, getClicker, getNextClicker, getTapPoints, INITIAL_GAME_STATE } from '../src/utils/gameModel';

const seed = () => ({ ...INITIAL_GAME_STATE });

test('클리커 ID로 단계·세기를 조회하고 별도 EXP 없이 클릭한다', () => {
  const state = seed();
  assert.equal(getClicker(state.clickerId).power, 30);
  assert.equal(getNextClicker(state.clickerId)?.power, 40);
  const normal = gameReducer(state, { type: 'tap', isCrit: false, now: 0 });
  assert.equal(normal.contribution - state.contribution, 30);
  assert.equal(normal.schoolScore - state.schoolScore, 30);
  assert.equal(normal.departmentScore - state.departmentScore, 30);
  const crit = gameReducer(normal, { type: 'tap', isCrit: true, now: 1 });
  assert.equal(crit.contribution - normal.contribution, 60);
  assert.equal(crit.clickerId, state.clickerId);
});

test('1000점 획득 후 300점 소비 → 개인 순위 700점, 학교·학과는 유지', () => {
  const state = { ...seed(), clickerId: 1, contribution: 1000 };
  const bought = gameReducer(state, { type: 'upgrade' });
  const before = getMockRankings(state);
  const after = getMockRankings(bought);
  assert.equal(bought.contribution, 700);
  assert.equal(after.personal.find((row) => row.id === 'me')?.score, 700);
  assert.deepEqual(after.school, before.school);
  assert.deepEqual(after.dept, before.dept);
  assert.equal(bought.clickerId, 2);
});

test('개인 순위는 소비 후 재정렬하고 초기 순위 대비 변동을 계산한다', () => {
  const before = seed();
  const after = getMockRankings({ ...before, contribution: 7900 });
  const me = after.personal.find((row) => row.id === 'me')!;
  assert.equal(me.rank, 9);
  assert.equal(me.change, '▼2');
  assert.equal(after.personal.at(-1)?.id, 'me');
});

test('정확한 강화 비용만 개인 기여도에서 차감하고 다음 단계 세기를 적용한다', () => {
  for (const clicker of CLICKERS.slice(0, -1)) {
    const state = { ...seed(), clickerId: clicker.id, contribution: clicker.upgradeCost! };
    const upgraded = gameReducer(state, { type: 'upgrade' });
    assert.equal(upgraded.contribution, 0);
    assert.equal(upgraded.clickerId, clicker.id + 1);
    assert.equal(upgraded.schoolScore, state.schoolScore);
    assert.equal(upgraded.departmentScore, state.departmentScore);
    assert.equal(getTapPoints(upgraded, false, 0), getNextClicker(clicker.id)?.power);
  }
});

test('잔액 부족·최고 단계에서는 강화로 잔액이나 레벨이 바뀌지 않는다', () => {
  const poor = { ...seed(), contribution: 1499 };
  assert.equal(gameReducer(poor, { type: 'upgrade' }), poor);
  const max = { ...seed(), clickerId: CLICKERS.at(-1)!.id, contribution: 1000000 };
  assert.equal(getNextClicker(max.clickerId), null);
  assert.equal(gameReducer(max, { type: 'upgrade' }), max);
});

test('구매는 잔액 부족·미정 시안·없는 아이템을 거절하고 기존 버프를 보존한다', () => {
  const active = gameReducer(seed(), { type: 'buy', itemId: 'coffee', now: 0 });
  const poor = { ...active, contribution: 59 };
  assert.equal(gameReducer(poor, { type: 'buy', itemId: 'pass', now: 1 }), poor);
  assert.equal(gameReducer(active, { type: 'buy', itemId: 'missing', now: 1 }), active);
  assert.deepEqual(STORE_ITEMS.map((item) => item.id), ['coffee']);
  for (const id of ['flag', 'pass', 'shard', 'touch_pen', 'golden_coffee', 'student_assistant', 'delivery_bike', 'cheer_squad', 'student_council', 'ai_lab']) {
    assert.equal(gameReducer(active, { type: 'buy', itemId: id, now: 1 }), active);
  }
  // 거절 뒤에도 기존 커피 버프가 원래 만료 시각까지 클릭 점수에 적용된다.
  const rejected = gameReducer(active, { type: 'buy', itemId: 'flag', now: 20000 });
  assert.equal(rejected.activeBuff?.expiresAt, 30000);
  assert.equal(getTapPoints(rejected, false, 20000), 60);
  assert.equal(getTapPoints(rejected, true, 20000), 120);
  assert.equal(getTapPoints(rejected, false, 30000), 30);
});

test('연속 강화·구매도 잔액이 음수가 되거나 중복 소비되지 않는다', () => {
  let state = { ...seed(), contribution: 1500 };
  state = gameReducer(state, { type: 'upgrade' });
  for (let i = 0; i < 10; i++) {
    state = gameReducer(state, { type: 'upgrade' });
    state = gameReducer(state, { type: 'buy', itemId: 'coffee', now: i });
  }
  assert.equal(state.contribution, 0);
  assert.equal(state.clickerId, 4);
});

test('같은 버프 재구매는 중첩하지 않고 시간을 다시 시작한다', () => {
  const first = gameReducer(seed(), { type: 'buy', itemId: 'coffee', now: 1000 });
  const replacement = gameReducer(first, { type: 'buy', itemId: 'coffee', now: 20000 });
  assert.equal(replacement.activeBuff?.expiresAt, 50000);
  assert.equal(getTapPoints(replacement, false, 20000), 60);
  assert.equal(getTapPoints(replacement, true, 20000), 120);
  assert.equal(replacement.contribution, first.contribution - 120);
  // 이전 버프의 만료 시점이 새 버프를 종료시키면 안 된다.
  assert.equal(gameReducer(replacement, { type: 'expire', now: 31000 }), replacement);
  assert.equal(getActiveBuff(replacement, 49999)?.id, 'coffee');
  assert.equal(getActiveBuff(replacement, 50000), null);
  assert.equal(gameReducer(replacement, { type: 'expire', now: 50000 }).activeBuff, null);
});

test('1회 버프는 다음 클릭에만 적용하고 시간 내 미사용 시 만료된다', () => {
  const fixture = [{ id: 'single-use-fixture', name: 'test', description: '', cost: 0, iconType: 'pass' as const, multiplier: 1, bonus: 50, durationMs: 300000, singleUse: true }];
  const state = { ...seed(), activeBuff: { itemId: 'single-use-fixture', expiresAt: 300000 } };
  const tap = gameReducer(state, { type: 'tap', isCrit: false, now: 1 }, fixture);
  assert.equal(tap.contribution - state.contribution, 80);
  assert.equal(tap.activeBuff, null);
  assert.equal(getTapPoints(tap, false, 2), 30);
  assert.equal(getTapPoints(state, true, 1, fixture), 110);
  assert.equal(getTapPoints(state, false, 300000, fixture), 30);
});

test('만료 정리 타이머가 지연되어도 클릭 계산은 만료된 버프를 사용하지 않는다', () => {
  const state = gameReducer(seed(), { type: 'buy', itemId: 'coffee', now: 0 });
  const tap = gameReducer(state, { type: 'tap', isCrit: false, now: 30000 });
  assert.equal(tap.contribution - state.contribution, 30);
  assert.equal(tap.activeBuff, null);
});

test('모든 캠퍼스 경계의 직전·도달·초과 점수에서 올바른 단계가 선택된다', () => {
  for (const [index, stage] of CAMPUS_STAGES.entries()) {
    assert.equal(getCampusStage(stage.requiredScore).level, stage.level);
    assert.equal(getCampusStage(stage.requiredScore + 1).level, stage.level);
    if (index > 0) assert.equal(getCampusStage(stage.requiredScore - 1).level, stage.level - 1);
  }
  assert.equal(getCampusStage(999999999).level, 10);
  const nextStage = { ...seed(), schoolScore: 2000000 - 30 };
  assert.equal(getCampusStage(gameReducer(nextStage, { type: 'tap', isCrit: false, now: 0 }).schoolScore).level, 5);
});

test('성장 시연은 학교 점수만 변경하고 개인·학과·클리커·버프를 보존한다', () => {
  const state = gameReducer(seed(), { type: 'buy', itemId: 'coffee', now: 0 });
  for (const stage of CAMPUS_STAGES) {
    const preview = gameReducer(state, { type: 'preview-campus', level: stage.level });
    assert.equal(preview.schoolScore, stage.requiredScore);
    assert.equal(preview.contribution, state.contribution);
    assert.equal(preview.departmentScore, state.departmentScore);
    assert.equal(preview.clickerId, state.clickerId);
    assert.equal(preview.activeBuff, state.activeBuff);
  }
  assert.equal(gameReducer(state, { type: 'preview-campus', level: 99 }), state);
});

test('정렬: 점수 내림차순, 한글 이름 오름차순, 동일 이름은 ID로 안정화', () => {
  const input = [
    { id: 'z', name: '한양대', score: 100 },
    { id: 'b', name: '가나다대', score: 100 },
    { id: 'a', name: '가나다대', score: 100 },
    { id: 'top', name: '최고대', score: 200 },
  ];
  const result = rankEntries(input);
  assert.deepEqual(result.map((row) => row.id), ['top', 'a', 'b', 'z']);
  assert.deepEqual(result.map((row) => row.rank), [1, 2, 3, 4]);
  assert.equal(input[0].id, 'z');
});

test('전국 학교는 점수 변경 시 재정렬하며 교내 개인에는 타 학교가 포함되지 않는다', () => {
  const national = getSchoolRankings(999999999);
  assert.equal(national[0].id, MY_SCHOOL_ID);
  assert.equal(national[0].rank, 1);
  assert.equal(national.length, 76);
  const rankings = getMockRankings(seed());
  assert.equal(rankings.personal.some((row) => row.id === 'visitor'), false);
  assert.ok(rankings.personal.every((row) => row.region.startsWith('한양대학교')));
  assert.ok(rankings.dept.every((row) => row.region === '한양대학교'));
  assert.equal(rankings.personal.find((row) => row.id === 'me')?.name, DEFAULT_PLAYER.nickname);
  for (const data of Object.values(rankings)) {
    assert.deepEqual(data.map((row) => row.rank), data.map((_, index) => index + 1));
    assert.ok(data.every((row, index) => index === 0 || data[index - 1].score >= row.score));
  }
});

test('온보딩 닉네임·학과 변경을 개인과 학과 순위에 반영한다', () => {
  const player = { ...DEFAULT_PLAYER, nickname: '가나다', department: '새학과' };
  const rankings = getMockRankings(seed(), player);
  assert.equal(rankings.personal.find((row) => row.id === 'me')?.name, '가나다');
  assert.equal(rankings.dept.find((row) => row.id === 'my-department')?.name, '새학과');
});

test('서버 내 정보 반영(account): 같은 학교면 기여도·클리커만 바꾸고, 다른 학교면 그 학교에서 새로 시작한다', () => {
  const playing = { ...seed(), contribution: 5000, clickerId: 6, schoolScore: 9_999_999, departmentScore: 777, activeBuff: { itemId: STORE_ITEMS[0].id, expiresAt: 10_000 } };
  const same = gameReducer(playing, { type: 'account', contribution: 120, clickerId: 1, schoolScore: null });
  assert.deepEqual(same, { ...playing, contribution: 120, clickerId: 1 });

  const moved = gameReducer(playing, { type: 'account', contribution: 0, clickerId: 2, schoolScore: 4321 });
  assert.deepEqual(moved, { ...INITIAL_GAME_STATE, schoolScore: 4321, contribution: 0, clickerId: 2 });
});
