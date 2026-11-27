// SCRUM-159: other students' click particles driven by click responses in API mode.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import type { ClickRequest, ClickResponse } from '../src/api/clicks';
import type { MyInfo } from '../src/api/users';
import type { OtherClicksSource } from '../src/data/mockRealtime';
import type { OtherClickScheduler } from '../src/hooks/useMockOtherClicks';
import { ApiError } from '../src/api/errors';
import { createClickBatcher } from '../src/utils/clickBatcher';
import { createOtherClicksEstimator, createOtherClicksFeed, OWN_CLICK_OVERLAP_MS } from '../src/utils/otherClicksFeed';
import { installDialogStub } from './helpers/dialogStub';
import { assertAbsent } from './helpers/domAssert';

const assets = registerHooks({ load(url, context, nextLoad) {
  if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: 'export default "/asset";', shortCircuit: true };
  return nextLoad(url, context);
} });
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true,
  requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window), cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window),
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
dom.window.HTMLElement.prototype.scrollIntoView = function () {};
installDialogStub(dom.window);

const { render, screen, fireEvent, cleanup, act, waitFor } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { MockGameProvider, useMockGame } = await import('../src/hooks/useMockGame');
const { useApiCampusClicks } = await import('../src/hooks/useCampusClicks');
const { useOtherClicks, OTHER_CLICKS_STALE_MS } = await import('../src/hooks/useMockOtherClicks');
const { OtherClicksFeedContext } = await import('../src/hooks/otherClicksFeedContext');
const { ApiPlayerSync } = await import('../src/components/ApiPlayerSync');
const { CampusView } = await import('../src/components/CampusView');
const queryClients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => {
  cleanup();
  for (const client of queryClients.splice(0)) client.clear();
});
after(() => { dom.window.close(); assets.deregister(); });

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

test('추정기는 직전 초 버킷에 들 수 있는 내 이전 배치만 빼고 0 아래로 내려가지 않는다', () => {
  const estimator = createOtherClicksEstimator();
  // The first batch cannot overlap an earlier own batch: the whole previous second is others'.
  assert.equal(estimator.accept(7, 3, 1000), 7);
  // 1s later the previous batch (3 clicks) may be in the bucket.
  assert.equal(estimator.accept(10, 2, 2000), 7);
  // Both earlier batches are within the window; the result is a lower bound, never negative.
  assert.equal(estimator.accept(4, 5, 2900), 0);
  // At exactly the overlap boundary the oldest batches drop out; only the one sent at 2900 remains.
  assert.equal(estimator.accept(9, 1, 2900 + OWN_CLICK_OVERLAP_MS - 1), 4);
  assert.equal(estimator.accept(9, 1, 10_000), 9);
});

test('추정기는 잘못된 서버 값을 0으로 다루고 그 배치도 이후 차감에 남긴다', () => {
  const estimator = createOtherClicksEstimator();
  for (const value of [Number.NaN, -3, 0, Number.POSITIVE_INFINITY]) assert.equal(estimator.accept(value, 0, 0), 0);
  assert.equal(estimator.accept(Number.NaN, 4, 100), 0);
  assert.equal(estimator.accept(6.9, 1, 600), 2);
});

test('배치 전송기는 수락된 요청과 실제로 성공한 시도의 전송 시각을 넘긴다', async () => {
  let time = 0;
  let sequence = 0;
  let ids = 0;
  let attempts = 0;
  const tasks = new Map<number, { at: number; run: () => void }>();
  const accepted: Array<{ request: ClickRequest; sentAt: number }> = [];
  const response: ClickResponse = {
    acceptedClicks: 2, earnedScore: 2, multiplier: 1, myContribScore: 2,
    university: { id: 1, totalScore: 2, rank: 1, logoUrl: 'tempUrl' }, department: { id: 7, totalScore: 2 }, schoolClicksLastSecond: 5, libraryBonus: false, criticalIndex: [], damageRateIndex: [],
  };
  const controller = createClickBatcher({
    now: () => time,
    createId: () => `submission-${++ids}`,
    schedule(run, delay) { const id = ++sequence; tasks.set(id, { at: time + delay, run }); return () => { tasks.delete(id); }; },
    send: async () => {
      attempts += 1;
      if (attempts === 1) throw new ApiError({ kind: 'network', code: 'CLIENT_NETWORK_ERROR', status: null });
      return response;
    },
    onAccepted: (_data, _pending, meta) => { accepted.push(meta); },
    onChange: () => undefined,
  });
  const advance = async (ms: number) => {
    const until = time + ms;
    while (true) {
      const next = [...tasks.entries()].sort((a, b) => a[1].at - b[1].at)[0];
      if (!next || next[1].at > until) break;
      time = Math.max(time, next[1].at);
      tasks.delete(next[0]);
      next[1].run();
      for (let i = 0; i < 12; i++) await Promise.resolve();
    }
    time = until;
    for (let i = 0; i < 12; i++) await Promise.resolve();
  };
  controller.enqueue(1);
  controller.enqueue(1);
  await advance(1000);
  assert.equal(attempts, 1);
  await advance(1000);
  assert.equal(attempts, 2);
  assert.deepEqual(accepted, [{ request: { submissionId: 'submission-1', clickCount: 2 }, sentAt: 2000 }]);
});

