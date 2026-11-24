// V1·V2 (docs/261110-2005-visit_ui_jeju_map_plan.md §4): a visited campus uses the main game's layout.
// jsdom compares the DOM skeleton and classes; pixel boxes are checked in a browser (tests/e2e/mobile-flows.mjs).
import { SCHOOL_ID } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { assertAbsent } from './helpers/domAssert';
import { installDialogStub } from './helpers/dialogStub';
import { stubMatchMedia } from './helpers/matchMedia';

const assetHooks = registerHooks({
  load(url, context, nextLoad) {
    if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: `export default ${JSON.stringify(url)};`, shortCircuit: true };
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

const { render, screen, fireEvent, cleanup, within } = await import('@testing-library/react');
const { clickTestView } = await import('./helpers/mockTools');
const { MockGameProvider } = await import('../src/hooks/useMockGame');
const { default: App } = await import('../src/App');
const { getSchoolById, getSchoolViews } = await import('../src/data/schoolDirectory');

afterEach(() => cleanup());
after(() => { assetHooks.deregister(); dom.window.close(); });

const click = (name: string | RegExp) => fireEvent.click(screen.getByRole('button', { name }));
const pusan = getSchoolById(getSchoolViews(), SCHOOL_ID.pusan)!;

function mountApp() {
  render(React.createElement(MockGameProvider, null, React.createElement(App)));
}

/** The layout's skeleton: classes of the column, main, the campus frame, and the footer's children. */
function skeleton(kind: 'my-campus' | 'visit') {
  const layout = document.querySelector<HTMLElement>(`[data-game-layout="${kind}"]`);
  assert.ok(layout, `${kind} layout is rendered`);
  const main = layout.querySelector<HTMLElement>('main[data-game-main]')!;
  const frame = main.querySelector<HTMLElement>('[data-campus-stage]')!;
  assert.ok(frame, `${kind}: the campus frame is in main`);
  const footer = layout.querySelector('footer')!;
  return {
    layout: layout.className,
    main: main.className,
    // The frame's own classes; the caller-only extras (cursor state) are not part of the layout.
    frame: frame.className.split(/\s+/).filter((c) => !c.startsWith('cursor-')).sort().join(' '),
    columnChildren: Array.from(layout.children).map((el) => el.tagName),
    footerChildren: footer.children.length,
    nav: !!footer.querySelector('nav'),
    mainChildren: main.querySelectorAll(':scope > div > *').length,
  };
}

/** Opens the Pusan visit from the map tab of the main game. */
function visitPusan() {
  click('지도');
  click('전국 지도');
  click(`${pusan.name} 방문`);
}

test('방문 화면은 메인 게임과 같은 레이아웃 뼈대·캠퍼스 틀을 쓴다 (V1)', () => {
  mountApp();
  clickTestView('🎮 메인 게임 화면');
  const mine = skeleton('my-campus');
  visitPusan();
  const visit = skeleton('visit');
  assert.deepEqual(visit, mine);
  assert.equal(visit.mainChildren, 1, 'main에는 캠퍼스 하나만 있고 그 아래 패널이 없다');
});

test('방문 화면: 방문 HUD·관전 바·학교 정보 표지판, 내 캠퍼스 전용 요소 없음 (V2)', () => {
  mountApp();
  clickTestView('🎮 메인 게임 화면');
  assertAbsent(screen.queryByTestId('spectator-bar'), '내 캠퍼스에는 관전 바가 없다');
  assertAbsent(screen.queryByTestId('visit-tag'));
  visitPusan();

  const layout = document.querySelector<HTMLElement>('[data-game-layout="visit"]')!;
  const header = layout.firstElementChild as HTMLElement;
  assert.ok(within(header).getByRole('heading', { level: 1, name: pusan.name }));
  assert.ok(within(header).getByTestId('visit-tag'));
  assert.ok(within(header).getByRole('button', { name: '지도로 돌아가기' }));
  // Q3: the level badge is not a button while visiting.
  assertAbsent(within(header).queryByRole('button', { name: /Lv\./ }), '방문 중 Lv 배지는 버튼이 아니다');

  const footer = layout.querySelector('footer')!;
  assert.ok(within(footer).getByTestId('spectator-bar'));
  assert.ok(within(footer).getByRole('button', { name: '내 학교로 돌아가기' }));
  // Q4: 지도 stays the current tab.
  assert.equal(within(footer).getByRole('button', { name: '지도' }).getAttribute('aria-current'), 'page');

  const frame = layout.querySelector<HTMLElement>('[data-campus-stage]')!;
  assert.ok(within(frame).getByTestId('visit-info-button'));
  assert.ok(within(frame).getByRole('button', { name: `${pusan.name} 방명록 보기` }));
  assertAbsent(within(frame).queryByTestId('campus-stats-button'), '상세 스탯은 내 캠퍼스에만 있다');
  // The values that sat in panels under the old small campus are only in the 학교 정보 sheet.
  assertAbsent(screen.queryByText('기여 랭커 정보 없음 (목업)'));
  assertAbsent(screen.queryByTestId('guestbook-preview-note'));
});

test('관전 클릭은 안내만 띄우고 건물을 흔들지 않는다 (Q5); 내 캠퍼스 클릭은 흔든다', () => {
  stubMatchMedia(dom.window as unknown as Window, []);
  dom.reconfigure({ url: 'http://localhost/?device=framed' });
  const proto = dom.window.Element.prototype as unknown as { animate?: unknown };
  let animations = 0;
  proto.animate = () => { animations++; return { cancel() {} }; };
  try {
    mountApp();
    clickTestView('🎮 메인 게임 화면');
    visitPusan();
    const campus = screen.getByRole('button', { name: `${pusan.name} 캠퍼스 (관전 모드)` });
    assert.ok(campus.querySelector('[data-wobble]'), '흔들 건물이 있는 장면이다');
    for (let i = 0; i < 3; i++) fireEvent.click(campus);
    assert.equal(animations, 0, '관전 클릭은 흔들림을 시작하지 않는다');
    assert.ok(screen.getByRole('status'));

    click('내 학교로 돌아가기');
    fireEvent.click(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }));
    assert.ok(animations > 0, '같은 스텁으로 내 캠퍼스 클릭은 흔들린다(스텁이 동작함을 확인)');
  } finally {
    delete proto.animate;
    dom.reconfigure({ url: 'http://localhost/' });
  }
});

test('방문 화면 소스에 옛 작은 캠퍼스·하단 패널 크기 규칙이 남지 않는다 (V1)', async () => {
  for (const file of ['src/components/VisitingScreen1Phone.tsx', 'src/components/VisitingParts.tsx']) {
    const source = await readFile(new URL(`../${file}`, import.meta.url), 'utf8');
    for (const pattern of ['max-h-[175px]', 'aspect-[16/9]']) {
      assert.ok(!source.includes(pattern), `${file}: ${pattern} — 방문 캠퍼스는 CampusStage fill을 쓴다`);
    }
  }
});

test('2-Phone 비교의 흐린 방문 화면 HUD는 Tab으로도 닿지 않는다 (V3)', async () => {
  const { VisitingScreen2Phone } = await import('../src/components/VisitingScreen2Phone');
  render(React.createElement(MockGameProvider, null, React.createElement(VisitingScreen2Phone, { schoolId: SCHOOL_ID.pusan })));
  const tag = screen.getByTestId('visit-tag');
  const backdrop = tag.closest('[inert]');
  assert.ok(backdrop, '방문 HUD가 inert 영역 안에 있다');
  assert.ok(backdrop.querySelector('button[aria-label="지도로 돌아가기"]'));
});
