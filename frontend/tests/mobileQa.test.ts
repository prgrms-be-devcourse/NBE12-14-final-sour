import assert from 'node:assert/strict';
import { assertAbsent, assertSameNode } from './helpers/domAssert';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { installDialogStub } from './helpers/dialogStub';

const assets = registerHooks({
  load(url, context, nextLoad) {
    if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) {
      return { format: 'module', source: `export default ${JSON.stringify(new URL(url).pathname)};`, shortCircuit: true };
    }
    return nextLoad(url, context);
  },
});
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node,
  requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window),
  cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window), IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
dom.window.HTMLElement.prototype.scrollIntoView = function () {};
installDialogStub(dom.window);

const { render, screen, fireEvent, cleanup, within, act } = await import('@testing-library/react');
const { clickTestView, openTestTools } = await import('./helpers/mockTools');
const { default: App } = await import('../src/App');
const { BottomSheetDialog } = await import('../src/components/BottomSheetDialog');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
let game: ReturnType<typeof useMockGame>;
function Probe() { game = useMockGame(); return null; }
function mountGame() {
  render(React.createElement(MockGameProvider, null, React.createElement(React.Fragment, null,
    React.createElement(App), React.createElement(Probe))));
  clickTestView('🎮 메인 게임 화면');
}
afterEach(cleanup);
after(() => { assets.deregister(); dom.window.close(); });

test('M6: 서비스 모달은 배경 페이지를 고정하고 해제 시 기존 스타일·스크롤 위치를 복원한다 (DOM 계약)', (t) => {
  const body = document.body;
  const root = document.documentElement;
  const originalBodyStyle = body.style.cssText;
  const originalRootStyle = root.style.cssText;
  const scrollX = Object.getOwnPropertyDescriptor(dom.window, 'scrollX')!;
  const scrollY = Object.getOwnPropertyDescriptor(dom.window, 'scrollY')!;
  const scrollTo = t.mock.method(dom.window, 'scrollTo', () => {});
  body.style.setProperty('position', 'relative', 'important');
  body.style.setProperty('top', '7px');
  root.style.setProperty('overflow', 'auto', 'important');
  const beforeBodyStyle = body.style.cssText;
  const beforeRootStyle = root.style.cssText;
  Object.defineProperties(dom.window, {
    scrollX: { configurable: true, value: 12 }, scrollY: { configurable: true, value: 240 },
  });
  const view = render(React.createElement(BottomSheetDialog, {
    label: '스크롤 잠금 검사', onClose() {}, children: React.createElement('div', null, '목록'),
  }));
  try {
    assert.equal(body.style.position, 'fixed', '배경 body가 터치 스크롤되지 않도록 고정');
    assert.equal(body.style.top, '-240px');
    assert.equal(body.style.left, '-12px');
    assert.equal(root.style.overflow, 'hidden');
    Object.defineProperties(dom.window, {
      scrollX: { configurable: true, value: 0 }, scrollY: { configurable: true, value: 0 },
    });
    view.unmount();
    assert.equal(body.style.cssText, beforeBodyStyle);
    assert.equal(root.style.cssText, beforeRootStyle);
    assert.deepEqual(scrollTo.mock.calls.at(-1)?.arguments, [12, 240]);
  } finally {
    view.unmount();
    body.style.cssText = originalBodyStyle;
    root.style.cssText = originalRootStyle;
    Object.defineProperties(dom.window, { scrollX, scrollY });
  }
});

test('M6: StrictMode의 재실행과 겹친 모달 정리 뒤에도 배경 스크롤 잠금이 남지 않는다', () => {
  const beforeBody = document.body.style.cssText;
  const beforeRoot = document.documentElement.style.cssText;
  const view = render(React.createElement(React.StrictMode, null,
    React.createElement(BottomSheetDialog, { label: '첫 모달', onClose() {}, children: '첫 목록' }),
    React.createElement(BottomSheetDialog, { label: '둘째 모달', onClose() {}, children: '둘째 목록' }),
  ));
  assert.equal(document.body.style.position, 'fixed');
  view.unmount();
  assert.equal(document.body.style.cssText, beforeBody);
  assert.equal(document.documentElement.style.cssText, beforeRoot);
});

test('M6: 채팅 미리보기는 Enter/Space의 기본 활성화를 제공하는 네이티브 버튼이다', () => {
  mountGame();
  const trigger = screen.getByTitle('채팅창 열기');
  assert.equal(trigger.tagName, 'BUTTON');
  assert.equal(trigger.getAttribute('type'), 'button');
});

test('M6: 방명록 표지판은 캠퍼스 득점과 분리된 키보드 접근 가능 버튼이다', () => {
  mountGame();
  const trigger = screen.getByTitle('우리 학교 방명록 보기');
  assert.equal(trigger.tagName, 'BUTTON');
  assert.equal(trigger.tabIndex, 0);
  trigger.focus();
  const before = game.state;
  fireEvent.keyDown(trigger, { key: 'Enter' });
  fireEvent.keyUp(trigger, { key: 'Enter' });
  fireEvent.click(trigger); // jsdom does not synthesize a native button's keyboard click.
  const dialog = screen.getByRole('dialog', { name: '우리 학교 방명록' });
  assert.deepEqual(game.state, before, '방명록 조작이 캠퍼스 클릭으로 전파되지 않는다');
  fireEvent.click(within(dialog).getByTitle('방명록 닫기'));
  assertSameNode(document.activeElement, trigger);
});

