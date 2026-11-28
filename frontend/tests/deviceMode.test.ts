import assert from 'node:assert/strict';
import { after, afterEach, beforeEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { installDialogStub } from './helpers/dialogStub';
import { assertAbsent, assertSameNode } from './helpers/domAssert';
import { stubMatchMedia } from './helpers/matchMedia';

// T2 (docs/261110-1005-mockup_remaining_tasks_plan.md §5): phone detection, the full shell and the mock menu sheet (Q5).

const assets = registerHooks({
  load(url, context, nextLoad) {
    if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) {
      return { format: 'module', source: `export default ${JSON.stringify(new URL(url).pathname)};`, shortCircuit: true };
    }
    return nextLoad(url, context);
  },
});
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node,
  requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window),
  cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window), IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
dom.window.HTMLElement.prototype.scrollIntoView = function () {};
installDialogStub(dom.window);

const { act, render, screen, fireEvent, cleanup, within } = await import('@testing-library/react');
const { default: App } = await import('../src/App');
const { MockGameProvider } = await import('../src/hooks/useMockGame');
const { GAME_WIDE_QUERY, MOBILE_DEVICE_QUERY, resolveDeviceMode, resolveGameLayout } = await import('../src/utils/deviceMode');

const win = dom.window as unknown as Window;
const asPhone = () => stubMatchMedia(win, [MOBILE_DEVICE_QUERY]);
const asDesktop = () => stubMatchMedia(win, []);
const mountApp = () => render(React.createElement(MockGameProvider, null, React.createElement(App)));
const shells = () => [...document.querySelectorAll<HTMLElement>('[data-phone-shell]')];

beforeEach(() => dom.reconfigure({ url: 'http://localhost/' }));
afterEach(cleanup);
after(() => { assets.deregister(); dom.window.close(); });

test('T2: ?device=full·framed가 미디어쿼리보다 우선하고, 그 밖의 값은 무시한다', () => {
  assert.equal(resolveDeviceMode('', true), 'full');
  assert.equal(resolveDeviceMode('', false), 'framed');
  assert.equal(resolveDeviceMode('?device=full', false), 'full');
  assert.equal(resolveDeviceMode('?device=framed', true), 'framed');
  assert.equal(resolveDeviceMode('?device=tablet', true), 'full');
  assert.equal(resolveDeviceMode('?x=1&device=full', false), 'full');
  // Portrait phones (width) and phones on their side (height) both count; a mouse never does.
  assert.match(MOBILE_DEVICE_QUERY, /\(pointer: coarse\) and \(max-width: 480px\)/);
  assert.match(MOBILE_DEVICE_QUERY, /\(pointer: coarse\) and \(max-height: 480px\)/);
});

test('T2: index.html viewport에 viewport-fit=cover가 있다 (safe-area inset 사용 조건)', () => {
  const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  const viewport = html.match(/<meta name="viewport" content="([^"]+)"/)?.[1] ?? '';
  assert.match(viewport, /(^|,\s*)viewport-fit=cover(\s*,|$)/);
});

test('SCRUM-212: 시안 모드에서도 메뉴·도구는 처음에는 닫혀 있고 390×844 프레임을 유지한다', () => {
  dom.reconfigure({ url: 'http://localhost/?device=framed' });
  asDesktop();
  mountApp();
  assertAbsent(screen.queryByRole('navigation', { name: '서비스 흐름' }));
  assertAbsent(screen.queryByRole('region', { name: '[시연] 도구' }));
  assert.ok(screen.getByRole('button', { name: '테스트 도구' }));
  const [shell] = shells();
  assert.equal(shell.dataset.phoneShell, 'framed');
  assert.match(shell.className, /h-\[844px\]/);
  assert.equal(shell.querySelectorAll('[data-phone-status-bar], [data-phone-home-bar]').length, 2);
});

test('SCRUM-206: 3열 미디어쿼리를 따르되 명시적 기기 프리뷰는 보존한다', () => {
  assert.equal(resolveGameLayout('', true), 'wide');
  assert.equal(resolveGameLayout('', false), 'compact');
  assert.equal(resolveGameLayout('?device=full', true), 'compact');
  assert.equal(resolveGameLayout('?device=framed', true), 'legacy');
});

