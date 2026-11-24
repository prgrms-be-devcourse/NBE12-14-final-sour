import { SCHOOL_ID } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { assertAbsent } from './helpers/domAssert';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { REDUCED_MOTION_QUERY, stubMatchMedia } from './helpers/matchMedia';
import { getCampusTimePhase, resolveAmbience, MOCK_AMBIENCE_CONFIG, type CampusPhase } from '../src/data/campusAmbience';

const hooks = registerHooks({ load(url, context, nextLoad) {
  if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: `export default ${JSON.stringify(url)};`, shortCircuit: true };
  return nextLoad(url, context);
} });
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window), cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window), IS_REACT_ACT_ENVIRONMENT: true })) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
dom.window.HTMLElement.prototype.scrollIntoView = function () {};
const { render, screen, fireEvent, cleanup } = await import('@testing-library/react');
const { clickTestView, withToolSetting } = await import('./helpers/mockTools');
const { MockGameProvider } = await import('../src/hooks/useMockGame');
const { CampusAmbienceProvider } = await import('../src/hooks/useCampusTimePhase');
const { CampusView } = await import('../src/components/CampusView');
const { VisitingScreen1Phone } = await import('../src/components/VisitingScreen1Phone');
const { VisitingScreen2Phone } = await import('../src/components/VisitingScreen2Phone');
const { default: App } = await import('../src/App');
afterEach(() => cleanup());
after(() => { hooks.deregister(); dom.window.close(); });

const kst = (hour: number, minute = 0) => Date.UTC(2026, 10, 7, hour - 9, minute);
test('KST 경계와 로케일 독립, 전이 및 대체 설정', () => {
  const c = MOCK_AMBIENCE_CONFIG;
  const cases: [number, number, CampusPhase][] = [[4, 59, 'night'], [5, 0, 'dawn'], [7, 0, 'day'], [17, 0, 'dusk'], [19, 0, 'night'], [23, 59, 'night']];
  const old = process.env.TZ;
  try {
    for (const tz of ['UTC', 'America/New_York', 'Asia/Seoul']) {
      process.env.TZ = tz;
      for (const [hour, minute, phase] of cases) assert.equal(getCampusTimePhase(kst(hour, minute), c).phase, phase);
    }
  } finally { if (old === undefined) delete process.env.TZ; else process.env.TZ = old; }
  assert.equal(getCampusTimePhase(kst(7), c).blend, 0);
  assert.equal(getCampusTimePhase(kst(16, 50), c).blend, 0.5);
  const alternate = { ...c, transitionMinutes: 60, phases: [{ phase: 'dawn' as const, startHour: 6 }, ...c.phases.slice(1)] };
  assert.equal(getCampusTimePhase(kst(5), alternate).phase, 'night');
  assert.equal(getCampusTimePhase(kst(16, 30), alternate).blend, 0.5);
  for (let minute = 0; minute < 24 * 60; minute++) {
    const info = getCampusTimePhase(kst(0, minute), c);
    assert.ok(info.blend >= 0 && info.blend <= 1);
    const visual = resolveAmbience(info, 'auto', c);
    for (const layer of visual.layers) {
      assert.ok(layer.tintOpacity <= c.maxTintOpacity && layer.tintOpacity >= 0);
      assert.ok(layer.glowIntensity <= c.maxLightOpacity && layer.glowIntensity >= 0);
    }
  }
  const info = getCampusTimePhase(kst(12), c);
  const night = resolveAmbience(info, 'night', c);
  assert.ok(night.layers[0].tintOpacity >= c.minNightTintOpacity && night.layers[0].tintOpacity <= c.maxTintOpacity);
  assert.equal(night.layers[0].windowOpacity, c.windowLightOpacity);
  assert.equal(night.phase, 'night');
  assert.equal(resolveAmbience(info, 'night', c).blend, 0);
  assert.equal(resolveAmbience(info, 'auto', c).phase, 'day');
});

test('세 캠퍼스에 포인터 비차단 레이어, 클릭, 밤 창문과 reduced motion', (t) => {
  t.mock.method(Date, 'now', () => kst(21));
  stubMatchMedia(dom.window as unknown as Window, [REDUCED_MOTION_QUERY]);
  let taps = 0;
  for (const child of [React.createElement(CampusView, { onTap: () => { taps++; }, clickPower: 1, basePower: 1, activeStudents: 1 }), React.createElement(VisitingScreen1Phone, { schoolId: SCHOOL_ID.pusan }), React.createElement(VisitingScreen2Phone, { schoolId: SCHOOL_ID.pusan })]) {
    const result = render(React.createElement(MockGameProvider, null, React.createElement(CampusAmbienceProvider, null, child)));
    const overlay = screen.getByTestId('campus-ambience');
    assert.ok(overlay.classList.contains('pointer-events-none'));
    assert.ok(overlay.querySelector('[data-ambience-layer="night"]'));
    assert.ok(overlay.querySelector('[data-ambience-layer="window"]'));
    assertAbsent(overlay.querySelector('.campus-window-twinkle, .campus-sun-sway'));
    if (screen.queryByRole('button', { name: '캠퍼스 클릭 영역' })) fireEvent.click(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }));
    result.unmount();
  }
  assert.equal(taps, 1);
});

test('시연 override 공유, 자동 복귀 및 적용 범위 제한', (t) => {
  t.mock.method(Date, 'now', () => kst(12));
  dom.reconfigure({ url: 'http://localhost/?device=framed' });
  t.after(() => dom.reconfigure({ url: 'http://localhost/' }));
  render(React.createElement(MockGameProvider, null, React.createElement(App)));
  const setPhase = (value: string) => withToolSetting('[시연] 시간대', (control) => fireEvent.change(control, { target: { value } }));
  for (const name of ['3단 온보딩 화면', '🗺️ 단일 폰 지도', '🏪 상점 & 강화 (Single Phone)', '🏆 랭킹 화면 (Single Phone)']) {
    clickTestView(name);
    assertAbsent(screen.queryByTestId('campus-ambience'));
  }
  clickTestView('🎮 메인 게임 화면');
  setPhase('night');
  assert.equal(screen.getByTestId('campus-ambience').getAttribute('data-phase'), 'night');
  setPhase('day');
  assertAbsent(screen.getByTestId('campus-ambience').querySelector('[data-ambience-layer="window"]'));
  setPhase('auto');
  assert.equal(screen.getByTestId('campus-ambience').getAttribute('data-phase'), 'day');
  setPhase('dusk');
  clickTestView('📖 2-Phone 방문교 방명록');
  assert.ok(screen.getAllByTestId('campus-ambience').every((el) => el.getAttribute('data-phase') === 'dusk'));
});
