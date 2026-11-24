import { SCHOOL_ID } from '../src/data/schoolIds.ts';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  attemptsLeft,
  completeProfile,
  cooldownSeconds,
  editEmail,
  expireCodeForDemo,
  INITIAL_AUTH_STATE,
  MOCK_AUTH_RULES,
  requestCode,
  resendCode,
  restartAuth,
  verifyCode,
  type AuthResult,
  type MockAuthState,
} from '../src/utils/mockAuth.ts';
import { MOCK_EMAIL_DOMAINS } from '../src/data/mockEmailDomains.ts';
import { getSchoolDepartments } from '../src/data/gameRules.ts';
import { REAL_UNIVERSITIES } from '../src/data/universitiesData.ts';

// Mock email verification rules (M4). Plan: docs/261108-2020-m4_onboarding_auth_plan.md 7-1, Issue #15 M4 criteria.
const T0 = 1_000_000;
const CODE = '123456';
const ok = (result: AuthResult): MockAuthState => {
  assert.equal(result.ok, true, result.ok ? '' : `expected success, got ${result.error}`);
  return result.state;
};
const err = (result: AuthResult) => (result.ok ? 'ok' : result.error);
const codeStep = (email = 'student@kaist.ac.kr') => ok(requestCode(INITIAL_AUTH_STATE, email, T0, CODE));
const verified = () => ok(verifyCode(codeStep(), CODE, T0 + 1000));

test('도메인으로 학교가 정해지고 형식 오류·미지원·공유 도메인은 거절한다', () => {
  const state = ok(requestCode(INITIAL_AUTH_STATE, '  Student@KAIST.ac.kr ', T0, CODE));
  assert.equal(state.email, 'student@kaist.ac.kr', '공백 제거·소문자 정규화');
  assert.equal(state.schoolId, SCHOOL_ID.kaist);
  assert.equal(state.step, 'code');
  assert.equal(err(requestCode(INITIAL_AUTH_STATE, 'not-an-email', T0, CODE)), 'invalid-email');
  assert.equal(err(requestCode(INITIAL_AUTH_STATE, 'student@gmail.com', T0, CODE)), 'unsupported-domain');
  assert.equal(err(requestCode(INITIAL_AUTH_STATE, 'student@shared-demo.ac.kr', T0, CODE)), 'ambiguous-domain');
  assert.equal(err(requestCode(INITIAL_AUTH_STATE, 'student@cs.kaist.ac.kr', T0, CODE)), 'unsupported-domain', '하위 도메인은 목업에서 일치로 보지 않는다');
});

test('샘플 도메인은 공통 학교 목록의 ID만 가리키고 지원 학교마다 학과가 있다', () => {
  const ids = new Set(REAL_UNIVERSITIES.map((school) => school.id));
  for (const entry of MOCK_EMAIL_DOMAINS) {
    for (const schoolId of entry.schoolIds) assert.ok(ids.has(schoolId), `${entry.domain} → ${schoolId}`);
    if (entry.schoolIds.length === 1) assert.ok(getSchoolDepartments(entry.schoolIds[0]).length >= 3, entry.domain);
  }
  for (const schoolId of ids) {
    const departments = getSchoolDepartments(schoolId);
    assert.equal(new Set(departments.map((d) => d.id)).size, departments.length, `${schoolId} 학과 ID 중복`);
    assert.equal(new Set(departments.map((d) => d.name)).size, departments.length, `${schoolId} 학과 이름 중복`);
  }
});

test('기준 1: 맞는 코드로만 프로필 단계로 가고 틀린 코드·만료 코드는 막힌다', () => {
  const state = codeStep();
  const wrong = verifyCode(state, '000000', T0 + 1000);
  assert.equal(err(wrong), 'wrong-code');
  assert.equal(wrong.state.step, 'code');
  assert.equal(err(verifyCode(state, '12345', T0 + 1000)), 'incomplete-code');
  assert.equal(err(verifyCode(state, CODE, T0 + MOCK_AUTH_RULES.codeTtlMs)), 'expired', '유효시간 경계에서 만료');
  assert.equal(err(verifyCode(expireCodeForDemo(state, T0 + 5000), CODE, T0 + 5000)), 'expired', '시연용 만료');
  const passed = ok(verifyCode(state, CODE, T0 + MOCK_AUTH_RULES.codeTtlMs - 1));
  assert.equal(passed.step, 'profile');
  assert.deepEqual(passed.verification, { email: 'student@kaist.ac.kr', schoolId: SCHOOL_ID.kaist, verifiedAt: T0 + MOCK_AUTH_RULES.codeTtlMs - 1 });
});