test('SCRUM-206: 3열과 좁은 PC 1열을 오가도 같은 캠퍼스와 활성 상점이 유지된다', () => {
  const wideQuery = Object.assign(new dom.window.EventTarget(), { matches: true });
  const narrowQuery = Object.assign(new dom.window.EventTarget(), { matches: false });
  Object.defineProperty(win, 'matchMedia', { configurable: true, value: (query: string) => query === GAME_WIDE_QUERY ? wideQuery : narrowQuery });
  const resize = (wide: boolean) => act(() => {
    wideQuery.matches = wide;
    wideQuery.dispatchEvent(new dom.window.Event('change'));
  });
  mountApp();
  assert.ok(screen.getByRole('complementary', { name: '학교 패널' }));
  assert.ok(screen.getByRole('complementary', { name: '전국 경쟁 패널' }));
  assertAbsent(screen.queryByText('UI Mockup'));
  const campus = screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
  assert.equal(shells()[0].dataset.phoneShell, 'embedded');

  resize(false);
  assertAbsent(screen.queryByRole('complementary', { name: '학교 패널' }));
  assert.ok(screen.getByRole('navigation', { name: '주요 메뉴' }));
  assert.equal(shells()[0].dataset.phoneShell, 'full');
  assertSameNode(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }), campus);
  fireEvent.click(screen.getByRole('button', { name: '상점' }));
  const shop = screen.getByTestId('clicker-upgrade-preview');

  resize(true);
  assertSameNode(screen.getByTestId('clicker-upgrade-preview'), shop);
  assert.equal(shells()[0].dataset.phoneShell, 'embedded');
});

test('SCRUM-207: 방문 학교 요약·상세·방명록을 바꾸고 채팅 탭 선택은 리사이즈 후 복원한다', () => {
  const wideQuery = Object.assign(new dom.window.EventTarget(), { matches: true });
  const narrowQuery = Object.assign(new dom.window.EventTarget(), { matches: false });
  Object.defineProperty(win, 'matchMedia', { configurable: true, value: (query: string) => query === GAME_WIDE_QUERY ? wideQuery : narrowQuery });
  const resize = (wide: boolean) => act(() => {
    wideQuery.matches = wide;
    wideQuery.dispatchEvent(new dom.window.Event('change'));
  });
  mountApp();
  const panel = () => within(screen.getByRole('complementary', { name: '학교 패널' }));
  assert.match(panel().getByRole('region', { name: '학교 요약' }).textContent ?? '', /한양대학교.*학교 점수.*전국 순위/);
  const detail = panel().getByRole('tab', { name: '상세' });
  const chat = panel().getByRole('tab', { name: '채팅' });
  detail.focus();
  fireEvent.keyDown(detail, { key: 'ArrowRight' });
  assertSameNode(document.activeElement, chat);
  assert.equal(detail.getAttribute('aria-selected'), 'true', '방향키는 포커스만 이동한다');
  fireEvent.click(chat);
  const message = panel().getByRole('textbox', { name: '채팅 메시지' });
  fireEvent.change(message, { target: { value: '동시 플레이 메시지' } });
  fireEvent.click(panel().getByRole('button', { name: '전송' }));
  fireEvent.click(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }));
  assert.equal(panel().getAllByText('동시 플레이 메시지').length, 1);
  assertAbsent(screen.queryByRole('dialog'));

  const competition = within(screen.getByRole('complementary', { name: '전국 경쟁 패널' }));
  fireEvent.click(competition.getByRole('button', { name: /^KAIST 전국/ }));
  fireEvent.click(competition.getByRole('button', { name: 'KAIST 방문' }));
  assert.match(panel().getByRole('region', { name: '학교 요약' }).textContent ?? '', /방문 학교KAIST/);
  assert.equal(panel().getByRole('tab', { name: '채팅' }).getAttribute('aria-selected'), 'true');
  const visitCampus = screen.getByRole('button', { name: 'KAIST 캠퍼스 (관전 모드)' });
  resize(false);
  assertAbsent(screen.queryByRole('complementary', { name: '학교 패널' }));
  assertSameNode(screen.getByRole('button', { name: 'KAIST 캠퍼스 (관전 모드)' }), visitCampus);
  resize(true);
  panel().getByRole('tabpanel', { name: '채팅' });
  fireEvent.click(screen.getByTestId('visit-info-button'));
  panel().getByRole('heading', { name: 'KAIST 학교 정보' });
  fireEvent.click(panel().getByRole('button', { name: '방명록 전체보기' }));
  assertSameNode(document.activeElement, panel().getByRole('tab', { name: '방명록' }));
  panel().getByRole('heading', { name: 'KAIST 방명록' });
  assertAbsent(screen.queryByRole('dialog'));
  fireEvent.click(screen.getByRole('button', { name: '내 학교로 돌아가기' }));
  panel().getByRole('heading', { name: '한양대학교 방명록' });
  assertAbsent(panel().queryByRole('textbox'));
});

