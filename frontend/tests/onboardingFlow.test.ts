import { SCHOOL_ID } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { assertAbsent } from './helpers/domAssert';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { installDialogStub } from './helpers/dialogStub';

// Sequential onboarding → campus with the verified school (M4). Plan: docs/261108-2020-m4_onboarding_auth_plan.md 7-1.
// jsdom checks flow and state; layout and real input still need a browser check.
const assetHooks = registerHooks({
  load(url, context, nextLoad) {
    if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) {
      const match = new URL(url).pathname.match(/\/src\/assets\/.+$/);
      return { format: 'module', source: `export default ${JSON.stringify(match ? match[0] : '/asset')};`, shortCircuit: true };
    }
    return nextLoad(url, context);
  },
});
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node,
  requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window),
  cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window),
  IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
dom.window.HTMLElement.prototype.scrollIntoView = function () {};
installDialogStub(dom.window);

const { render, screen, fireEvent, act, cleanup, within } = await import('@testing-library/react');
const { clickTestView } = await import('./helpers/mockTools');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { default: App } = await import('../src/App');
const { INITIAL_GAME_STATE } = await import('../src/utils/gameModel');
const { INITIAL_CONTRIBUTION, initialSchoolScore } = await import('../src/data/gameRules');
const { MOCK_AUTH_RULES } = await import('../src/utils/mockAuth');
const { getSchoolById, getSchoolViews } = await import('../src/data/schoolDirectory');

type Controller = ReturnType<typeof useMockGame>;
let latest: Controller;
function Probe() {
  latest = useMockGame();
  return null;
}

let codes: string[] = [];
function mountApp(initialState = INITIAL_GAME_STATE) {
  codes = ['111111', '222222', '333333', '444444'];
  let next = 0;
  render(React.createElement(MockGameProvider, {
    initialState,
    generateAuthCode: () => codes[next++ % codes.length],
    children: React.createElement(React.Fragment, null, React.createElement(App), React.createElement(Probe)),
  }));
}
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const phone = () => within(screen.getByRole('region', { name: '순차 온보딩' }));
const alertText = () => phone().getByRole('alert').textContent ?? '';
const openSequential = () => clickTestView('📱 순차 온보딩 (Single Phone)');

function requestCodeFor(email: string) {
  fireEvent.change(phone().getByLabelText('학교 이메일 주소'), { target: { value: email } });
  click('인증 코드 받기');
}
function enterCode(code: string) {
  code.split('').forEach((digit, index) => {
    fireEvent.change(phone().getByLabelText(`인증 코드 ${index + 1}번째 자리`), { target: { value: digit } });
  });
  click('코드 확인');
}
function enrollAs(nickname: string, department: string) {
  fireEvent.change(phone().getByLabelText('플레이어 닉네임'), { target: { value: nickname } });
  fireEvent.click(within(phone().getByRole('group', { name: '소속 학과' })).getByRole('button', { name: department }));
  click('입학하기!');
}

afterEach(() => cleanup());
after(() => { assetHooks.deregister(); dom.window.close(); });

