import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { installDialogStub } from './helpers/dialogStub';
import { assertAbsent, assertSameNode } from './helpers/domAssert';

// T5 (docs/261110-1005-mockup_remaining_tasks_plan.md §8): the campus stats sign and sheet replace the panel under the campus.

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
const { clickTestView } = await import('./helpers/mockTools');
const { default: App } = await import('../src/App');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { INITIAL_GAME_STATE } = await import('../src/utils/gameModel');
let game: ReturnType<typeof useMockGame>;
function Probe() { game = useMockGame(); return null; }
function mountGame() {
  render(React.createElement(MockGameProvider, null, React.createElement(React.Fragment, null,
    React.createElement(App), React.createElement(Probe))));
  clickTestView('🎮 메인 게임 화면');
}
const statsButton = () => screen.getByRole('button', { name: /^상세 스탯 보기/ });
afterEach(cleanup);
after(() => { assets.deregister(); dom.window.close(); });

test('T5: 메인 화면에는 보유 기여도 패널이 없고, 스탯 버튼이 아이콘·“상세 스탯”·보유 기여도를 보인다', () => {
  mountGame();
  const main = document.querySelector('main[data-game-main]')!;
  assert.doesNotMatch(main.textContent ?? '', /보유 기여도|클리커 Lv\./, '캠퍼스 아래 스탯 패널은 모달로 옮겼다');
  const button = statsButton();
  assert.ok(main.contains(button));
  assert.ok(button.querySelector('svg'), '아이콘');
  assert.match(button.textContent ?? '', /상세 스탯/);
  const shown = within(button).getByTestId('campus-stats-contribution');
  assert.equal(shown.textContent, game.state.contribution.toLocaleString('ko-KR'));
  assert.equal(button.getAttribute('aria-label'), `상세 스탯 보기, 보유 기여도 ${game.state.contribution.toLocaleString('ko-KR')}`);

  fireEvent.click(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }));
  assert.equal(shown.textContent, game.state.contribution.toLocaleString('ko-KR'), '캠퍼스를 누르면 숫자가 바로 바뀐다');
});

test('T5: 큰 보유 기여도는 버튼에서 M·B·T로 줄이고 접근성 이름에는 전체 숫자를 쓴다', () => {
  render(React.createElement(MockGameProvider, { initialState: { ...INITIAL_GAME_STATE, contribution: 1_234_567_890 }, children: React.createElement(App) }));
  clickTestView('🎮 메인 게임 화면');
  const button = statsButton();
  assert.equal(within(button).getByTestId('campus-stats-contribution').textContent, '1.2B');
  assert.match(button.getAttribute('aria-label') ?? '', /1,234,567,890$/);
});

test('T5: 스탯 버튼은 캠퍼스 점수를 올리지 않고 시트를 열며, 닫으면 버튼으로 포커스가 돌아온다', () => {
  mountGame();
  const button = statsButton();
  button.focus();
  const before = game.state;
  fireEvent.click(button);
  assert.deepEqual(game.state, before, '스탯 버튼 클릭은 캠퍼스 클릭으로 전파되지 않는다');
  const dialog = screen.getByRole('dialog', { name: '상세 스탯' });
  assert.ok(dialog.hasAttribute('open'));
  const text = dialog.textContent ?? '';
  for (const part of [`클리커 Lv.${game.clicker.level}`, `클릭당 ${game.normalPower}점`, '보유 기여도', game.state.contribution.toLocaleString('ko-KR'), '강화 비용', '활성 버프 없음', `캠퍼스 Lv.${game.campus.level}`, ...game.campus.features]) {
    assert.ok(text.includes(part), part);
  }
  fireEvent(dialog, new dom.window.Event('cancel', { bubbles: false, cancelable: true }));
  assertAbsent(screen.queryByRole('dialog', { name: '상세 스탯' }), 'Esc(cancel)로 닫힌다');
  assertSameNode(document.activeElement, button);

  fireEvent.click(button);
  fireEvent.click(within(screen.getByRole('dialog', { name: '상세 스탯' })).getByRole('button', { name: '▼ 터치하여 닫기' }));
  assertAbsent(screen.queryByRole('dialog', { name: '상세 스탯' }), '닫기 버튼으로 닫힌다');
  assertSameNode(document.activeElement, button);
});

test('T5: 시트의 “상점에서 강화하기”는 시트를 닫고 상점 탭으로 간다', () => {
  mountGame();
  fireEvent.click(statsButton());
  fireEvent.click(within(screen.getByRole('dialog', { name: '상세 스탯' })).getByRole('button', { name: '상점에서 강화하기' }));
  assertAbsent(screen.queryByRole('dialog', { name: '상세 스탯' }));
  assert.ok(document.querySelector('main[data-game-main]')?.textContent?.includes('캠퍼스 잡화점'));
});

test('T5: 채팅 1·방명록 1 비교 화면도 패널 없이 같은 스탯 버튼과 공유 기여도를 보인다 (Q2)', () => {
  mountGame();
  act(() => { game.upgrade(); });
  for (const view of ['💬 2-Phone 채팅 비교', '📜 2-Phone 우리 방명록 (Screen 1 & 2)']) {
    clickTestView(view);
    assert.doesNotMatch(document.body.textContent ?? '', /보유 기여도 \d|클리커 Lv\./, view);
    const buttons = screen.getAllByRole('button', { name: /^상세 스탯 보기/ });
    assert.ok(buttons.length > 0, view);
    for (const button of buttons) {
      assert.equal(within(button).getByTestId('campus-stats-contribution').textContent, game.state.contribution.toLocaleString('ko-KR'), view);
    }
  }
});