test('④ PC 학교 패널 탭은 채팅이며 우리 학교 채팅 문구가 없다', () => {
  stubMatchMedia(win, [GAME_WIDE_QUERY]);
  mountApp();
  const panel = within(screen.getByRole('complementary', { name: '학교 패널' }));
  panel.getByRole('tab', { name: '채팅' });
  assertAbsent(screen.queryByText('우리 학교 채팅'));
});

test('mock PC 패널에서 교내·연합 전환을 제공하고 닫기 버튼은 없다', () => {
  stubMatchMedia(win, [GAME_WIDE_QUERY]);
  mountApp();
  const panel = within(screen.getByRole('complementary', { name: '학교 패널' }));
  fireEvent.click(panel.getByRole('tab', { name: /채팅/ }));
  panel.getByRole('button', { name: '교내 채팅' });
  assertAbsent(panel.queryByRole('button', { name: '채팅창 닫기' }));
  fireEvent.click(panel.getByRole('button', { name: '연합 채팅' }));
  panel.getByTestId('union-chat-empty');
});

test('SCRUM-208: 지도 선택은 미리보기만 갱신하고 방문 버튼만 캠퍼스를 바꾼다', () => {
  const wideQuery = Object.assign(new dom.window.EventTarget(), { matches: true });
  const otherQuery = Object.assign(new dom.window.EventTarget(), { matches: false });
  Object.defineProperty(win, 'matchMedia', { configurable: true, value: (query: string) => query === GAME_WIDE_QUERY ? wideQuery : otherQuery });
  const resize = (wide: boolean) => act(() => {
    wideQuery.matches = wide;
    wideQuery.dispatchEvent(new dom.window.Event('change'));
  });
  mountApp();
  const panel = () => within(screen.getByRole('complementary', { name: '전국 경쟁 패널' }));
  const campus = screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
  const map = panel().getByRole('tab', { name: '지도' });
  assert.equal(map.getAttribute('aria-selected'), 'true');
  map.focus();
  fireEvent.keyDown(map, { key: 'End' });
  const alliance = panel().getByRole('tab', { name: '연합 대결' });
  assertSameNode(document.activeElement, alliance);
  assert.equal(map.getAttribute('aria-selected'), 'true');
  fireEvent.click(alliance);
  panel().getByRole('tabpanel', { name: '연합 대결' });
  fireEvent.click(panel().getByRole('tab', { name: '순위' }));
  panel().getByRole('button', { name: '학교 순위' });
  assertSameNode(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }), campus);
  resize(false);
  assertAbsent(screen.queryByRole('complementary', { name: '전국 경쟁 패널' }));
  resize(true);
  panel().getByRole('tabpanel', { name: '순위' });
  fireEvent.click(panel().getByRole('tab', { name: '지도' }));
  fireEvent.click(panel().getByRole('button', { name: /^KAIST 전국/ }));
  assert.equal(within(panel().getByRole('region', { name: '학교 목록' })).getByRole('button', { name: /^KAIST$/ }).getAttribute('aria-expanded'), 'true');
  assertSameNode(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }), campus);
  const list = within(panel().getByRole('region', { name: '학교 목록' }));
  fireEvent.click(list.getByRole('button', { name: '서울대학교' }));
  assertSameNode(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }), campus);
  assertAbsent(panel().queryByRole('button', { name: 'KAIST 방문' }));
  const visit = panel().getByRole('button', { name: '서울대학교 방문' });
  fireEvent.click(screen.getByRole('button', { name: '상점' }));
  fireEvent.click(screen.getByRole('button', { name: '커피 한 잔 구매 선택' }));
  assert.equal(visit.getAttribute('aria-disabled'), 'true');
  fireEvent.click(visit);
  const confirmation = within(screen.getByRole('dialog', { name: '아이템 구매 확인' }));
  fireEvent.click(confirmation.getByRole('button', { name: '아니오' }));
  assert.equal(visit.getAttribute('aria-disabled'), 'false');
  assertAbsent(screen.queryByRole('button', { name: '서울대학교 캠퍼스 (관전 모드)' }), '차단한 방문을 나중에 자동 실행하지 않는다');
  fireEvent.click(visit);
  screen.getByRole('button', { name: '서울대학교 캠퍼스 (관전 모드)' });
  assertAbsent(screen.queryByRole('button', { name: '캠퍼스 클릭 영역' }));
});