test('기준 6: KAIST 메일로 가입하면 HUD·지도·순위·채팅이 같은 학교·닉네임·학과를 쓴다', () => {
  mountApp();
  openSequential();
  requestCodeFor('student@kaist.ac.kr');
  assert.equal(phone().getByTestId('mock-mailbox-code').textContent, '111111', '목업 메일함에 발급 코드 표시');
  enterCode('111111');
  assert.match(phone().getByTestId('verified-school').textContent ?? '', /KAIST/);
  assert.match(phone().getByText(/처음부터 시작합니다/).textContent ?? '', /KAIST/, '다른 학교는 초기화 안내(D2)');
  enrollAs('카이스트새내기', '전기및전자공학부');
  assert.ok(phone().getByText('KAIST 입학을 환영합니다!'));
  assert.deepEqual(latest.player, { ...latest.player, schoolId: SCHOOL_ID.kaist, departmentId: 'kaist-ee', department: '전기및전자공학부', nickname: '카이스트새내기', email: 'student@kaist.ac.kr' });

  click('캠퍼스로 이동');
  const kaist = getSchoolById(getSchoolViews(initialSchoolScore(SCHOOL_ID.kaist), SCHOOL_ID.kaist), SCHOOL_ID.kaist)!;
  assert.equal(screen.getByRole('heading', { level: 1, name: 'KAIST' }).textContent, 'KAIST', 'HUD 학교명');
  assertAbsent(screen.queryByText(/^입학생:/), '실제 캠퍼스에는 시안 발표 문구가 없다');

  fireEvent.click(screen.getByTitle('채팅창 열기'));
  assert.ok(screen.getAllByText(/카이스트새내기/).length > 0, '채팅 작성자 닉네임');
  assert.ok(screen.getAllByText(/전기및전자공학부/).length > 0, '채팅 작성자 학과');
  fireEvent.click(screen.getByText('▼ 터치하여 닫기'));

  click('순위');
  click('교내 학과');
  assert.ok(screen.getByText(/KAIST 교내 학과/));
  assert.ok(screen.getAllByText('전기및전자공학부').length > 0);
  click('교내 개인');
  assert.ok(screen.getAllByText('카이스트새내기').length > 0);
  assertAbsent(screen.queryByText('외부학교샘플'));
  assert.ok(kaist.isMySchool, '공통 순위에서 KAIST가 내 학교');

  click('지도');
  click('전국 지도');
  click('KAIST 방문');
  assert.ok(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }), '내 학교(KAIST)는 방문이 아니라 내 캠퍼스');
  clickTestView('🗺️ 단일 폰 지도');
  click('한양대학교 방문');
  assert.ok(screen.getByRole('heading', { level: 1, name: '한양대학교' }), '이전 학교(한양대)는 이제 방문 대상');

  // The full ranking screen labels its in-school tabs with the player's school too (fixed in M5).
  clickTestView('🏆 랭킹 화면 (Single Phone)');
  click('교내 학과');
  assert.ok(screen.getByText(/KAIST 교내 학과/));
  assertAbsent(screen.queryByText(/한양대 교내/));
});

test('기준 7: 다른 학교로 가입하면 게임 상태가 새 학교 초기값으로 바뀐다', () => {
  mountApp({ ...INITIAL_GAME_STATE, contribution: 99_999, clickerId: 7, schoolScore: 5_000_000, departmentScore: 900_000 });
  openSequential();
  requestCodeFor('student@pusan.ac.kr');
  enterCode('111111');
  enrollAs('부산갈매기', '해양학과');
  assert.deepEqual(latest.state, { ...INITIAL_GAME_STATE, schoolScore: initialSchoolScore(SCHOOL_ID.pusan) });
  assert.equal(latest.state.contribution, INITIAL_CONTRIBUTION);
  assert.ok(phone().getByText('새 기록으로 처음부터 시작합니다.'));
});

test('기준 7: 같은 학교로 다시 가입하면 진행 유지와 처음부터 중에서 고른 대로 처리한다', () => {
  const progressed = { ...INITIAL_GAME_STATE, contribution: 77_777, clickerId: 6, schoolScore: 3_000_000 };
  mountApp(progressed);
  openSequential();
  requestCodeFor('student@hanyang.ac.kr');
  enterCode('111111');
  assert.equal((phone().getByLabelText('플레이어 닉네임') as HTMLInputElement).value, '열정학번사자', '같은 학교면 기존 닉네임을 채운다');
  assert.equal((phone().getByRole('radio', { name: /진행 유지/ }) as HTMLInputElement).checked, true);
  enrollAs('한양사자', '경영학과');
  assert.deepEqual(latest.state, progressed, '유지: 점수·기여도·클리커 그대로');
  assert.equal(latest.player.departmentId, 'business');

  click('온보딩 다시 시작');
  requestCodeFor('student2@hanyang.ac.kr');
  enterCode('222222');
  fireEvent.click(phone().getByRole('radio', { name: '처음부터 시작' }));
  enrollAs('한양사자', '경영학과');
  assert.deepEqual(latest.state, INITIAL_GAME_STATE, '처음부터: 한양대 초기값');
});