test('기준 2: 시도 한도를 넘으면 맞는 코드도 막히고, 이메일을 고쳐 다시 요청해도 대기 시간 안에는 풀리지 않는다', () => {
  let state = codeStep();
  for (let i = 1; i <= MOCK_AUTH_RULES.maxAttempts; i++) {
    const result = verifyCode(state, '000000', T0 + i);
    assert.equal(err(result), i === MOCK_AUTH_RULES.maxAttempts ? 'locked' : 'wrong-code');
    state = result.state;
    assert.equal(attemptsLeft(state), MOCK_AUTH_RULES.maxAttempts - i);
  }
  assert.equal(err(verifyCode(state, CODE, T0 + 10)), 'locked');
  assert.equal(state.challenge?.attempts, MOCK_AUTH_RULES.maxAttempts, '잠긴 뒤 시도는 더 세지 않는다');

  const edited = editEmail(state);
  assert.equal(err(requestCode(edited, 'student@kaist.ac.kr', T0 + 20, '654321')), 'cooldown');
  const unlocked = ok(resendCode(state, T0 + MOCK_AUTH_RULES.resendCooldownMs, '654321'));
  assert.equal(attemptsLeft(unlocked), MOCK_AUTH_RULES.maxAttempts, '새 코드는 시도 횟수를 새로 센다');
});

test('기준 3: 사용한 코드를 다시 내거나 완료를 다시 눌러도 인증·완료가 반복되지 않는다', () => {
  const state = verified();
  const again = verifyCode(state, CODE, T0 + 2000);
  assert.equal(err(again), 'already-used');
  assert.equal(again.state, state, '상태 객체도 그대로');
  const done = ok(completeProfile(state, { nickname: '카이스트새내기', departmentId: 'kaist-ee' }));
  const twice = completeProfile(done, { nickname: '다른이름', departmentId: 'kaist-me' });
  assert.equal(err(twice), 'already-completed');
  assert.equal(twice.state.completed?.nickname, '카이스트새내기');
  assert.equal(err(verifyCode(done, CODE, T0 + 3000)), 'already-used');
});

test('기준 4: 재발송 대기 중에는 거절하고, 재발송 뒤 이전 코드는 쓸 수 없다', () => {
  const state = codeStep();
  assert.equal(cooldownSeconds(state, state.email, T0), MOCK_AUTH_RULES.resendCooldownMs / 1000);
  assert.equal(err(resendCode(state, T0 + MOCK_AUTH_RULES.resendCooldownMs - 1, '654321')), 'cooldown');
  const resent = ok(resendCode(state, T0 + MOCK_AUTH_RULES.resendCooldownMs, '654321'));
  assert.equal(err(verifyCode(resent, CODE, T0 + 61_000)), 'wrong-code', '이전 코드 무효');
  assert.equal(err(resendCode(resent, T0 + 61_000, '111111')), 'cooldown', '재발송 직후 다시 대기');
  assert.equal(ok(verifyCode(resent, '654321', T0 + 61_000)).step, 'profile');
});

test('기준 5: 이메일을 고치면 이전 인증을 쓸 수 없고 프로필 완료로 우회하지 못한다', () => {
  const state = verified();
  const edited = editEmail(state);
  assert.equal(edited.step, 'email');
  assert.equal(edited.verification, null);
  assert.equal(edited.challenge, null);
  assert.equal(err(completeProfile(edited, { nickname: '우회', departmentId: 'kaist-ee' })), 'not-verified');
  assert.equal(err(completeProfile(codeStep(), { nickname: '우회', departmentId: 'kaist-ee' })), 'not-verified', '인증 전 완료 금지');
  const swapped = { ...state, email: 'student@hanyang.ac.kr' };
  assert.equal(err(completeProfile(swapped, { nickname: '우회', departmentId: 'kaist-ee' })), 'not-verified', '인증한 이메일과 다르면 실패(기획서 6-2 6단계)');
});

test('프로필은 닉네임 1~10자와 인증된 학교의 학과만 받는다', () => {
  const state = verified();
  assert.equal(err(completeProfile(state, { nickname: '   ', departmentId: 'kaist-ee' })), 'invalid-nickname');
  assert.equal(err(completeProfile(state, { nickname: '열한글자닉네임입니다요', departmentId: 'kaist-ee' })), 'invalid-nickname');
  assert.equal(err(completeProfile(state, { nickname: '새내기', departmentId: 'business' })), 'invalid-department', '다른 학교 학과는 안 된다');
  const done = ok(completeProfile(state, { nickname: ' 새내기 ', departmentId: 'kaist-ee' }));
  assert.deepEqual(done.completed, { email: 'student@kaist.ac.kr', schoolId: SCHOOL_ID.kaist, departmentId: 'kaist-ee', department: '전기및전자공학부', nickname: '새내기' });
});

test('거절된 요청은 코드를 만들지 않는다', () => {
  let made = 0;
  const source = () => { made++; return CODE; };
  requestCode(INITIAL_AUTH_STATE, 'student@gmail.com', T0, source);
  const state = ok(requestCode(INITIAL_AUTH_STATE, 'student@kaist.ac.kr', T0, source));
  resendCode(state, T0 + 1000, source);
  assert.equal(made, 1);
});

test('다시 시작해도 재발송 대기는 남는다', () => {
  const done = ok(completeProfile(verified(), { nickname: '새내기', departmentId: 'kaist-ee' }));
  const restarted = restartAuth(done);
  assert.equal(restarted.step, 'email');
  assert.equal(restarted.completed, null);
  assert.equal(err(requestCode(restarted, 'student@kaist.ac.kr', T0 + 2000, '222222')), 'cooldown');
  assert.equal(ok(requestCode(restarted, 'student@kaist.ac.kr', T0 + MOCK_AUTH_RULES.resendCooldownMs, '222222')).step, 'code');
});