test('SCRUM-225: PC 지도 검색 시 목록은 지도 아래에 고정되고 기존 방문 선택과 제외 안내를 유지한다', () => {
  const wideQuery = Object.assign(new dom.window.EventTarget(), { matches: true });
  const otherQuery = Object.assign(new dom.window.EventTarget(), { matches: false });
  Object.defineProperty(win, 'matchMedia', { configurable: true, value: (query: string) => query === GAME_WIDE_QUERY ? wideQuery : otherQuery });
  mountApp();
  const panel = () => within(screen.getByRole('complementary', { name: '전국 경쟁 패널' }));
  const mapArea = () => panel().getByTestId('map-area');
  const listSection = () => panel().getByRole('region', { name: '학교 목록' });
  const list = () => within(listSection());
  const search = () => panel().getByRole('textbox', { name: '학교 검색' });

  // 서울대학교 선택
  fireEvent.click(within(listSection()).getByRole('button', { name: '서울대학교' }));
  assert.ok(list().getByRole('button', { name: '서울대학교 방문' }));
  assert.equal(list().getByRole('button', { name: '서울대학교' }).getAttribute('aria-expanded'), 'true');

  // KAIST 검색: 목록은 지도 아래에 유지, 제목·옆 건수 갱신, 방문 대상은 서울대학교 유지 + 제외 안내 표시
  fireEvent.change(search(), { target: { value: 'KAIST' } });
  assert.ok(
    Boolean(mapArea().compareDocumentPosition(listSection()) & dom.window.Node.DOCUMENT_POSITION_FOLLOWING),
    '검색 중에도 학교 목록이 지도 아래에 유지된다',
  );
  assert.equal(within(listSection()).getByRole('heading', { level: 3 }).textContent, '검색 학교 목록 - 1개교');
  assert.equal(panel().getByTestId('map-search-status').textContent, '1개교');
  assertAbsent(panel().queryByRole('button', { name: '서울대학교 방문' }));
  assert.match(panel().getByText(/선택 학교가 검색 결과에 없습니다/).textContent ?? '', /검색 결과/);

  // 검색어만 지우면 서울대학교 선택은 유지하고 안내만 제거
  fireEvent.change(search(), { target: { value: '' } });
  assert.ok(list().getByRole('button', { name: '서울대학교 방문' }));
  assertAbsent(panel().queryByText(/검색 결과에 없습니다/));
  assertAbsent(panel().queryByTestId('map-search-status'));
  assert.match(within(listSection()).getByRole('heading', { level: 3 }).textContent ?? '', /^학교 목록 · \d+개교$/);

  // 다시 KAIST 검색 후 KAIST 행 선택 시 방문 대상이 KAIST로 바뀌고 안내가 사라짐
  fireEvent.change(search(), { target: { value: 'KAIST' } });
  assert.ok(panel().getByText(/검색 결과에 없습니다/));
  fireEvent.click(list().getByRole('button', { name: /^KAIST$/ }));
  assert.ok(list().getByRole('button', { name: 'KAIST 방문' }));
  assertAbsent(panel().queryByText(/검색 결과에 없습니다/));
  fireEvent.click(list().getByRole('button', { name: /^KAIST$/ }));
  assertAbsent(list().queryByRole('button', { name: 'KAIST 방문' }));
  fireEvent.click(list().getByRole('button', { name: /^KAIST$/ }));

  // 0건 검색(충청 권역 포함): 검색 결과 없음 + 0개교 제목 + 목록 지도 아래 유지
  fireEvent.click(panel().getByRole('button', { name: '충청' }));
  fireEvent.change(search(), { target: { value: '없는학교-SCRUM225' } });
  assert.equal(panel().getByTestId('map-search-status').textContent, '검색 결과 없음');
  assert.equal(within(listSection()).getByRole('heading', { level: 3 }).textContent, '검색 학교 목록 - 0개교');
  assert.ok(panel().getByText(/검색 결과에 없습니다/));
  assert.ok(
    Boolean(mapArea().compareDocumentPosition(listSection()) & dom.window.Node.DOCUMENT_POSITION_FOLLOWING),
    '충청 0건 검색에서도 목록이 지도 아래에 유지된다',
  );
});