test('기준 1·2: 틀린 코드는 막히고, 한도를 넘긴 뒤에는 화면을 다시 열어도 잠금과 남은 횟수가 유지된다', () => {
  mountApp();
  openSequential();
  requestCodeFor('student@kaist.ac.kr');
  for (let i = 1; i < MOCK_AUTH_RULES.maxAttempts; i++) {
    enterCode('999999');
    assert.match(alertText(), new RegExp(`남은 시도 ${MOCK_AUTH_RULES.maxAttempts - i}회`));
  }
  assertAbsent(phone().queryByLabelText('플레이어 닉네임'), '틀린 코드로는 프로필 단계가 없다');

  clickTestView('🎮 메인 게임 화면');
  openSequential();
  assert.equal(phone().getByTestId('attempts-left').textContent, '남은 시도 1회', '다시 열어도 횟수 유지');
  enterCode('999999');
  enterCode('111111');
  assert.match(alertText(), /시도 횟수를 모두 사용/);
  assert.equal(latest.player.schoolId, SCHOOL_ID.hanyang);
});

test('기준 1·4: 만료 코드는 막히고 재발송은 대기 뒤에만 되며 이전 코드는 무효다', (t) => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval', 'setTimeout'], now: 1_000 });
  mountApp();
  openSequential();
  requestCodeFor('student@kaist.ac.kr');
  click('[시연] 코드 만료시키기');
  enterCode('111111');
  assert.match(alertText(), /유효 시간이 지났습니다/);

  click(/코드 다시 받기/);
  assert.match(alertText(), /초 후에 코드를 다시 받을 수 있습니다/);
  act(() => { t.mock.timers.tick(MOCK_AUTH_RULES.resendCooldownMs); });
  click('코드 다시 받기');
  assert.equal(phone().getByTestId('mock-mailbox-code').textContent, '222222');
  assert.match(phone().getByRole('status').textContent ?? '', /이전 코드는 더 이상 쓸 수 없어요/);
  enterCode('111111');
  assert.match(alertText(), /일치하지 않습니다/);
  enterCode('222222');
  assert.ok(phone().getByLabelText('플레이어 닉네임'));
});

test('기준 5: 미지원·공유 도메인은 거절하고, 이메일 수정 뒤에는 새 코드로 다시 인증해야 한다', () => {
  mountApp();
  openSequential();
  requestCodeFor('student@gmail.com');
  assert.match(alertText(), /지원하지 않는/);
  requestCodeFor('student@shared-demo.ac.kr');
  assert.match(alertText(), /소속 학교를 확정할 수 없습니다/);
  requestCodeFor('student@kaist.ac.kr');
  click('이메일 수정');
  assert.equal((phone().getByLabelText('학교 이메일 주소') as HTMLInputElement).value, 'student@kaist.ac.kr');
  requestCodeFor('student@pusan.ac.kr');
  assert.equal(phone().getByTestId('mock-mailbox-code').textContent, '222222', '새 이메일에는 새 코드');
  enterCode('111111');
  assert.match(alertText(), /일치하지 않습니다/, '이전 이메일의 코드는 쓸 수 없다');
});

test('기준 3: 입학하기를 연달아 눌러도 가입은 한 번만 적용된다', () => {
  mountApp();
  openSequential();
  requestCodeFor('student@kaist.ac.kr');
  enterCode('111111');
  fireEvent.change(phone().getByLabelText('플레이어 닉네임'), { target: { value: '첫번째' } });
  const enroll = phone().getByRole('button', { name: '입학하기!' });
  act(() => {
    fireEvent.click(enroll);
    fireEvent.click(enroll);
  });
  assert.equal(latest.player.nickname, '첫번째');
  assert.ok(phone().getByText('KAIST 입학을 환영합니다!'));
});

test('3단 비교 화면은 고정 샘플을 보여 주고 상태를 바꾸지 않으며 버튼은 순차 온보딩으로 이동한다', () => {
  mountApp();
  clickTestView('3단 온보딩 화면');
  const before = latest.player;
  assertAbsent(screen.queryByText(/한빛/), '가상 학교 한빛대 제거');
  assert.ok(screen.getAllByText(/한양대학교/).length > 0);
  assert.ok(screen.getByText(`처음부터 시작하면 기본 기여도 ${INITIAL_CONTRIBUTION.toLocaleString('ko-KR')}점으로 출발해요!`));
  fireEvent.click(screen.getAllByRole('button', { name: '입학하기!' })[0]);
  assert.equal(latest.player, before);
  assert.ok(screen.getByRole('region', { name: '순차 온보딩' }));
});