test('M6: 채팅 시트는 모달로 열리고 cancel·닫기 후 트리거에 포커스를 돌려준다', () => {
  mountGame();
  const trigger = screen.getByTitle('채팅창 열기');
  trigger.focus();
  fireEvent.click(trigger);
  const dialog = screen.getByRole('dialog', { name: '채팅' });
  assert.equal(dialog.tagName, 'DIALOG');
  assert.ok(dialog.hasAttribute('open'), 'showModal로 열림');
  assert.equal(dialog.dataset.modal, 'true', 'non-modal open 속성이 아닌 showModal API 사용');
  assert.ok(dialog.contains(document.activeElement), '열 때 내부에 포커스');
  fireEvent(dialog, new dom.window.Event('cancel', { bubbles: false, cancelable: true }));
  assertAbsent(screen.queryByRole('dialog', { name: '채팅' }));
  assertSameNode(document.activeElement, trigger);
  fireEvent.click(trigger);
  fireEvent.click(within(screen.getByRole('dialog', { name: '채팅' })).getByTitle('채팅창 닫기'));
  assertSameNode(document.activeElement, trigger);
});

test('M6: 크기 변경 이벤트는 열린 시트·작성 내용·게임 진행 상태를 초기화하지 않는다 (레이아웃 검증 아님)', () => {
  mountGame();
  act(() => { game.buy('coffee'); });
  const state = game.state;
  fireEvent.click(screen.getByTitle('채팅창 열기'));
  const dialog = screen.getByRole('dialog', { name: '채팅' });
  const input = within(dialog).getByRole('textbox', { name: '채팅 메시지' }) as HTMLInputElement;
  fireEvent.change(input, { target: { value: '회전 중 작성 내용' } });
  for (const [width, height] of [[844, 390], [390, 844]]) {
    Object.defineProperties(dom.window, {
      innerWidth: { configurable: true, value: width }, innerHeight: { configurable: true, value: height },
    });
    act(() => {
      dom.window.dispatchEvent(new dom.window.Event('resize'));
      dom.window.dispatchEvent(new dom.window.Event('orientationchange'));
    });
    assertSameNode(screen.getByRole('dialog', { name: '채팅' }), dialog);
    assert.equal(input.value, '회전 중 작성 내용');
    assert.deepEqual(game.state, state);
  }
});

test('M6: 게임 화면 순위 탭은 목록 끝까지 스크롤하면 다음 10개 대학을 자동으로 불러온다 (iPhone 보고 5-4)', () => {
  mountGame();
  fireEvent.click(screen.getByRole('button', { name: '순위' }));
  const more = () => screen.queryByRole('button', { name: /10개 대학 더 불러오기/ });
  const before = more()!.textContent!.match(/\((\d+) \/ (\d+)\)/)!;
  const list = more()!.parentElement!;
  const setGeometry = (scrollHeight: number, clientHeight: number, scrollTop: number) => {
    Object.defineProperty(list, 'scrollHeight', { configurable: true, value: scrollHeight });
    Object.defineProperty(list, 'clientHeight', { configurable: true, value: clientHeight });
    list.scrollTop = scrollTop;
    fireEvent.scroll(list);
  };

  setGeometry(1000, 160, 300);
  assert.match(more()!.textContent!, new RegExp(`\\(${before[1]} / `), '목록 중간에서는 불러오지 않는다');
  act(() => {
    // Two scroll events at the end before the longer list renders must load only once.
    setGeometry(1000, 160, 840);
    setGeometry(1000, 160, 840);
  });
  assert.match(more()!.textContent!, new RegExp(`\\(${Number(before[1]) + 10} / ${before[2]}\\)`), '끝에서는 10개를 한 번만 더 불러온다');
});

// Touch areas 44px (user instruction 2026-11-09). Sizes and overlaps are checked in Chromium by
// tests/e2e/touch-targets.mjs; this fixes where the service area and exclusions are marked.
test('터치 영역 44px: 서비스 화면만 표시하고 [시연]·시안 비교·지도 마커는 제외한다', () => {
  mountGame();
  const area = () => document.querySelector('[data-touch-area="44"]');
  assert.ok(area()?.contains(screen.getByRole('navigation', { name: '주요 메뉴' })), '게임 화면은 서비스 영역');
  assert.ok(!area()?.contains(openTestTools().getByRole('button', { name: '🏛️ 10단계 캠퍼스 성장 시트 (5x2)' })), '성장 시트는 서비스 영역 밖');
  clickTestView('🗺️ 단일 폰 지도');
  const markers = document.querySelectorAll('[data-school-id]:is(button), [data-cluster-region]');
  assert.ok(markers.length > 0);
  for (const marker of markers) assert.ok(marker.hasAttribute('data-no-touch44'), marker.getAttribute('aria-label') ?? '');
  assert.ok(area()?.querySelector('button[aria-label$=" 방문"]:not([data-no-touch44])'), '마커 대신 목록의 방문 버튼은 44px 대상');
  for (const comparison of ['📜 2-Phone 우리 방명록 (Screen 1 & 2)', '💬 2-Phone 채팅 비교', '📖 2-Phone 방문교 방명록']) {
    clickTestView(comparison);
    assert.equal(area(), null, comparison);
  }
});
