import { SCHOOL_ID } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { installDialogStub } from './helpers/dialogStub';

// No Hanyang fixed values after enrolling at another school.
// Plan: docs/261109-1357-school_values_touch44_plan.md §2, §4 criteria 1–3.
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
const { DEMO_VISIT_SCHOOL_ID } = await import('../src/data/schoolDirectory');
const { getVisitGuestbookSamples, getVisitGuestbookPreview, getOurSchoolGuestbookSamples } = await import('../src/data/mockGuestbook');
const { REAL_UNIVERSITIES } = await import('../src/data/universitiesData');

let latest: ReturnType<typeof useMockGame>;
function Probe() {
  latest = useMockGame();
  return null;
}
const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));

/** Mounts the app with the player enrolled at KAIST (M4 sets the school this way after email verification). */
function mountAsKaist() {
  render(React.createElement(MockGameProvider, { children: React.createElement(React.Fragment, null, React.createElement(App), React.createElement(Probe)) }));
  act(() => { latest.setPlayer({ schoolId: SCHOOL_ID.kaist, department: '전산학부', nickname: '넙죽러' }); });
}
const kaist = REAL_UNIVERSITIES.find((school) => school.id === SCHOOL_ID.kaist)!;
const hanyang = REAL_UNIVERSITIES.find((school) => school.id === SCHOOL_ID.hanyang)!;

afterEach(() => cleanup());
after(() => { assetHooks.deregister(); dom.window.close(); });

test('방명록 샘플은 학교 ID로 이름·배지를 채우고, 방명록 주인 학교의 글은 빠진다', () => {
  for (const school of REAL_UNIVERSITIES) {
    assert.ok(getVisitGuestbookSamples(school.id).every((entry) => entry.schoolId !== school.id));
    assert.ok(getOurSchoolGuestbookSamples(school.id).every((entry) => entry.schoolId !== school.id));
  }
  const [entry] = getVisitGuestbookSamples(999_999); // no university row has this ID
  const writer = REAL_UNIVERSITIES.find((school) => school.id === entry.schoolId)!;
  assert.deepEqual([entry.schoolName, entry.schoolInitial, entry.schoolBadgeBg], [writer.name, writer.firstSyllable, writer.badgeColor]);
});

test('방문 미리보기는 전체 방명록과 같은 샘플 중 좋아요가 많은 2건이다', () => {
  for (const id of [SCHOOL_ID.kaist, SCHOOL_ID.pusan, SCHOOL_ID.hanyang]) {
    const all = getVisitGuestbookSamples(id);
    const preview = getVisitGuestbookPreview(id);
    assert.equal(preview.length, 2);
    const top = Math.min(...preview.map((entry) => entry.likeCount));
    assert.ok(preview.every((entry) => all.some((item) => item.id === entry.id)));
    assert.ok(all.filter((entry) => !preview.some((item) => item.id === entry.id)).every((entry) => entry.likeCount <= top));
  }
});

test('KAIST 입학: 실제 우리 학교 방명록 시트는 KAIST 이름이고 KAIST 글이 없으며 개수가 목록 수와 같다', () => {
  mountAsKaist();
  clickTestView('🎮 메인 게임 화면');
  click('우리 학교 방명록 보기');
  const sheet = screen.getByRole('dialog', { name: '우리 학교 방명록' });
  assert.ok(sheet.textContent!.includes(`${kaist.name} 방명록`));
  assert.ok(!sheet.textContent!.includes(hanyang.name));
  assert.ok(!sheet.textContent!.includes(`(${kaist.name})`), '소속 학교 학생의 글이 섞이면 안 된다');
  const expected = getOurSchoolGuestbookSamples(SCHOOL_ID.kaist).length;
  assert.equal(within(sheet).getByTestId('our-guestbook-count').textContent, String(expected));
  assert.equal(within(sheet).getAllByTestId('our-guestbook-entry').length, expected);
});

test('KAIST 입학: 우리 방명록·채팅 시안 화면에 한양대 고정값이 없다', () => {
  mountAsKaist();
  for (const view of ['📜 2-Phone 우리 방명록 (Screen 1 & 2)', '💬 2-Phone 채팅 비교']) {
    clickTestView(view);
    const text = document.body.textContent!;
    assert.ok(!text.includes(hanyang.name), `${view}: ${hanyang.name}`);
    assert.ok(!text.includes('한양'), `${view}: 한양`);
    assert.ok(text.includes(kaist.name), `${view}: ${kaist.name}`);
  }
});

test('KAIST 입학: 방문 시안 화면의 미리보기에 방문 학교 글이 없고 작성자는 KAIST 학생이다', () => {
  mountAsKaist();
  clickTestView('📖 2-Phone 방문교 방명록');
  // The preview notes moved into the 학교 정보 sheet (V2, Q2).
  fireEvent.click(screen.getByTestId('visit-info-button'));
  const notes = screen.getAllByTestId('guestbook-preview-note');
  assert.deepEqual(notes.map((note) => note.textContent!.includes(getVisitGuestbookPreview(DEMO_VISIT_SCHOOL_ID)[notes.indexOf(note)].message)), [true, true]);
  const visited = REAL_UNIVERSITIES.find((school) => school.id === DEMO_VISIT_SCHOOL_ID)!;
  assert.ok(notes.every((note) => !note.textContent!.includes(`(${visited.name})`)));
  assert.ok(document.body.textContent!.includes(`${kaist.name} · 넙죽러 으로 남겨요`));
});