test('SCRUM-209: 방문 학교·양쪽 탭·구매 확인을 리사이즈 후 복원하고 상점을 닫으면 직전 캠퍼스로 돌아간다', () => {
  const wideQuery = Object.assign(new dom.window.EventTarget(), { matches: true });
  const otherQuery = Object.assign(new dom.window.EventTarget(), { matches: false });
  Object.defineProperty(win, 'matchMedia', { configurable: true, value: (query: string) => query === GAME_WIDE_QUERY ? wideQuery : otherQuery });
  const resize = (wide: boolean) => act(() => {
    wideQuery.matches = wide;
    wideQuery.dispatchEvent(new dom.window.Event('change'));
  });
  mountApp();
  const left = () => within(screen.getByRole('complementary', { name: '학교 패널' }));
  const right = () => within(screen.getByRole('complementary', { name: '전국 경쟁 패널' }));
  fireEvent.click(right().getByRole('button', { name: /^KAIST 전국/ }));
  fireEvent.click(right().getByRole('button', { name: 'KAIST 방문' }));
  fireEvent.click(left().getByRole('tab', { name: '방명록' }));
  fireEvent.click(right().getByRole('tab', { name: '순위' }));
  fireEvent.click(screen.getByRole('button', { name: '상점' }));
  const shop = screen.getByRole('main');
  assertSameNode(document.activeElement, shop);
  left().getByRole('heading', { name: 'KAIST 방명록' });
  fireEvent.click(screen.getByRole('button', { name: '커피 한 잔 구매 선택' }));
  const dialog = screen.getByRole('dialog', { name: '아이템 구매 확인' });
  resize(false);
  assertSameNode(screen.getByRole('main'), shop);
  assertSameNode(screen.getByRole('dialog', { name: '아이템 구매 확인' }), dialog);
  resize(true);
  left().getByRole('tabpanel', { name: '방명록' });
  right().getByRole('tabpanel', { name: '순위' });
  assertSameNode(screen.getByRole('dialog', { name: '아이템 구매 확인' }), dialog);
  fireEvent.click(within(dialog).getByRole('button', { name: '아니오' }));
  resize(false);
  fireEvent.click(within(screen.getByRole('navigation', { name: '주요 메뉴' })).getByRole('button', { name: '캠퍼스' }));
  screen.getByRole('button', { name: 'KAIST 캠퍼스 (관전 모드)' });
  resize(true);
  left().getByRole('heading', { name: 'KAIST 방명록' });
  right().getByRole('tabpanel', { name: '순위' });
  fireEvent.click(screen.getByRole('button', { name: '내 학교로 돌아가기' }));
  screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
  fireEvent.click(screen.getByRole('button', { name: '상점' }));
  fireEvent.click(screen.getByRole('button', { name: '캠퍼스로 돌아가기' }));
  screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
});