test('feed가 있으면 목업 소스를 폴링하지 않고 응답마다 파티클을 띄우며 3초 뒤 수치를 지운다', async () => {
  const clock = new Clock();
  const feed = createOtherClicksFeed();
  let polled = 0;
  const source: OtherClicksSource = { exchange: () => { polled += 1; return { otherClicks: 99 }; } };
  const noOwnClicks = () => 0;
  function Probe() {
    const result = useOtherClicks({ getMyClicksSinceLastTick: noOwnClicks, source, scheduler: clock, feed });
    return React.createElement('output', null, `${result.particles.length}:${result.otherClicks}`);
  }
  const view = render(React.createElement(Probe));
  assert.equal([...clock.tasks.values()].filter((task) => task.repeat > 0).length, 0);
  await clock.advance(5000);
  assert.equal(polled, 0);
  assert.equal(screen.getByRole('status').textContent, '0:null');

  await act(async () => { feed.publish(4); });
  assert.equal(screen.getByRole('status').textContent, '0:4');
  await clock.advance(999);
  assert.equal(screen.getByRole('status').textContent, '4:4');
  await clock.advance(OTHER_CLICKS_STALE_MS - 999);
  assert.equal(screen.getByRole('status').textContent, '0:null');
  assert.equal(polled, 0);

  // A new estimate restarts the stale timer instead of letting the earlier one clear it.
  await act(async () => { feed.publish(0); });
  await clock.advance(OTHER_CLICKS_STALE_MS - 1);
  await act(async () => { feed.publish(2); });
  await clock.advance(2);
  assert.equal(screen.getByRole('status').textContent.endsWith(':2'), true);
  view.unmount();
  assert.equal(clock.tasks.size, 0);
  await act(async () => { feed.publish(5); });
});

const ME: MyInfo = {
  id: 10, nickname: '파티클학생', email: 'test@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 1, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 100 }, totalContribScore: 10, totalAttackScore: 0,
};
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status: 200 });

test('API 모드 캠퍼스는 클릭 응답의 schoolClicksLastSecond로 파티클과 안내를 표시하고 목업 소스를 호출하지 않는다', async () => {
  const sent: ClickRequest[] = [];
  const lastSecond = [7, 12];
  const client = createApiClient({ fetch: (async (input, init) => {
    const path = String(input);
    if (path.endsWith('/users/me')) return ok(ME);
    if (path.endsWith('/clicks')) {
      const body = JSON.parse(String(init?.body)) as ClickRequest;
      sent.push(body);
      const total = sent.reduce((sum, request) => sum + request.clickCount, 0);
      return ok({
        acceptedClicks: body.clickCount, earnedScore: body.clickCount, multiplier: 1, myContribScore: total,
        university: { id: 1, totalScore: 100 + total, rank: 1, logoUrl: 'tempUrl' }, department: { id: 7, totalScore: 20 + total },
        schoolClicksLastSecond: lastSecond[sent.length - 1] ?? 0, libraryBonus: false, criticalIndex: [], damageRateIndex: [],
      } satisfies ClickResponse);
    }
    if (path.endsWith('/auth/reissue')) return ok({ accessToken: 'member-a', expiresInSeconds: 3600 });
    throw new Error(`Unexpected request: ${path}`);
  }) as typeof fetch });
  client.session.signIn('member-a');
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  let polled = 0;
  const source: OtherClicksSource = { exchange: () => { polled += 1; return { otherClicks: 99 }; } };
  function Campus() {
    const clicks = useApiCampusClicks();
    const game = useMockGame();
    return React.createElement(OtherClicksFeedContext.Provider, { value: clicks.otherClicksFeed },
      React.createElement(ApiPlayerSync, { getPendingScore: clicks.getPendingScore }),
      React.createElement('output', { 'data-testid': 'contribution' }, String(game.state.contribution)),
      React.createElement(CampusView, { onTap: (_x: number, _y: number, isCrit: boolean) => clicks.tap(isCrit), clickPower: 1, basePower: 1, activeStudents: 37, otherClicksSource: source }));
  }
  render(React.createElement(ApiProvider, { client, queryClient, children: React.createElement(MockGameProvider, null, React.createElement(Campus)) }));
  assert.ok(screen.getByText('클릭하면 같은 학교 학생들의 직전 1초 클릭이 함께 보입니다.'));
  assertAbsent(screen.queryByText(/\[시연\]/), 'API 모드에는 목업 시연 문구가 없어야 합니다.');
  // ApiPlayerSync must load the member before taps count.
  await waitFor(() => assert.equal(screen.getByTestId('contribution').textContent, '10'));
  const area = screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
  fireEvent.click(area);
  fireEvent.click(area);
  assert.equal(screen.getByTestId('contribution').textContent, '12');
  await waitFor(() => assert.ok(screen.getByText('직전 1초 다른 학생 약 7회 클릭')), { timeout: 2500 });
  assert.deepEqual(sent.map((request) => request.clickCount), [2]);
  // The next batch goes about 1s after this click, so the first batch's 2 own clicks are subtracted from 12.
  fireEvent.click(area);
  await waitFor(() => assert.ok(screen.getAllByTestId('other-click-particle').length > 0), { timeout: 1500 });
  await waitFor(() => assert.ok(screen.getByText('직전 1초 다른 학생 약 10회 클릭')), { timeout: 2500 });
  assert.deepEqual(sent.map((request) => request.clickCount), [2, 1]);
  assert.equal(polled, 0);
});

test('CampusView는 feed가 없을 때만 목업 폴링 문구를 쓰고 App은 API 큐의 feed를 Context로 내린다', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(app, /<OtherClicksFeedContext\.Provider value=\{campusClicks\.otherClicksFeed\}>/);
  const view = readFileSync(new URL('../src/components/CampusView.tsx', import.meta.url), 'utf8');
  assert.match(view, /useOtherClicks\(\{[^}]*feed: otherClicksFeed \}\)/);
  assert.doesNotMatch(view, /useMockOtherClicks\(/);
});
