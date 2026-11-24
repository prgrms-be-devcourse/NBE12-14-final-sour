import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { assertAbsent, assertSameNode } from './helpers/domAssert';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';

// DOM 통합 테스트용 자산 로더. 이미지 렌더링/레이아웃은 실제 브라우저 검증을 대체하지 않는다.
const assetHooks = registerHooks({
  load(url, context, nextLoad) {
    if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) {
      const pathname = new URL(url).pathname;
      const match = pathname.match(/\/src\/assets\/.+$/);
      const webPath = match ? match[0] : '/src/assets/images/mock_identity_sample.svg';
      return { format: 'module', source: `export default ${JSON.stringify(webPath)};`, shortCircuit: true };
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

const { render, screen, fireEvent, act, cleanup, within } = await import('@testing-library/react');
const { clickTestView, openTestTools, withToolSetting } = await import('./helpers/mockTools');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { default: App } = await import('../src/App');
const { SharedImageProvider, MOCK_IMAGE_DIRECTORY, useImageStore } = await import('../src/hooks/useSharedImages');
const { ImageChangeShop } = await import('../src/components/ImageChangeShop');
const { ShopScreenPhone } = await import('../src/components/ShopScreenPhone');
const { MOCK_SHOP_CONFIG } = await import('../src/data/mockShopConfig');
const { INITIAL_GAME_STATE } = await import('../src/utils/gameModel');
const { CAMPUS_TALL_ASPECT } = await import('../src/data/campusGeometry');
const { SharedImage } = await import('../src/components/SharedImage');
const { createSharedImageStore, canChangeImage } = await import('../src/utils/sharedImages');
const { SCHOOL_LOGO_ASSETS, schoolLogoStatus } = await import('../src/data/schoolLogos');
const marquee = await import('../src/components/MarqueeText');
const { BottomNav, AllianceNavProvider } = await import('../src/components/BottomNav');
const { DialogPanel } = await import('../src/components/DialogPanel');
const { CampusView } = await import('../src/components/CampusView');
const { MOCK_CLICK_RULES } = await import('../src/utils/clickGuard');
/** Default click rules without the interval check, for tests about same-millisecond clicks. */
const SAME_TICK_RULES = { ...MOCK_CLICK_RULES, minIntervalMs: 0 };

type Controller = ReturnType<typeof useMockGame>;
let latest: Controller;
function Probe() {
  latest = useMockGame();
  return React.createElement('output', { 'data-testid': 'state-probe' }, JSON.stringify(latest.state));
}
function mount(withApp = true) {
  render(React.createElement(MockGameProvider, null,
    React.createElement(React.Fragment, null, withApp ? React.createElement(App) : null, React.createElement(Probe))));
}
function clickButton(name: string | RegExp) {
  fireEvent.click(screen.getByRole('button', { name }));
}
function selectAndBuy(name: string) {
  clickButton(new RegExp(name));
  clickButton('구매 및 사용');
}
afterEach(() => cleanup());
after(() => { assetHooks.deregister(); dom.window.close(); });

test('폰 목업 화면은 모두 같은 PhoneShell(390×844, 상태바·홈 바 1개씩)로 렌더링된다 (T1, 2-2)', async () => {
  const { PHONE_FRAME } = await import('../src/components/PhoneShell');
  mount();
  const views = [
    '🎮 메인 게임 화면',
    '🏆 랭킹 화면 (Single Phone)',
    '🤝 연합 신청 (Alliance Apply)',
    '🛡️ 현재 연합 (Current Alliance)',
    '⚔️ 대결 신청 (Battle Apply)',
    '📊 대결 현황 (Battle Status)',
    '🏆 2-Phone 순위 비교 (무한 스크롤)',
    '📜 2-Phone 우리 방명록 (Screen 1 & 2)',
    '📖 2-Phone 방문교 방명록',
    '💬 2-Phone 채팅 비교',
    '🏪 상점 & 강화 (Single Phone)',
    '🗺️ 단일 폰 지도',
    '📱📱 2-Phone 지도 비교',
    '📱 순차 온보딩 (Single Phone)',
    '3단 온보딩 화면',
  ];
  for (const view of views) {
    clickTestView(view);
    const shells = Array.from(document.querySelectorAll('[data-phone-shell]'));
    assert.ok(shells.length > 0, view);
    for (const shell of shells) {
      assert.match(shell.className, new RegExp(`(^| )w-\\[${PHONE_FRAME.width}px\\] max-w-full h-\\[${PHONE_FRAME.height}px\\]`), view);
      assert.equal(shell.querySelectorAll(':scope > [data-phone-status-bar]').length, 1, `${view}: 상태바는 셸이 한 번만 그린다`);
      assert.equal(shell.querySelectorAll(':scope > [data-phone-home-bar]').length, 1, `${view}: 홈 바는 셸이 한 번만 그린다`);
      assert.equal(shell.lastElementChild?.hasAttribute('data-phone-home-bar'), true, `${view}: 홈 바는 맨 아래`);
    }
  }
});

test('메인 게임의 상점 탭은 게임 폰 안에 상태바·홈 바를 또 그리지 않는다 (T1b)', () => {
  mount();
  fireEvent.click(within(document.querySelector('footer nav[aria-label="주요 메뉴"]') as HTMLElement).getByRole('button', { name: '상점' }));
  const shell = document.querySelector('[data-phone-shell]')!;
  assert.ok(shell.querySelector('main[data-game-main]')?.textContent?.includes('캠퍼스 잡화점'), '상점 탭이 열려 있어야 한다');
  // 표시 기준으로 센다: 화면 안에 상태바(LTE)와 홈 바 막대가 하나씩만 있어야 한다.
  assert.equal(shell.textContent?.match(/LTE/g)?.length, 1, '상태바는 게임 폰의 것 하나');
  assert.equal(shell.querySelectorAll('div.w-20.h-1').length, 1, '홈 바는 게임 폰의 것 하나');
});

test('온보딩은 셸의 select-none을 끄고 글자를 선택할 수 있다: 목업 메일함 인증 코드 복사 (T1b)', () => {
  mount();
  clickTestView('📱 순차 온보딩 (Single Phone)');
  const content = screen.getByRole('region', { name: '순차 온보딩' }).querySelector(':scope > :not([data-phone-status-bar]):not([data-phone-home-bar])');
  assert.match(content?.className ?? '', /(^| )select-text( |$)/, 'T1b 이전 온보딩 프레임에는 select-none이 없었다');
});

test('앱을 열면 메뉴를 누르지 않아도 메인 게임 화면이 첫 화면이다 (사용자 지시 2026-11-10)', () => {
  mount();
  assertAbsent(screen.queryByRole('navigation', { name: '서비스 흐름' }));
  const tools = openTestTools();
  assert.match(tools.getByRole('button', { name: '🎮 메인 게임 화면' }).className, /\bplaque-green\b/, 'the main game menu is the selected one');
  fireEvent.click(tools.getByRole('button', { name: /닫기/ }));
  assert.ok(screen.getByRole('button', { name: '캠퍼스' }), 'the main game tabs are on screen');
  assertAbsent(screen.queryByRole('region', { name: '순차 온보딩' }));
});

test('두 열람 화면이 같은 저장소를 구독하고 권한 없는 변경은 반영되지 않는다', () => {
  const store = createSharedImageStore(MOCK_IMAGE_DIRECTORY);
  const target = { type: 'school' as const, id: SCHOOL_ID.hanyang };
  render(React.createElement(SharedImageProvider, { store },
    React.createElement('section', { 'aria-label': '내 화면' }, React.createElement(SharedImage, { target, name: '한양대학교' })),
    React.createElement('section', { 'aria-label': '외부 학교 화면' }, React.createElement(SharedImage, { target, name: '한양대학교' }))));
  assert.equal(screen.getAllByRole('img', { name: '한양대학교 기본 배지' }).length, 2);
  act(() => { assert.equal(store.apply('me', target, 'https://example.org/school.png', 'ok'), true); });
  assert.equal(screen.getAllByRole('img', { name: '한양대학교 이미지' }).length, 2);
  act(() => { assert.equal(store.apply('external-viewer', target, 'https://example.org/other.png', 'denied'), false); });
  assert.equal(screen.getAllByRole('img', { name: '한양대학교 이미지' }).length, 2);
  assert.equal(store.get({ type: 'school', id: SCHOOL_ID.kaist }), undefined);
  fireEvent.error(screen.getAllByRole('img', { name: '한양대학교 이미지' })[0]);
  assert.equal(screen.getAllByRole('img', { name: '한양대학교 기본 배지' }).length, 1);
});

test('학과 및 프로필은 ID로 공유되고 이름 변경과 타 대상 변경에 독립적이다', () => {
  const store = createSharedImageStore(MOCK_IMAGE_DIRECTORY);
  const dept = { type: 'department' as const, id: 'my-department' };
  const profile = { type: 'profile' as const, id: 'me' };
  const views = (name: string) => React.createElement(SharedImageProvider, { store, children: React.createElement(React.Fragment, null,
    React.createElement(SharedImage, { target: dept, name }), React.createElement(SharedImage, { target: dept, name }),
    React.createElement(SharedImage, { target: profile, name: '작성자' }), React.createElement(SharedImage, { target: profile, name: '작성자' })) });
  const rendered = render(views('컴퓨터학과'));
  act(() => { assert.equal(store.apply('me', dept, 'https://example.org/dept.png', 'd'), true); });
  assert.equal(screen.getAllByRole('img', { name: '컴퓨터학과 이미지' }).length, 2);
  act(() => { assert.equal(store.apply('tiger', dept, 'https://example.org/no.png', 'denied'), false); });
  act(() => { assert.equal(store.apply('me', profile, 'https://example.org/person.png', 'p'), true); });
  assert.equal(screen.getAllByRole('img', { name: '작성자 이미지' }).length, 2);
  assert.equal(store.get(dept)?.url, 'https://example.org/dept.png');
  rendered.rerender(views('이름 변경 학과'));
  assert.equal(screen.getAllByRole('img', { name: '이름 변경 학과 이미지' }).length, 2);
  assert.equal(store.get(profile)?.url, 'https://example.org/person.png');
});

test('대체 설정이 가격 표시, 구매 차감, 커피 세기·만료에 모두 반영된다', (t) => {
  t.mock.method(Date, 'now', () => 1000);
  const config = { ...MOCK_SHOP_CONFIG, coffee: { price: 75, currency: 'contribution' as const, durationMs: 10000, multiplier: 3 }, imagePricesKrw: { school: 700, department: 800, profile: 900 } };
  render(React.createElement(MockGameProvider, { config, children: React.createElement(React.Fragment, null,
    React.createElement(Probe), React.createElement(ShopScreenPhone, { onNavigateTab: () => {} })) }));
  assert.ok(screen.getByText('학교 이미지 변경 · 700원'));
  assert.ok(screen.getByText('학과 이미지 변경 · 800원'));
  assert.ok(screen.getByText('내 프로필 이미지 변경 · 900원'));
  assert.ok(screen.getByText('10초간 클릭 세기 3배'));
  clickButton('커피 한 잔 구매 선택');
  assert.ok(screen.getByText(/구매 확인 · 커피 한 잔 \(75점\)/));
  clickButton('구매 및 사용');
  assert.equal(latest.state.contribution, 18485);
  assert.equal(latest.normalPower, 90);
  assert.equal(latest.state.activeBuff?.expiresAt, 11000);
});

test('모의 이미지 결제 성공·실패·취소는 공유 화면과 게임 상태를 보존한다', async (t) => {
  t.mock.method(Date, 'now', () => 1000);
  let store: ReturnType<typeof useImageStore>;
  function StoreProbe() { store = useImageStore(); return null; }
  render(React.createElement(MockGameProvider, null, React.createElement(React.Fragment, null,
    React.createElement(Probe), React.createElement(StoreProbe), React.createElement(ImageChangeShop),
    React.createElement(SharedImage, { target: { type: 'school', id: SCHOOL_ID.hanyang }, name: '한양대학교' }))));
  act(() => { latest.buy('coffee'); });
  const before = latest.state;
  clickButton(/학교 이미지 변경 ·/);
  fireEvent.change(screen.getByLabelText('이미지 URL'), { target: { value: 'https://example.org/crest.png' } });
  await act(async () => { clickButton('결제 실패 시연'); });
  assert.equal(store!.get({ type: 'school', id: SCHOOL_ID.hanyang }), undefined);
  assert.deepEqual(latest.state, before);
  clickButton('취소');
  assert.equal(store!.get({ type: 'school', id: SCHOOL_ID.hanyang }), undefined);
  clickButton(/학교 이미지 변경 ·/);
  fireEvent.change(screen.getByLabelText('이미지 URL'), { target: { value: 'https://example.org/crest.png' } });
  await act(async () => {
    clickButton('모의 결제 성공 및 즉시 적용');
    clickButton('모의 결제 성공 및 즉시 적용');
  });
  assert.equal(store!.get({ type: 'school', id: SCHOOL_ID.hanyang })?.revision, 1);
  assert.equal(screen.getByRole('img', { name: '한양대학교 이미지' }).getAttribute('src'), 'https://example.org/crest.png');
  assert.deepEqual(latest.state, before);
  assert.equal(latest.state.activeBuff?.expiresAt, 31000);
});

test('메인 상점 → 독립 상점 → 교내 개인 순위 → 캠퍼스에서 같은 잔액·강화·버프를 사용한다', () => {
  mount();
  clickTestView('🎮 메인 게임 화면');
  const initial = latest.state;
  clickButton('상점');
  clickButton(/업그레이드/);
  assert.equal(latest.state.contribution, 17060);
  assert.equal(latest.state.clickerId, 4);
  assert.equal(latest.state.schoolScore, initial.schoolScore);
  assert.equal(latest.state.departmentScore, initial.departmentScore);
  clickButton('캠퍼스');
  assert.equal(screen.getByTestId('campus-stats-contribution').textContent, '17,060', '메인 스탯 버튼이 강화 뒤 보유 기여도를 보인다 (T5)');
  clickTestView('🏪 상점 & 강화 (Single Phone)');
  selectAndBuy('커피 한 잔');
  assert.equal(latest.state.contribution, 16940);
  assert.equal(latest.normalPower, 80);
  clickTestView('🏆 랭킹 화면 (Single Phone)');
  clickButton('교내 개인');
  assert.ok(screen.getAllByText('16,940').length > 0);
  clickTestView('🎮 메인 게임 화면');
  assert.equal(screen.getByTestId('campus-stats-contribution').textContent, '16,940');
  assert.equal(latest.state.clickerId, 4);
  assert.equal(latest.state.activeBuff?.itemId, 'coffee');
  assert.doesNotMatch(document.body.textContent ?? '', /EXP|성장 경험치|숙련도/);
});

test('모바일 touchstart 뒤 click이 이어져도 한 번만 득점하고 키보드 입력도 한 번 득점한다', (t) => {
  t.mock.method(Math, 'random', () => 0.9);
  // M5: clicks closer than the mock minimum interval are refused, so the clock moves between inputs.
  let clock = 1000;
  t.mock.method(Date, 'now', () => clock);
  mount();
  clickTestView('🎮 메인 게임 화면');
  const initial = latest.state;
  const campus = screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
  fireEvent.touchStart(campus, { touches: [{ clientX: 100, clientY: 100 }] });
  fireEvent.click(campus, { clientX: 100, clientY: 100 });
  assert.equal(latest.state.contribution - initial.contribution, 30);
  assert.equal(latest.state.schoolScore - initial.schoolScore, 30);
  assert.equal(latest.state.departmentScore - initial.departmentScore, 30);
  clock += 100;
  // A key click counts on press and release (2026-11-08 user instruction); the press alone does not.
  fireEvent.keyDown(campus, { key: 'Enter' });
  assert.equal(latest.state.contribution - initial.contribution, 30);
  fireEvent.keyUp(campus, { key: 'Enter' });
  assert.equal(latest.state.contribution - initial.contribution, 60);
});

test('같은 밀리초 연속 클릭 시 표시 점수와 실제 지급액이 일치한다', (t) => {
  t.mock.method(Math, 'random', () => 0.9);
  t.mock.method(Date, 'now', () => 1000);
  const errors = t.mock.method(console, 'error');
  // Same-millisecond consistency of the reducer and effect keys is the subject here, so the M5 interval check
  // is set to 0; the default rules refuse the second click (see tests/clickGuard.test.ts).
  render(React.createElement(MockGameProvider, { clickRules: SAME_TICK_RULES, children: React.createElement(React.Fragment, null, React.createElement(App), React.createElement(Probe)) }));
  clickTestView('🎮 메인 게임 화면');
  act(() => { latest.buy('coffee'); });
  const balance = latest.state.contribution;
  const schoolScore = latest.state.schoolScore;
  const campus = screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
  act(() => {
    fireEvent.click(campus, { clientX: 100, clientY: 100 });
    fireEvent.click(campus, { clientX: 110, clientY: 110 });
  });
  assert.equal(latest.state.contribution - balance, 60 + 60);
  assert.equal(latest.state.schoolScore - schoolScore, 60 + 60);
  assert.equal(latest.state.activeBuff?.itemId, 'coffee');
  assert.equal(screen.getAllByText('+60').length, 2);
  assert.equal(errors.mock.callCount(), 0, '같은 밀리초 연속 클릭에서도 React key가 중복되면 안 된다.');
});

test('비판매 1회 버프의 같은 밀리초 연속 클릭도 최신 상태와 실제 표시가 일치한다', (t) => {
  t.mock.method(Math, 'random', () => 0.9);
  t.mock.method(Date, 'now', () => 1000);
  const errors = t.mock.method(console, 'error');
  const fixture = { id: 'single-use-fixture', name: 'test', description: '', cost: 0, iconType: 'pass' as const, multiplier: 1, bonus: 50, durationMs: 300000, singleUse: true };
  render(React.createElement(MockGameProvider, { clickRules: SAME_TICK_RULES, initialState: { ...INITIAL_GAME_STATE, activeBuff: { itemId: fixture.id, expiresAt: 301000 } }, extraBuffs: [fixture], children: React.createElement(React.Fragment, null, React.createElement(App), React.createElement(Probe)) }));
  clickTestView('🎮 메인 게임 화면');
  const balance = latest.state.contribution;
  const schoolScore = latest.state.schoolScore;
  const campus = screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
  act(() => { fireEvent.click(campus, { clientX: 100, clientY: 100 }); fireEvent.click(campus, { clientX: 110, clientY: 110 }); });
  assert.equal(latest.state.contribution - balance, 110);
  assert.equal(latest.state.schoolScore - schoolScore, 110);
  assert.equal(latest.state.activeBuff, null);
  assert.ok(screen.getByText('+80'));
  assert.ok(screen.getByText('+30'));
  assert.equal(errors.mock.callCount(), 0);
  assert.equal(latest.buy(fixture.id), false);
});

test('한 프레임의 연속 구매·강화는 공유 컨트롤러의 최신 잔액으로 판정한다', () => {
  mount(false);
  act(() => {
    for (let i = 0; i < 200; i++) latest.buy('coffee');
    latest.upgrade();
  });
  assert.equal(latest.state.contribution, 80);
  assert.equal(latest.state.clickerId, 3);
  assert.equal(latest.normalPower, 60);
});

test('공유 타이머는 교체된 버프를 이전 만료 시각에 지우지 않고 새 만료 시각에 종료한다', (t) => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval', 'setTimeout'], now: 1000 });
  mount(false);
  act(() => { latest.buy('coffee'); });
  assert.equal(latest.remainingSeconds, 30);
  act(() => { t.mock.timers.tick(20000); });
  act(() => { latest.buy('coffee'); });
  assert.equal(latest.remainingSeconds, 30);
  act(() => { t.mock.timers.tick(10000); });
  assert.equal(latest.activeBuff?.id, 'coffee');
  assert.equal(latest.normalPower, 60);
  act(() => { t.mock.timers.tick(20000); });
  assert.equal(latest.state.activeBuff, null);
  assert.equal(latest.remainingSeconds, 0);
  assert.equal(latest.normalPower, 30);
  cleanup();
});

test('성장 시연 선택을 학교 점수·HUD·메인 캠퍼스 이미지에 함께 반영한다', () => {
  mount();
  const balance = latest.state.contribution;
  clickTestView('🏛️ 10단계 캠퍼스 성장 시트 (5x2)');
  fireEvent.click(screen.getByText('L.1', { exact: true }));
  clickButton('성장 시연: 학교 점수를 기준값으로 설정');
  assert.equal(latest.state.schoolScore, 0);
  assert.equal(latest.state.contribution, balance);
  clickButton('인게임으로');
  assert.ok(screen.getByRole('img', { name: '캠퍼스 Lv.1 풀밭 부지와 텐트' }));
  assert.ok(screen.getByRole('button', { name: 'Lv.1' }));
  assert.ok(screen.getByRole('button', { name: /^상세 스탯 보기/ }));
  clickButton('Lv.1');
  assert.ok(screen.getByRole('group', { name: '캠퍼스 Lv.1 · 현재' }));
  assert.equal(screen.getAllByRole('group', { name: /미해금$/ }).length, 9);
});

test('메인 화면은 헤더·main·푸터로 나뉘고 연합·대결 진입은 하단 내비의 캠퍼스와 지도 사이에 있다 (T3, 2-1-2)', () => {
  mount();
  clickTestView('🎮 메인 게임 화면');
  const main = document.querySelector('main[data-game-main]');
  const footer = main?.parentElement?.querySelector(':scope > footer');
  const hud = main?.parentElement?.querySelector(':scope > div > header');
  assert.ok(main && footer && hud, '헤더·main·푸터가 같은 화면 컨테이너의 형제여야 한다');
  // 긴 학교명을 위해 헤더에는 연합·대결 버튼을 두지 않는다.
  assert.doesNotMatch(hud.textContent ?? '', /연합·대결/);
  // 하단 내비는 푸터 안에 있고 순서는 상점·순위·캠퍼스·연합·대결·지도다(사용자 지시 2-1-2).
  const nav = footer.querySelector('nav[aria-label="주요 메뉴"]');
  assert.ok(nav, '하단 내비는 스크롤되는 main이 아닌 푸터에 있어야 한다');
  const labels = Array.from(nav.querySelectorAll('button')).map((b) => b.textContent?.trim());
  assert.deepEqual(labels, ['상점', '순위', '캠퍼스', '연합·대결', '지도']);
  assert.ok(main.querySelector('[data-testid="campus-stats-button"]'), '탭 콘텐츠(캠퍼스와 스탯 버튼)는 main 안에 렌더링된다');
  clickButton('연합·대결');
  assert.ok(screen.getByText('소속 연합 없음'));
});

test('MarqueeText는 칸보다 길 때만 페이드·왕복 설정을 켜고 전체 이름을 title로 제공한다', (t) => {
  const { MarqueeText, getMarqueeDurationSeconds, MARQUEE_FADE_PX, MARQUEE_MIN_DURATION_S, MARQUEE_SPEED_PX_PER_S } = marquee;
  assert.equal(getMarqueeDurationSeconds(0), 0);
  assert.equal(getMarqueeDurationSeconds(5), MARQUEE_MIN_DURATION_S, '조금만 넘쳐도 최소 시간을 지켜 끝에서 멈춘다');
  assert.ok(getMarqueeDurationSeconds(400) > 400 / MARQUEE_SPEED_PX_PER_S, '먼 거리는 멈춤 비율만큼 더 길다');

  // jsdom은 레이아웃이 없으므로 폭을 주입한다: 칸 100px, 글자 폭은 내용 길이 × 10px.
  const proto = dom.window.HTMLElement.prototype;
  t.mock.getter(proto, 'clientWidth', function (this: HTMLElement) { return this.classList.contains('marquee-box') ? 100 : 0; });
  t.mock.getter(proto, 'offsetWidth', function (this: HTMLElement) { return (this.textContent?.length ?? 0) * 10; });

  const { rerender } = render(React.createElement(MarqueeText, { text: '짧은이름' }));
  const box = document.querySelector('.marquee-box') as HTMLElement;
  assert.equal(box.dataset.overflowing, 'false');
  assert.equal(box.getAttribute('title'), null);

  const longName = '한국방송통신대학교 프라임칼리지';
  rerender(React.createElement(MarqueeText, { text: longName }));
  assert.equal(box.dataset.overflowing, 'true');
  assert.equal(box.getAttribute('title'), longName);
  const distance = longName.length * 10 + MARQUEE_FADE_PX * 2 - 100;
  assert.equal(box.style.getPropertyValue('--marquee-distance'), `-${distance}px`);
  assert.equal(box.style.getPropertyValue('--marquee-duration'), `${getMarqueeDurationSeconds(distance).toFixed(2)}s`);
});

test('헤더 학교명은 시연 선택값을 따르고 지역 줄 없이 한 줄로 표시된다', () => {
  mount();
  clickTestView('🎮 메인 게임 화면');
  withToolSetting('[시연] 학교명', (control) => fireEvent.change(control, { target: { value: 'long' } }));
  const hud = document.querySelector('main[data-game-main]')!.parentElement!.querySelector(':scope > div > header')!;
  assert.equal(hud.querySelector('h1')?.textContent, '한국방송통신대학교 프라임칼리지 서울캠퍼스');
  assert.doesNotMatch(hud.textContent ?? '', /서울 · 수도권/);
});

test('연합·대결 버튼은 모든 폰 화면 하단 내비에 같고, 알림이 있을 때만 배지를 표시한다', () => {
  mount();
  for (const view of ['🏪 상점 & 강화 (Single Phone)', '🏆 랭킹 화면 (Single Phone)', '📱📱 2-Phone 지도 비교']) {
    clickTestView(view);
    const navs = Array.from(document.querySelectorAll('nav[aria-label="주요 메뉴"]'));
    assert.ok(navs.length > 0, view);
    for (const nav of navs) {
      assert.deepEqual(Array.from(nav.querySelectorAll('button')).map((b) => b.textContent?.trim()), ['상점', '순위', '캠퍼스', '연합·대결', '지도'], view);
      assertAbsent(nav.querySelector('[data-alert]'), '초기 목업 상태에는 확인할 연합 소식이 없다');
    }
  }
  // 비교 화면의 어느 폰에서 눌러도 같은 현재 연합 화면으로 이동한다.
  fireEvent.click(screen.getAllByRole('button', { name: '연합·대결' })[0]);
  assert.ok(screen.getByText('소속 연합 없음'));
  cleanup();

  // Provider 없이 단독 렌더링하면 기존 4칸, 알림이 있으면 배지 표시.
  const nav = () => React.createElement(BottomNav, { activeTab: 'campus', onChangeTab: () => {}, hasShopUpgrade: false, hasRankChange: false });
  render(nav());
  assert.deepEqual(Array.from(document.querySelectorAll('nav button')).map((b) => b.textContent?.trim()), ['상점', '순위', '캠퍼스', '지도']);
  cleanup();
  render(React.createElement(AllianceNavProvider, { value: { open: () => {}, hasAlert: true } }, nav()));
  const alertButton = screen.getByRole('button', { name: '연합·대결' });
  assert.equal(alertButton.dataset.alert, 'true');
  assert.match(alertButton.getAttribute('title') ?? '', /확인할 소식 있음/);
});

test('하단 내비 5버튼은 아이콘 24px·라벨 12px로 같은 모양이고 가운데 강조가 없다 (T3, 2-1-1, Q7)', () => {
  render(React.createElement(AllianceNavProvider, { value: { open: () => {}, hasAlert: false } },
    React.createElement(BottomNav, { activeTab: 'campus', onChangeTab: () => {}, hasShopUpgrade: false, hasRankChange: false })));
  const buttons = Array.from(document.querySelectorAll('nav button'));
  assert.equal(buttons.length, 5);
  const layout = (b: Element) => b.className.replace(/\bplaque-(wood|yellow)\b/, '').trim();
  for (const button of buttons) {
    const name = button.textContent?.trim();
    assert.match(button.querySelector('svg')?.getAttribute('class') ?? '', /(^| )w-6 h-6( |$)/, `${name}: 아이콘 24px`);
    assert.match(button.querySelector('span:last-child')?.className ?? '', /(^| )text-\[12px\]( |$)/, `${name}: 라벨 12px`);
    assert.equal(layout(button), layout(buttons[0]), `${name}: 활성 색 외에는 모든 버튼이 같은 모양`);
  }
});

test('연합·대결 4개 화면은 하단 내비를 푸터에 두고 연합·대결을 현재 화면으로 표시하며, 다른 탭으로 이동한다 (T4, 계획서 §7)', () => {
  mount();
  const views = ['🤝 연합 신청 (Alliance Apply)', '🛡️ 현재 연합 (Current Alliance)', '⚔️ 대결 신청 (Battle Apply)', '📊 대결 현황 (Battle Status)'];
  const destinations: [string, () => unknown][] = [
    ['상점', () => screen.queryByText('캠퍼스 잡화점 & 강화소')],
    ['순위', () => screen.queryByRole('heading', { name: '전국 랭킹 대항전' })],
    ['지도', () => screen.queryByTestId('map-area')],
    ['연합·대결', () => screen.queryByText('소속 연합 없음')],
    ['캠퍼스', () => document.querySelector('main[data-game-main]')],
  ];
  for (const view of views) {
    for (const [tab, isShown] of destinations) {
      clickTestView(view);
      const shell = document.querySelector('[data-phone-shell]')!;
      const navs = shell.querySelectorAll('nav[aria-label="주요 메뉴"]');
      assert.equal(navs.length, 1, `${view}: 하단 내비 1개`);
      const nav = navs[0] as HTMLElement;
      // 스크롤 본문 밖의 고정 푸터: 셸의 직계 자식이고 홈 바 바로 위에 있다.
      const footer = nav.parentElement!;
      assertSameNode(footer.parentElement, shell, `${view}: 내비 푸터는 셸의 직계 자식`);
      assert.equal(footer.nextElementSibling?.hasAttribute('data-phone-home-bar'), true, `${view}: 내비 푸터는 홈 바 바로 위`);
      assert.deepEqual(Array.from(nav.querySelectorAll('button')).map((b) => b.textContent?.trim()), ['상점', '순위', '캠퍼스', '연합·대결', '지도'], view);
      assert.deepEqual(Array.from(nav.querySelectorAll('[aria-current="page"]')).map((b) => b.textContent?.trim()), ['연합·대결'], `${view}: 현재 화면 표시`);
      fireEvent.click(within(nav).getByRole('button', { name: tab }));
      assert.ok(isShown() != null, `${view} → ${tab}`);
    }
  }
  // 메인으로 돌아오면 캠퍼스가 현재 화면이다.
  const mainNav = document.querySelector('footer nav[aria-label="주요 메뉴"]')!;
  assert.deepEqual(Array.from(mainNav.querySelectorAll('[aria-current="page"]')).map((b) => b.textContent?.trim()), ['캠퍼스']);
  cleanup();

  // 연합·대결 화면 안에서는 알림 배지를 띄우지 않는다(다른 탭과 같은 규칙).
  render(React.createElement(AllianceNavProvider, { value: { open: () => {}, hasAlert: true } },
    React.createElement(BottomNav, { activeTab: 'alliance', onChangeTab: () => {}, hasShopUpgrade: false, hasRankChange: false })));
  const allianceButton = screen.getByRole('button', { name: '연합·대결' });
  assert.equal(allianceButton.getAttribute('aria-current'), 'page');
  assert.match(allianceButton.className, /(^| )plaque-yellow( |$)/);
  assertAbsent(allianceButton.getAttribute('data-alert'), '현재 화면에서는 알림 배지를 숨긴다');
});

test('D2: 메인 캠퍼스 칸은 남는 높이를 채우되 220px 이상, 검사한 최대 비율 이하이고, 스탯 시트의 패널은 기여도·클리커 두 상자에 모든 정보를 담는다 (T2·T5)', () => {
  mount();
  clickTestView('🎮 메인 게임 화면');
  // T2 (계획서 §5): 고정 375px 대신 가변 높이. 실제 픽셀(7개 크기, 220px 이상)은 e2e가 잰다.
  const campus = screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
  const classes = campus.className.split(/\s+/);
  assert.ok(classes.includes('flex-1') && classes.includes('min-h-[220px]'), '남는 높이를 채우고 최소 220px');
  assert.doesNotMatch(campus.className, /(^|\s)h-\[/, '고정 높이 없음');
  assert.doesNotMatch(campus.className, /\b(sm|md|lg):h-/, '창 폭 분기는 프레임 안에서 main 넘침을 만들었다');
  // 최대 높이는 장면을 검사한 가장 긴 비율(CAMPUS_TALL_ASPECT)과 같아야 한다. 칸 폭은 @container인 main의 폭이다.
  const cap = classes.find((c) => c.startsWith('max-h-['));
  const ratio = cap?.match(/\*(\d+)\/(\d+)/);
  assert.ok(ratio, `최대 높이가 폭 비율로 정해진다: ${cap}`);
  assert.equal(Number(ratio[1]) / Number(ratio[2]), CAMPUS_TALL_ASPECT);
  assert.ok(campus.closest('main[data-game-main]')?.classList.contains('@container'), 'cqw 기준인 main이 @container');
  // 장면 svg가 흐름에 있으면 viewBox 비율이 최소 높이가 되어 칸이 줄지 않는다(360×584에서 main 넘침).
  assert.ok(campus.querySelector('svg')?.classList.contains('absolute'), '장면은 흐름 밖');
  cleanup();

  render(React.createElement(MockGameProvider, null, React.createElement(DialogPanel, { myContribution: 18560, clickerLevel: 3 })));
  const boxes = document.querySelectorAll('.rpg-box');
  assert.equal(boxes.length, 2, '기여도 상자와 클리커 상자');
  const text = Array.from(boxes).map((box) => box.textContent).join(' ');
  for (const part of ['🖋️ 클리커 Lv.3', '보유 기여도', '18,560', '강화 비용', '기본 세기', '치명타 2배', '내 누적 기여 점수 · 구매/강화 시 차감']) assert.ok(text.includes(part), part);
  assert.ok(screen.getByRole('progressbar', { name: '다음 강화 비용 대비 보유 기여도' }));
  assert.equal(screen.getByRole('status').textContent, '활성 버프 없음');
});

test('채팅·방명록 비교 화면에도 공유 클리커·기여도·버프를 반영한다', (t) => {
  t.mock.method(Math, 'random', () => 0.9);
  mount();
  act(() => { latest.upgrade(); latest.buy('coffee'); });
  const balance = latest.state.contribution;
  clickTestView('💬 2-Phone 채팅 비교');
  const shownContributions = () => screen.getAllByTestId('campus-stats-contribution').map((el) => el.textContent);
  assert.deepEqual(new Set(shownContributions()), new Set([balance.toLocaleString('ko-KR')]), '비교 화면 스탯 버튼은 공유 보유 기여도를 보인다 (T5)');
  const campuses = screen.getAllByRole('button', { name: '캠퍼스 클릭 영역' });
  assert.equal(campuses.length, 2, '비교 화면은 클릭 가능한 1번과 시안용 2번 폰을 모두 유지한다.');
  fireEvent.click(campuses[0]);
  assert.equal(latest.state.contribution, balance + 80);
  fireEvent.click(campuses[1]);
  assert.equal(latest.state.contribution, balance + 80, '시안용 폰은 실제 점수를 중복 지급하지 않는다.');
  clickTestView('📜 2-Phone 우리 방명록 (Screen 1 & 2)');
  assert.deepEqual(new Set(shownContributions()), new Set([(balance + 80).toLocaleString('ko-KR')]));
  assert.doesNotMatch(document.body.textContent ?? '', /EXP|성장 경험치|숙련도/);
});

test('상점은 판매 중단 상품을 숨기고 잔액 부족 시 구매·강화를 비활성화한다', () => {
  mount();
  clickTestView('🏪 상점 & 강화 (Single Phone)');
  assertAbsent(screen.queryByText('황금 클리커 조각'));
  assertAbsent(screen.queryByText('응원 깃발'));
  assertAbsent(screen.queryByText('도서관 열람권'));
  act(() => { for (let i = 0; i < 200; i++) latest.buy('coffee'); });
  clickButton('커피 한 잔 구매 선택');
  assert.equal(screen.getByRole<HTMLButtonElement>('button', { name: '구매 및 사용' }).disabled, true);
  assert.equal(screen.getByRole<HTMLButtonElement>('button', { name: /업그레이드/ }).disabled, true);
  assert.ok(screen.getByText('기여도가 부족합니다.'));
  assert.equal(latest.state.contribution, 80);
  assert.equal(latest.state.clickerId, 3);
});

test('상점 변경 대상은 고정 리터럴이 아닌 현재 사용자 신원/소속 ID에서 파생된다', () => {
  let controller: Controller;
  function IdentityProbe() { controller = useMockGame(); return null; }
  render(React.createElement(MockGameProvider, null, React.createElement(React.Fragment, null,
    React.createElement(IdentityProbe), React.createElement(ImageChangeShop))));

  // 기본 상태: departmentId = 'my-department', department = '컴퓨터소프트웨어학부'
  assert.equal(controller!.player.departmentId, 'my-department');
  assert.equal(canChangeImage(controller!.imageStore.getDirectory(), controller!.player.userId, { type: 'department', id: 'my-department' }), true);
  assert.equal(canChangeImage(controller!.imageStore.getDirectory(), controller!.player.userId, { type: 'department', id: 'business' }), false);

  clickButton(/학과 이미지 변경 ·/);
  assert.ok(screen.getByText(/컴퓨터소프트웨어학부 · 500원/));
  clickButton('취소');

  // 소속 학과 변경: '경영학과'로 온보딩/소속 변경
  act(() => { controller!.setPlayer({ department: '경영학과' }); });
  assert.equal(controller!.player.departmentId, 'business');
  assert.equal(canChangeImage(controller!.imageStore.getDirectory(), controller!.player.userId, { type: 'department', id: 'business' }), true);
  assert.equal(canChangeImage(controller!.imageStore.getDirectory(), controller!.player.userId, { type: 'department', id: 'my-department' }), false);

  // MOCK_IMAGE_DIRECTORY는 모듈 수준에서 절대 변경되지 않는 불변 시드임을 입증
  assert.equal(canChangeImage(MOCK_IMAGE_DIRECTORY, 'me', { type: 'department', id: 'my-department' }), true);
  assert.equal(canChangeImage(MOCK_IMAGE_DIRECTORY, 'me', { type: 'department', id: 'business' }), false);

  clickButton(/학과 이미지 변경 ·/);
  assert.ok(screen.getByText(/경영학과 · 500원/));
  clickButton('취소');
});

test('커스텀 이미지 로드 실패 시 등록된 기본 로고로 복구하고 기본 로고 실패 시 접근성 텍스트로 복구한다', () => {
  const store = createSharedImageStore(MOCK_IMAGE_DIRECTORY);
  const target = { type: 'school' as const, id: SCHOOL_ID.hanyang };
  render(React.createElement(SharedImageProvider, { store },
    React.createElement(SharedImage, { target, name: '한양대학교', defaultUrl: 'https://example.org/default-crest.png' })));

  // 초기 상태: 커스텀 이미지가 없으므로 기본 로고 표시
  const defaultImg = screen.getByRole('img', { name: '한양대학교 기본 로고' });
  assert.equal(defaultImg.getAttribute('src'), 'https://example.org/default-crest.png');

  // 커스텀 이미지 적용
  act(() => { store.apply('me', target, 'https://example.org/custom.png', 'r2-req'); });
  const customImg = screen.getByRole('img', { name: '한양대학교 이미지' });
  assert.equal(customImg.getAttribute('src'), 'https://example.org/custom.png');

  // 커스텀 이미지 실패 -> 기본 로고로 fallback
  fireEvent.error(customImg);
  const fallbackToDefault = screen.getByRole('img', { name: '한양대학교 기본 로고' });
  assert.equal(fallbackToDefault.getAttribute('src'), 'https://example.org/default-crest.png');

  // 기본 로고도 실패 -> 접근성 텍스트 fallback
  fireEvent.error(fallbackToDefault);
  assert.ok(screen.getByRole('img', { name: '한양대학교 기본 배지' }));
});

test('학교 기본 로고는 지도와 같은 SCHOOL_LOGO_ASSETS 한 곳에서 온다 (M1-D5)', (t) => {
  // The map shows a school's logo status from SCHOOL_LOGO_ASSETS; SharedImage must read the same map
  // instead of a second list, so registering one asset updates both.
  const logos = SCHOOL_LOGO_ASSETS as Record<SchoolId, string>;
  logos[SCHOOL_ID.kaist] = 'https://example.org/kaist-crest.png';
  t.after(() => { delete logos[SCHOOL_ID.kaist]; });
  const store = createSharedImageStore(MOCK_IMAGE_DIRECTORY);
  render(React.createElement(SharedImageProvider, { store },
    React.createElement(SharedImage, { target: { type: 'school', id: SCHOOL_ID.kaist }, name: 'KAIST' })));
  assert.equal(schoolLogoStatus(SCHOOL_ID.kaist), 'asset');
  assert.equal(screen.getByRole('img', { name: 'KAIST 기본 로고' }).getAttribute('src'), 'https://example.org/kaist-crest.png');
});

test('세션 요청 ID로 중복 완료를 차단하고 취소 시 URL 초안을 초기화한다', async () => {
  let store: ReturnType<typeof useImageStore>;
  function StoreProbe() { store = useImageStore(); return null; }
  render(React.createElement(MockGameProvider, null, React.createElement(React.Fragment, null,
    React.createElement(StoreProbe), React.createElement(ImageChangeShop))));

  // 취소 시 URL 초기화 검증
  clickButton(/학교 이미지 변경 ·/);
  const urlInput = screen.getByLabelText('이미지 URL') as HTMLInputElement;
  fireEvent.change(urlInput, { target: { value: 'https://example.org/first-draft.png' } });
  assert.equal(urlInput.value, 'https://example.org/first-draft.png');
  clickButton('취소');
  assertAbsent(screen.queryByLabelText('이미지 URL'));

  // 다시 열면 URL 초안이 비어 있음
  clickButton(/학교 이미지 변경 ·/);
  const reopenedDialog = screen.getByRole('dialog', { name: /학교 이미지 변경 확인/ });
  const completedRequestId = reopenedDialog.getAttribute('data-session-request-id');
  assert.ok(completedRequestId);
  const reopenedInput = screen.getByLabelText('이미지 URL') as HTMLInputElement;
  assert.equal(reopenedInput.value, '');

  // 완료 성공
  fireEvent.change(reopenedInput, { target: { value: 'https://example.org/school.png' } });
  let notifications = 0;
  store!.subscribe(() => { notifications++; });

  await act(async () => { clickButton('모의 결제 성공 및 즉시 적용'); });
  assert.equal(store!.get({ type: 'school', id: SCHOOL_ID.hanyang })?.revision, 1);
  assert.equal(notifications, 1);

  // 동일 세션 완료 중복 시도 -> 거부되고 리비전과 알림이 증가하지 않음
  const duplicateApplied = store!.apply('me', { type: 'school', id: SCHOOL_ID.hanyang }, 'https://example.org/school.png', completedRequestId!);
  assert.equal(duplicateApplied, false);
  assert.equal(store!.get({ type: 'school', id: SCHOOL_ID.hanyang })?.revision, 1);
  assert.equal(notifications, 1);
});

test('2개 이상의 서로 다른 사용자·열람 화면 컨텍스트에서 전파·권한 거부·fallback을 검증한다', () => {
  const store = createSharedImageStore(MOCK_IMAGE_DIRECTORY);

  function MultiUserViews({ myDeptName = '컴퓨터소프트웨어학부', myNick = '열정학번사자' }: { myDeptName?: string; myNick?: string }) {
    return React.createElement(SharedImageProvider, { store },
      React.createElement('div', { 'data-testid': 'viewer-me' },
        React.createElement(SharedImage, { target: { type: 'school', id: SCHOOL_ID.hanyang }, name: '한양대학교' }),
        React.createElement(SharedImage, { target: { type: 'department', id: 'my-department' }, name: myDeptName }),
        React.createElement(SharedImage, { target: { type: 'profile', id: 'me' }, name: myNick }),
      ),
      React.createElement('div', { 'data-testid': 'viewer-external' },
        React.createElement(SharedImage, { target: { type: 'school', id: SCHOOL_ID.hanyang }, name: '한양대학교' }),
        React.createElement(SharedImage, { target: { type: 'department', id: 'my-department' }, name: myDeptName }),
        React.createElement(SharedImage, { target: { type: 'profile', id: 'me' }, name: myNick }),
        React.createElement(SharedImage, { target: { type: 'school', id: SCHOOL_ID.kaist }, name: 'KAIST' }),
        React.createElement(SharedImage, { target: { type: 'department', id: 'kaist-computing' }, name: '전산학부' }),
        React.createElement(SharedImage, { target: { type: 'profile', id: 'external-viewer' }, name: '외부인' }),
      ),
    );
  }

  const { rerender } = render(React.createElement(MultiUserViews));

  // 1. 학교 이미지 변경: user 'me' -> hanyang
  act(() => { assert.equal(store.apply('me', { type: 'school', id: SCHOOL_ID.hanyang }, 'https://example.org/hanyang.png', 'm-req-1'), true); });
  assert.equal(screen.getAllByRole('img', { name: '한양대학교 이미지' }).length, 2);
  assert.equal(store.get({ type: 'school', id: SCHOOL_ID.kaist }), undefined);

  // 2. 학과 이미지 변경: user 'me' -> my-department
  act(() => { assert.equal(store.apply('me', { type: 'department', id: 'my-department' }, 'https://example.org/my-dept.png', 'm-req-2'), true); });
  assert.equal(screen.getAllByRole('img', { name: '컴퓨터소프트웨어학부 이미지' }).length, 2);
  assert.equal(store.get({ type: 'department', id: 'kaist-computing' }), undefined);

  // 3. 프로필 이미지 변경: user 'me' -> me
  act(() => { assert.equal(store.apply('me', { type: 'profile', id: 'me' }, 'https://example.org/profile-me.png', 'm-req-3'), true); });
  assert.equal(screen.getAllByRole('img', { name: '열정학번사자 이미지' }).length, 2);
  assert.equal(store.get({ type: 'profile', id: 'external-viewer' }), undefined);

  // 4. 실패 / 취소는 아무것도 전파하지 않음
  act(() => {
    assert.equal(store.apply('me', { type: 'school', id: SCHOOL_ID.hanyang }, 'invalid-url', 'fail-1'), false);
    assert.equal(store.apply('me', { type: 'school', id: SCHOOL_ID.hanyang }, 'javascript:alert(1)', 'fail-2'), false);
  });
  assert.equal(store.get({ type: 'school', id: SCHOOL_ID.hanyang })?.url, 'https://example.org/hanyang.png');

  // 5. 권한 거절:
  assert.equal(store.apply('me', { type: 'department', id: 'business' }, 'https://example.org/biz.png', 'biz-req'), false);
  assert.equal(store.apply('me', { type: 'school', id: SCHOOL_ID.kaist }, 'https://example.org/k.png', 'k-req'), false);
  assert.equal(store.apply('me', { type: 'profile', id: 'external-viewer' }, 'https://example.org/ext.png', 'ext-req'), false);
  assert.equal(store.apply('external-viewer', { type: 'school', id: SCHOOL_ID.hanyang }, 'https://example.org/hack.png', 'hack-req'), false);
  assert.equal(store.apply('external-viewer', { type: 'department', id: 'my-department' }, 'https://example.org/hack2.png', 'hack2-req'), false);

  // 6. 이름 변경 시에도 ID 기반 이미지 보존
  rerender(React.createElement(MultiUserViews, { myDeptName: '컴퓨터AI학부', myNick: 'AI사자' }));
  assert.equal(screen.getAllByRole('img', { name: '컴퓨터AI학부 이미지' }).length, 2);
  assert.equal(screen.getAllByRole('img', { name: 'AI사자 이미지' }).length, 2);
});

test('이미지 구매의 성공·실패·취소는 모든 이미지 유형에서 게임 상태와 커피 만료를 불변으로 유지한다', async (t) => {
  t.mock.method(Date, 'now', () => 1000);
  render(React.createElement(MockGameProvider, null, React.createElement(React.Fragment, null,
    React.createElement(Probe), React.createElement(ImageChangeShop))));

  act(() => { latest.buy('coffee'); });
  const fixedClockState = { ...latest.state };
  assert.equal(fixedClockState.activeBuff?.itemId, 'coffee');
  assert.equal(fixedClockState.activeBuff?.expiresAt, 31000);

  const buttonLabels = [/학교 이미지 변경 ·/, /학과 이미지 변경 ·/, /내 프로필 이미지 변경 ·/];

  for (const label of buttonLabels) {
    clickButton(label);
    clickButton('취소');
    assert.deepEqual(latest.state, fixedClockState);

    clickButton(label);
    fireEvent.change(screen.getByLabelText('이미지 URL'), { target: { value: 'https://example.org/test.png' } });
    await act(async () => { clickButton('결제 실패 시연'); });
    assert.deepEqual(latest.state, fixedClockState);
    clickButton('취소');

    clickButton(label);
    fireEvent.change(screen.getByLabelText('이미지 URL'), { target: { value: 'https://example.org/success.png' } });
    await act(async () => { clickButton('모의 결제 성공 및 즉시 적용'); });
    assert.deepEqual(latest.state, fixedClockState);
  }
});

test('커피 버프 활성 중 이미지 구매 후 시간이 지나면 커피가 정상 만료된다', async (t) => {
  t.mock.timers.enable({ apis: ['Date', 'setInterval', 'setTimeout'], now: 1000 });
  render(React.createElement(MockGameProvider, null, React.createElement(React.Fragment, null,
    React.createElement(Probe), React.createElement(ImageChangeShop),
    React.createElement(SharedImage, { target: { type: 'school', id: SCHOOL_ID.hanyang }, name: '한양대학교' }))));

  act(() => { latest.buy('coffee'); });
  assert.equal(latest.state.activeBuff?.expiresAt, 31000);
  assert.equal(latest.remainingSeconds, 30);
  assert.equal(latest.normalPower, 60);

  act(() => { t.mock.timers.tick(9000); });
  assert.equal(latest.remainingSeconds, 21);

  clickButton(/학교 이미지 변경 ·/);
  fireEvent.change(screen.getByLabelText('이미지 URL'), { target: { value: 'https://example.org/hanyang-banner.png' } });
  await act(async () => { clickButton('모의 결제 성공 및 즉시 적용'); });

  assert.equal(screen.getByRole('img', { name: '한양대학교 이미지' }).getAttribute('src'), 'https://example.org/hanyang-banner.png');
  assert.equal(latest.state.activeBuff?.itemId, 'coffee');
  assert.equal(latest.state.activeBuff?.expiresAt, 31000);
  assert.equal(latest.normalPower, 60);

  act(() => { t.mock.timers.tick(22000); });
  assert.equal(latest.state.activeBuff, null);
  assert.equal(latest.remainingSeconds, 0);
  assert.equal(latest.normalPower, 30);
  cleanup();
});

test('R3-1 (a): 두 프로바이더를 나란히 마운트 시 한쪽의 학과 변경이 다른 쪽 소유권에 영향을 주지 않는다', () => {
  let controllerA: Controller;
  let controllerB: Controller;
  function ProbeA() { controllerA = useMockGame(); return null; }
  function ProbeB() { controllerB = useMockGame(); return null; }

  render(React.createElement('div', null,
    React.createElement(MockGameProvider, null, React.createElement(ProbeA)),
    React.createElement(MockGameProvider, null, React.createElement(ProbeB)),
  ));

  // 초기 상태: 둘 다 'my-department'
  assert.equal(canChangeImage(controllerA!.imageStore.getDirectory(), 'me', { type: 'department', id: 'my-department' }), true);
  assert.equal(canChangeImage(controllerB!.imageStore.getDirectory(), 'me', { type: 'department', id: 'my-department' }), true);

  // A 프로바이더의 학과만 '경영학과'(business)로 변경
  act(() => { controllerA!.setPlayer({ department: '경영학과' }); });

  // A의 저장소는 'business'로 변경됨
  assert.equal(canChangeImage(controllerA!.imageStore.getDirectory(), 'me', { type: 'department', id: 'business' }), true);
  assert.equal(canChangeImage(controllerA!.imageStore.getDirectory(), 'me', { type: 'department', id: 'my-department' }), false);

  // B의 저장소는 독립적으로 기존 'my-department' 유지
  assert.equal(canChangeImage(controllerB!.imageStore.getDirectory(), 'me', { type: 'department', id: 'my-department' }), true);
  assert.equal(canChangeImage(controllerB!.imageStore.getDirectory(), 'me', { type: 'department', id: 'business' }), false);

  // B 저장소에서 'my-department' 이미지 변경 시도 -> 성공
  act(() => {
    assert.equal(controllerB!.imageStore.apply('me', { type: 'department', id: 'my-department' }, 'https://example.org/b-dept.png', 'req-b-1'), true);
  });
  // A 저장소에서는 'my-department' 이미지 변경 시도 -> 거절 (이미 경영학과로 변경됨)
  act(() => {
    assert.equal(controllerA!.imageStore.apply('me', { type: 'department', id: 'my-department' }, 'https://example.org/a-dept.png', 'req-a-1'), false);
  });
  // A 저장소에서 'business' 이미지 변경 시도 -> 성공
  act(() => {
    assert.equal(controllerA!.imageStore.apply('me', { type: 'department', id: 'business' }, 'https://example.org/a-biz.png', 'req-a-2'), true);
  });
});

test('R3-1 (b): React.StrictMode 환경에서 setPlayer는 부수효과 없이 단일하고 일관된 멤버십을 유지한다', () => {
  let controller: Controller;
  function StrictProbe() { controller = useMockGame(); return null; }

  render(React.createElement(React.StrictMode, null,
    React.createElement(MockGameProvider, null, React.createElement(StrictProbe)),
  ));

  // StrictMode 하에서 함수형 업데이터로 setPlayer 실행
  act(() => {
    controller!.setPlayer((prev) => ({ ...prev, department: '경영학과' }));
  });

  // 디렉터리 내 'me' 유저가 중복 생성되지 않고 정확히 1개만 존재하며 business로 갱신됨
  const matchingUsers = controller!.imageStore.getDirectory().users.filter((u) => u.userId === 'me');
  assert.equal(matchingUsers.length, 1);
  assert.equal(matchingUsers[0].departmentId, 'business');
  assert.equal(matchingUsers[0].schoolId, SCHOOL_ID.hanyang);
});

test('R3-1 (c): 전역 초기화에 의존하지 않고 시드 디렉터리는 깊은 동결 불변성을 유지한다', () => {
  assert.ok(Object.isFrozen(MOCK_IMAGE_DIRECTORY));
  assert.ok(Object.isFrozen(MOCK_IMAGE_DIRECTORY.schoolIds));
  assert.ok(Object.isFrozen(MOCK_IMAGE_DIRECTORY.departments));
  assert.ok(Object.isFrozen(MOCK_IMAGE_DIRECTORY.users));
  // 시드 원본 수정 시도 시 TypeError 발생
  assert.throws(() => {
    ((MOCK_IMAGE_DIRECTORY as unknown) as { schoolIds: readonly SchoolId[] }).schoolIds = [];
  }, TypeError);
});

test('P4-4: ImageChangeShop 확인 다이얼로그 액션 버튼 스타일 및 비활성화 상태 검증', () => {
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(ImageChangeShop),
    ),
  );

  // 1. 다이얼로그 오픈 (학교 이미지 변경 선택)
  clickButton(/학교 이미지 변경 ·/);

  // 2. 다이얼로그 내부의 모든 액션 버튼이 non-empty className을 가진 button인지 검증
  const dialog = screen.getByRole('dialog', { name: '학교 이미지 변경 확인' });
  const buttons = dialog.querySelectorAll('button');
  assert.ok(buttons.length >= 4, `Expected at least 4 action buttons, found ${buttons.length}`);
  for (const btn of buttons) {
    assert.ok(btn.className && btn.className.trim().length > 0, `Button ${btn.textContent} has empty className`);
    assert.match(btn.className, /pixel-plaque/, `Button ${btn.textContent} does not use pixel-plaque class`);
  }

  // 3. URL 입력 전: confirm 버튼이 disabled 상태이며 disabled 스타일링(opacity-50, cursor-not-allowed)을 가짐
  const confirmBtn = screen.getByRole('button', { name: '모의 결제 성공 및 즉시 적용' }) as HTMLButtonElement;
  assert.equal(confirmBtn.disabled, true);
  assert.match(confirmBtn.className, /opacity-50/);
  assert.match(confirmBtn.className, /cursor-not-allowed/);

  // 4. 로컬 샘플 사용 클릭 후: confirm 버튼이 활성화(disabled = false)되고 disabled 스타일링이 해제됨
  clickButton('로컬 샘플 사용');
  assert.equal(confirmBtn.disabled, false);
  assert.doesNotMatch(confirmBtn.className, /opacity-50 cursor-not-allowed shadow-none/);
  assert.match(confirmBtn.className, /cursor-pointer/);
});

test('SCRUM-224: 내 캠퍼스 손 안내 문구·모서리 버튼 비전파·상세 스탯 항목 분리를 지킨다', () => {
  let taps = 0;
  let guestbookOpened = 0;
  let statsOpened = 0;
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(CampusView, {
        onTap: () => { taps += 1; },
        clickPower: 30,
        basePower: 30,
        activeStudents: 37,
        tapPrompt: '클릭!',
        onOpenGuestbook: () => { guestbookOpened += 1; },
        onOpenStats: () => { statsOpened += 1; },
      }),
    ),
  );

  const stage = document.querySelector('[data-campus-stage]');
  assert.ok(stage?.hasAttribute('data-my-campus'), '내 캠퍼스 프레임에 전용 속성이 붙는다');
  assert.ok(screen.getByText('클릭!'), 'PC에서는 손 모양 안내가 클릭!으로 보인다');
  fireEvent.click(screen.getByRole('button', { name: '우리 학교 방명록 보기' }));
  fireEvent.click(screen.getByTestId('campus-stats-button'));
  assert.equal(guestbookOpened, 1);
  assert.equal(statsOpened, 1);
  assert.equal(taps, 0, '방명록·상세 스탯 버튼은 캠퍼스 점수 클릭을 유발하지 않는다');

  fireEvent.click(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }));
  assert.equal(taps, 1);
  assertAbsent(screen.queryByText('클릭!'), '첫 클릭 뒤에는 손 모양 안내가 사라진다');
  cleanup();

  // 기본값(모바일/시연)은 터치! 유지
  render(
    React.createElement(
      MockGameProvider,
      null,
      React.createElement(CampusView, {
        onTap: () => {},
        clickPower: 30,
        basePower: 30,
        activeStudents: 37,
      }),
    ),
  );
  assert.ok(screen.getByText('터치!'), '기본 손 모양 안내는 터치!를 유지한다');
  cleanup();

  // 상세 스탯: 기본 세기·치명타·보조 안내 분리 및 숫자만 굵게
  render(React.createElement(MockGameProvider, null, React.createElement(DialogPanel, { myContribution: 18560, clickerLevel: 3 })));
  const stats = Array.from(document.querySelectorAll('.game-clicker-stat')).map((el) => el.textContent?.trim());
  assert.deepEqual(stats, ['기본 세기 30점', '치명타 2배']);
  const bolds = Array.from(document.querySelectorAll('.game-clicker-stat-num')).map((el) => el.textContent?.trim());
  assert.deepEqual(bolds, ['30', '2'], '숫자만 굵게 표시한다');
  assert.equal(document.querySelector('.game-clicker-note')?.textContent?.trim(), '강화는 상점에서');
});