test('T2: 폰에서는 서비스 화면이 전체 화면 셸이 되고, 메뉴·도구는 “테스트 도구” 시트로 들어간다 (Q5)', () => {
  asPhone();
  mountApp();
  assertAbsent(screen.queryByRole('navigation', { name: '서비스 흐름' }), '페이지에는 메뉴 없음');
  assertAbsent(screen.queryByRole('region', { name: '[시연] 도구' }));
  const [shell] = shells();
  assert.equal(shell.dataset.phoneShell, 'full');
  assert.ok(shell.classList.contains('phone-shell-full'));
  assert.doesNotMatch(shell.className, /h-\[844px\]|border-4/, '고정 크기·테두리 없음');
  assert.equal(shell.querySelectorAll('[data-phone-status-bar], [data-phone-home-bar]').length, 0, '실기기 상태바와 겹치는 목업 상태바·홈 바 없음');
  assert.ok(document.querySelector('main[data-game-main] [aria-label="캠퍼스 클릭 영역"]'));

  const trigger = screen.getByRole('button', { name: '테스트 도구' });
  trigger.focus();
  fireEvent.click(trigger);
  const sheet = screen.getByRole('dialog', { name: '테스트 도구' });
  assert.ok(within(sheet).getByRole('navigation', { name: '서비스 흐름' }));
  assert.ok(within(sheet).getByRole('navigation', { name: '[시연] 시안 비교·도구' }));
  const tools = within(sheet).getByRole('region', { name: '[시연] 도구' });

  // A demo tool keeps the sheet open; choosing a screen closes it and shows that screen.
  fireEvent.change(within(tools).getByRole('combobox', { name: '[시연] 시간대' }), { target: { value: 'night' } });
  assert.ok(screen.getByRole('dialog', { name: '테스트 도구' }));
  fireEvent.click(within(sheet).getByRole('button', { name: /랭킹 화면/ }));
  assertAbsent(screen.queryByRole('dialog', { name: '테스트 도구' }));
  assertSameNode(document.activeElement, trigger, '닫으면 “테스트 도구” 버튼으로 포커스가 돌아온다');
  assertAbsent(document.querySelector('main[data-game-main]'), '메인 게임에서 랭킹 화면으로 바뀐다');
  assert.equal(shells()[0].dataset.phoneShell, 'full');
});

test('T2: 폰에서도 시안 비교 화면은 390×844 프레임을 유지하고, ?device=framed는 데스크톱 화면을 강제한다', () => {
  asPhone();
  mountApp();
  fireEvent.click(screen.getByRole('button', { name: '테스트 도구' }));
  fireEvent.click(within(screen.getByRole('dialog', { name: '테스트 도구' })).getByRole('button', { name: /2-Phone 채팅 비교/ }));
  assert.ok(shells().length >= 2);
  for (const shell of shells()) assert.equal(shell.dataset.phoneShell, 'framed');
  cleanup();

  dom.reconfigure({ url: 'http://localhost/?device=framed' });
  mountApp();
  assertAbsent(screen.queryByRole('navigation', { name: '서비스 흐름' }));
  assert.equal(shells()[0].dataset.phoneShell, 'framed');
});

test('T2: 폰의 순차 온보딩은 바깥 안내문 없이 전체 화면이고, 목업 메일함 안내는 화면 안에 남는다', () => {
  asPhone();
  mountApp();
  fireEvent.click(screen.getByRole('button', { name: '테스트 도구' }));
  fireEvent.click(within(screen.getByRole('dialog', { name: '테스트 도구' })).getByRole('button', { name: /순차 온보딩/ }));
  assertAbsent(screen.queryByText('순차 온보딩 · 목업 인증 (실제 메일 발송 없음)'));
  const onboarding = screen.getByRole('region', { name: '순차 온보딩' });
  assert.equal(onboarding.dataset.phoneShell, 'full');
});
