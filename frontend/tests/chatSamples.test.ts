import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { assertAbsent } from './helpers/domAssert';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { installDialogStub } from './helpers/dialogStub';

// Chat samples follow the real ranking and the player's alliance (T44-D1, T44-D2).
// Plan: docs/261109-1829-chat_samples_plan.md §4.
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
const { getSchoolViews } = await import('../src/data/schoolDirectory');
const { getSchoolChatSamples, getUnionChatSamples, sampleDepartments } = await import('../src/data/mockChat');
const { INITIAL_MOCK_ALLIANCES, getAllianceForSchool } = await import('../src/data/allianceModel');
const { getSchoolDepartments, initialSchoolScore } = await import('../src/data/gameRules');

afterEach(() => cleanup());
after(() => { assetHooks.deregister(); dom.window.close(); });

const input = (mySchoolId: SchoolId, myDepartment = '내학과') => ({
  // Same score the app starts from when a player enrols at this school (useMockGame enroll).
  schools: getSchoolViews(initialSchoolScore(mySchoolId), mySchoolId), mySchoolId, myNickname: '나', myDepartment,
});
const byId = (messages: { id: string; text: string }[], id: string) => messages.find((message) => message.id === id)!.text;

test('교내 채팅의 순위 문구는 내 학교의 현재 순위와 바로 위 학교에서 나온다 (T44-D1)', () => {
  for (const id of [SCHOOL_ID.hanyang, SCHOOL_ID.pusan, SCHOOL_ID.chonnam, SCHOOL_ID.jejunu]) {
    const sample = input(id);
    const me = sample.schools.find((school) => school.id === id)!;
    const above = sample.schools.find((school) => school.rank === me.rank - 1)!;
    const messages = getSchoolChatSamples(sample);
    assert.equal(byId(messages, 'msg-2'), `${me.rank}위 실화냐 ${above.rank}위 가자!!`, `school ${id}`);
    assert.equal(byId(messages, 'msg-5'), `${above.shortName} ${above.rank}위 추월 가능?`, `school ${id}`);
  }
});

test('내 학교가 1위면 위 학교 대신 2위 학교를 경계한다', () => {
  const sample = input(SCHOOL_ID.kaist);
  const me = sample.schools.find((school) => school.id === SCHOOL_ID.kaist)!;
  assert.equal(me.rank, 1);
  const messages = getSchoolChatSamples(sample);
  assert.equal(byId(messages, 'msg-2'), '1위 지키자!! 끝까지 간다');
  assert.equal(byId(messages, 'msg-5'), `${sample.schools[1].shortName} 따라온다 긴장해!`);
});

test('교내 채팅 작성자는 모두 내 학교 배지이고 학과는 내 학교 목록(없으면 일반 학과)에서 나온다', () => {
  const hanyangOnly = getSchoolDepartments(SCHOOL_ID.hanyang).map((d) => d.name).filter((name) => !getSchoolDepartments(SCHOOL_ID.kaist).some((d) => d.name === name));
  const sample = input(SCHOOL_ID.kaist, '전산학부');
  const kaist = sample.schools.find((school) => school.id === SCHOOL_ID.kaist)!;
  const messages = getSchoolChatSamples(sample).filter((message) => !message.isMe && !message.isFiltered);
  assert.ok(messages.every((message) => message.schoolInitial === kaist.firstSyllable && message.schoolBadgeBg === kaist.badgeColor));
  assert.ok(messages.every((message) => !hanyangOnly.includes(message.department)), '한양대 학과가 섞이면 안 된다');
  assert.ok(messages.every((message) => message.department !== '전산학부'), '내 학과는 샘플 작성자에서 뺀다');
  assert.equal(sampleDepartments(SCHOOL_ID.snu, '', 3).length, 3, '학과 목록이 없는 학교도 작성자 학과가 있다');
});

test('연합 채팅은 소속 연합이 없으면 null, 있으면 다른 연합 학교만 쓴다 (T44-D2)', () => {
  assert.equal(getAllianceForSchool(INITIAL_MOCK_ALLIANCES, SCHOOL_ID.hanyang, Date.now()), undefined);
  assert.equal(getUnionChatSamples(input(SCHOOL_ID.hanyang), null), null);
  const alliance = getAllianceForSchool(INITIAL_MOCK_ALLIANCES, SCHOOL_ID.kaist, Date.now())!;
  assert.equal(getUnionChatSamples(input(SCHOOL_ID.hanyang), alliance.schoolIds), null, '내 학교가 없는 연합은 쓰지 않는다');
  const sample = input(SCHOOL_ID.kaist);
  const messages = getUnionChatSamples(sample, alliance.schoolIds)!;
  const partners = alliance.schoolIds.filter((id) => id !== SCHOOL_ID.kaist).map((id) => sample.schools.find((school) => school.id === id)!);
  const others = messages.filter((message) => !message.isMe);
  assert.deepEqual(others.map((message) => [message.schoolInitial, message.schoolBadgeBg]), partners.map((school) => [school.firstSyllable, school.badgeColor]));
  assert.ok(others[0].text.startsWith(`${partners[0].shortName} ${partners[0].rank}위`));
  assert.equal(messages.at(-1)!.text, '우리 학교 1위 지킵니다 💪');
});

test('앱: 한양대(연합 없음)는 연합 채팅 빈 상태와 비활성 입력, KAIST는 연합 학교 글을 본다', () => {
  let latest!: ReturnType<typeof useMockGame>;
  const Probe = () => { latest = useMockGame(); return null; };
  render(React.createElement(MockGameProvider, { children: React.createElement(React.Fragment, null, React.createElement(App), React.createElement(Probe)) }));
  const openUnion = () => {
    clickTestView('🎮 메인 게임 화면');
    fireEvent.click(screen.getByTitle('채팅창 열기'));
    const dialog = screen.getByRole('dialog', { name: '채팅' });
    fireEvent.click(within(dialog).getByRole('button', { name: '연합 채팅' }));
    return dialog;
  };

  let dialog = openUnion();
  assert.ok(within(dialog).getByTestId('union-chat-empty'));
  assert.equal((within(dialog).getByRole('textbox', { name: '채팅 메시지' }) as HTMLInputElement).disabled, true);
  assert.ok(!dialog.textContent!.includes('KAIST 1위 수성'), '옛 고정 샘플이 남으면 안 된다');
  fireEvent.click(within(dialog).getByTitle('채팅창 닫기'));

  act(() => { latest.setPlayer({ schoolId: SCHOOL_ID.kaist, department: '전산학부', nickname: '넙죽러' }); });
  dialog = openUnion();
  assertAbsent(within(dialog).queryByTestId('union-chat-empty'));
  // setPlayer keeps the current game score, so expected text comes from the live ranking.
  const schools = getSchoolViews(latest.state.schoolScore, SCHOOL_ID.kaist);
  const alliance = getAllianceForSchool(INITIAL_MOCK_ALLIANCES, SCHOOL_ID.kaist, Date.now())!;
  const expected = getUnionChatSamples({ schools, mySchoolId: SCHOOL_ID.kaist, myNickname: '넙죽러', myDepartment: '전산학부' }, alliance.schoolIds)!;
  for (const message of expected) assert.ok(dialog.textContent!.includes(message.text), message.text);
  for (const id of [SCHOOL_ID.postech]) assert.ok(dialog.textContent!.includes(schools.find((entry) => entry.id === id)!.shortName), `school ${id}`);
});
