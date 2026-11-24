import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { REDUCED_MOTION_QUERY, stubMatchMedia } from './helpers/matchMedia';
import { MOCK_REALTIME_CONFIG, planOtherClickParticles, createSeededRandom, type OtherClicksSource } from '../src/data/mockRealtime';
import type { OtherClickScheduler } from '../src/hooks/useMockOtherClicks';

const hooks = registerHooks({ load(url, context, nextLoad) {
  if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: `export default ${JSON.stringify(url)};`, shortCircuit: true };
  return nextLoad(url, context);
} });
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window), cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window), IS_REACT_ACT_ENVIRONMENT: true })) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
dom.window.HTMLElement.prototype.scrollIntoView = function () {};
const { render, screen, fireEvent, cleanup, act } = await import('@testing-library/react');
const { clickTestView } = await import('./helpers/mockTools');
const { useMockOtherClicks } = await import('../src/hooks/useMockOtherClicks');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { CampusView } = await import('../src/components/CampusView');
const { getSchoolRankings } = await import('../src/data/mockRankings');
const { default: App } = await import('../src/App');
afterEach(() => cleanup());
after(() => { hooks.deregister(); dom.window.close(); });

class Clock implements OtherClickScheduler {
  now = 0;
  seq = 0;
  tasks = new Map<number, { at: number; callback: () => void; repeat: number }>();
  interval = (callback: () => void, ms: number) => this.add(callback, ms, ms);
  timeout = (callback: () => void, ms: number) => this.add(callback, ms, 0);
  add(callback: () => void, ms: number, repeat: number) {
    const id = ++this.seq;
    this.tasks.set(id, { at: this.now + ms, callback, repeat });
    return () => { this.tasks.delete(id); };
  }
  async advance(ms: number) {
    const end = this.now + ms;
    while (true) {
      const next = [...this.tasks].filter(([, task]) => task.at <= end).sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!next) break;
      const [id, task] = next;
      this.now = task.at;
      if (task.repeat) task.at += task.repeat; else this.tasks.delete(id);
      await act(async () => { task.callback(); await Promise.resolve(); });
    }
    this.now = end;
  }
}

test('타 클릭 플래너 상한, 가중치, 균등 시간 분산과 설정 주입', () => {
  const c = MOCK_REALTIME_CONFIG;
  for (const n of [0, -1, NaN, Infinity]) assert.deepEqual(planOtherClickParticles(n, c, () => 0.5), []);
  assert.equal(planOtherClickParticles(5, c, () => 0.5).length, 5);
  const result = planOtherClickParticles(100, c, createSeededRandom(123));
  assert.equal(result.length, 20);
  assert.equal(result.reduce((sum, p) => sum + p.weight, 0), 100);
  assert.deepEqual(result, planOtherClickParticles(100, c, createSeededRandom(123)));
  assert.ok(result.every(p => p.delayMs >= 0 && p.delayMs < c.pollIntervalMs && p.xPct >= 15 && p.xPct <= 85 && p.yPct >= 22 && p.yPct <= 78));
  assert.ok(new Set(result.map(p => p.delayMs)).size > 1);
  const alternate = { ...c, maxParticlesPerTick: 5, pollIntervalMs: 500 };
  const smaller = planOtherClickParticles(100, alternate, createSeededRandom(123));
  assert.equal(smaller.length, 5);
  assert.equal(smaller.reduce((sum, p) => sum + p.weight, 0), 100);
  assert.ok(smaller.every(p => p.delayMs < 500));
});

test('틱마다 내 클릭을 전송하고 초기화, 실패 격리 및 언마운트 정리', async () => {
  const clock = new Clock();
  let clicks = 2;
  const sent: number[] = [];
  const source: OtherClicksSource = { exchange(n) { sent.push(n); if (sent.length === 2) throw Error('offline'); return { otherClicks: 5 }; } };
  const take = () => { const n = clicks; clicks = 0; return n; };
  function Probe() { useMockOtherClicks({ getMyClicksSinceLastTick: take, source, scheduler: clock }); return null; }
  const view = render(React.createElement(Probe));
  await clock.advance(1000);
  assert.deepEqual(sent, [2]);
  clicks = 3;
  await clock.advance(1000);
  await clock.advance(1000);
  assert.deepEqual(sent, [2, 3, 0]);
  view.unmount();
  await clock.advance(5000);
  assert.deepEqual(sent, [2, 3, 0]);
  assert.equal(clock.tasks.size, 0);
});

test('소스 교체 시 이전 입자와 타이머를 정리한다', async () => {
  const clock = new Clock();
  const take = () => 0;
  const first: OtherClicksSource = { exchange: () => ({ otherClicks: 5 }) };
  const second: OtherClicksSource = { exchange: () => ({ otherClicks: 0 }) };
  function Probe({ source }: { source: OtherClicksSource }) {
    const result = useMockOtherClicks({ getMyClicksSinceLastTick: take, source, scheduler: clock });
    return React.createElement('output', null, `${result.particles.length}:${result.otherClicks}`);
  }
  const view = render(React.createElement(Probe, { source: first }));
  await clock.advance(2000);
  assert.notEqual(screen.getByRole('status').textContent, '0:5');
  view.rerender(React.createElement(Probe, { source: second }));
  assert.equal(screen.getByRole('status').textContent, '0:null');
  await clock.advance(1000);
  assert.equal(screen.getByRole('status').textContent, '0:0');
  view.unmount();
  assert.equal(clock.tasks.size, 0);
});

test('렌더 상한·수명·입력·감속 모션과 게임 데이터 불변', async () => {
  stubMatchMedia(dom.window as unknown as Window, [REDUCED_MOTION_QUERY]);
  const clock = new Clock();
  let calls = 0;
  let game: ReturnType<typeof useMockGame>;
  const sent: number[] = [];
  const source: OtherClicksSource = { exchange: (count) => { sent.push(count); return { otherClicks: 100 }; } };
  const config = { ...MOCK_REALTIME_CONFIG, maxActiveParticles: 10 };
  function Probe() { game = useMockGame(); return null; }
  const view = render(React.createElement(MockGameProvider, null, React.createElement(Probe), React.createElement(CampusView, { onTap: () => { calls++; }, clickPower: 1, basePower: 1, activeStudents: 1, otherClicksSource: source, realtimeConfig: config, realtimeScheduler: clock })));
  const initial = JSON.stringify(game!.state);
  const ranking = JSON.stringify(getSchoolRankings(game!.state.schoolScore));
  for (let i = 0; i < 35; i++) {
    await clock.advance(100);
    assert.ok(screen.queryAllByTestId('other-click-particle').length <= config.maxActiveParticles);
  }
  assert.ok(screen.getAllByTestId('other-click-particle').length <= 10);
  assert.ok(screen.getAllByTestId('other-click-particle').length > 0);
  assert.ok(screen.getAllByTestId('other-click-particle').every(el => el.classList.contains('pointer-events-none') && !el.classList.contains('other-click-animate')));
  assert.ok(screen.getByText(/다른 사용자 100회 클릭 · 점수 미반영/));
  assert.equal(JSON.stringify(game!.state), initial);
  assert.equal(JSON.stringify(getSchoolRankings(game!.state.schoolScore)), ranking);
  fireEvent.click(screen.getByRole('button', { name: '캠퍼스 클릭 영역' }));
  assert.equal(calls, 1);
  await clock.advance(500);
  assert.equal(sent.at(-1), 1);
  await clock.advance(1000);
  assert.equal(sent.at(-1), 0);
  // Stop new polls while allowing previously scheduled expirations to run.
  const intervalTask = [...clock.tasks].find(([, t]) => t.repeat > 0)!;
  clock.tasks.delete(intervalTask[0]);
  await clock.advance(2500);
  assert.equal(screen.queryAllByTestId('other-click-particle').length, 0);
  view.unmount();
});

test('방문·대결 화면에는 타 클릭 파티클 없음', (t) => {
  dom.reconfigure({ url: 'http://localhost/?device=framed' });
  t.after(() => dom.reconfigure({ url: 'http://localhost/' }));
  render(React.createElement(MockGameProvider, null, React.createElement(App)));
  for (const name of ['📖 2-Phone 방문교 방명록', '⚔️ 대결 신청 (Battle Apply)', '📊 대결 현황 (Battle Status)']) {
    clickTestView(name);
    assert.equal(screen.queryAllByTestId('other-click-particle').length, 0);
  }
});
