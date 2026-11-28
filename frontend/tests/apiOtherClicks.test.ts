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
import { activityCount, createFeed, OWN_CLICK_OVERLAP_MS, type SchoolActivity } from '../src/utils/otherClicksFeed';
import { ACTIVITY_FRESH_MS, activityHubFor, createActivityHub } from '../src/utils/universityActivity';
import type { UniversityActivity } from '../src/api/activity';
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
const { UniversityActivityContext, useUniversityActivityFeed, ACTIVITY_POLL_MS, ACTIVITY_MAX_BACKOFF_MS } = await import('../src/hooks/useUniversityActivity');
const { getUniversityActivity } = await import('../src/api/activity');
const { ApiPlayerSync } = await import('../src/components/ApiPlayerSync');
const { CampusView } = await import('../src/components/CampusView');
const { OtherActivityParticles, activitySummary } = await import('../src/components/OtherActivityParticles');
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

test('허브는 같은 초를 한 번만 내보내고, 이전 초와 second 없는 보고(이전 서버)는 버린다', () => {
  let time = 10_000;
  const hub = createActivityHub({ now: () => time });
  const seen: SchoolActivity[] = [];
  hub.feed(1).subscribe((activity) => seen.push(activity));
  assert.equal(hub.report(1, { second: 100, clicks: 4, attacks: 1, assists: 0 }), true);
  // The poll and an answer about the same second: drawn once.
  assert.equal(hub.report(1, { second: 100, clicks: 4, attacks: 1, assists: 0 }), false);
  assert.equal(hub.report(1, { second: 99, clicks: 9, attacks: 9, assists: 9 }), false);
  assert.equal(hub.report(1, { clicks: 9, attacks: 9, assists: 9 }), false);
  assert.equal(hub.report(1, null), false);
  assert.equal(hub.report(1, { second: 101, clicks: -3, attacks: Number.NaN }), true);
  // Another school has its own seconds.
  assert.equal(hub.report(2, { second: 50, clicks: 1, attacks: 0, assists: 0 }), true);
  assert.deepEqual(seen, [{ clicks: 4, attacks: 1, assists: 0 }, { clicks: 0, attacks: 0, assists: 0 }]);
  // A report keeps the school fresh for ACTIVITY_FRESH_MS; one without a second does not count.
  assert.equal(hub.isFresh(1), true);
  time += ACTIVITY_FRESH_MS;
  assert.equal(hub.isFresh(1), false);
  hub.report(1, { clicks: 1 });
  assert.equal(hub.isFresh(1), false);
  assert.equal(hub.isFresh(3), false);
});

test('허브는 같은 학교·종류의 최근 내 배치만 빼고, 0 아래로 내려가지 않는다', () => {
  let time = 10_000;
  const hub = createActivityHub({ now: () => time });
  const seen: SchoolActivity[] = [];
  hub.feed(1).subscribe((activity) => seen.push(activity));
  hub.recordOwn(1, 'click', 3, 9_000);
  hub.recordOwn(1, 'attack', 2, 9_500);
  hub.recordOwn(2, 'click', 5, 9_500);
  hub.recordOwn(1, 'assist', 0, 9_500);
  hub.report(1, { second: 100, clicks: 10, attacks: 1, assists: 4 });
  // My 3 clicks and 2 attacks at school 1; school 2's batch and the empty assist are not taken out.
  assert.deepEqual(seen.at(-1), { clicks: 7, attacks: 0, assists: 4 });
  // A batch older than the window may no longer be in the reported second.
  time = 9_000 + OWN_CLICK_OVERLAP_MS;
  hub.report(1, { second: 101, clicks: 10, attacks: 1, assists: 4 });
  assert.deepEqual(seen.at(-1), { clicks: 10, attacks: 0, assists: 4 });
  hub.clearOwn();
  hub.report(1, { second: 102, clicks: 10, attacks: 1, assists: 4 });
  assert.deepEqual(seen.at(-1), { clicks: 10, attacks: 1, assists: 4 });
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

test('feed가 있으면 목업 소스를 폴링하지 않고 보고마다 파티클을 띄우며 3초 뒤 수치를 지운다', async () => {
  const clock = new Clock();
  const feed = createFeed<SchoolActivity>();
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

  await act(async () => { feed.publish({ clicks: 4, attacks: 0, assists: 0 }); });
  assert.equal(screen.getByRole('status').textContent, '0:4');
  await clock.advance(999);
  assert.equal(screen.getByRole('status').textContent, '4:4');
  await clock.advance(OTHER_CLICKS_STALE_MS - 999);
  assert.equal(screen.getByRole('status').textContent, '0:null');
  assert.equal(polled, 0);

  // A new estimate restarts the stale timer instead of letting the earlier one clear it.
  await act(async () => { feed.publish({ clicks: 0, attacks: 0, assists: 0 }); });
  await clock.advance(OTHER_CLICKS_STALE_MS - 1);
  await act(async () => { feed.publish({ clicks: 2, attacks: 0, assists: 0 }); });
  await clock.advance(2);
  assert.equal(screen.getByRole('status').textContent.endsWith(':2'), true);
  view.unmount();
  assert.equal(clock.tasks.size, 0);
  await act(async () => { feed.publish({ clicks: 5, attacks: 0, assists: 0 }); });
});

test('서버 수는 0 이상의 정수로 읽고, 없거나 잘못된 값(이전 서버)은 0이다', () => {
  assert.deepEqual([undefined, null, Number.NaN, -2, Number.POSITIVE_INFINITY, '3', 0, 4.8, 12].map(activityCount), [0, 0, 0, 0, 0, 0, 0, 4, 12]);
});

test('활동 폴링은 1초마다 그 학교를 묻고, 1초 안에 보고가 있었으면 건너뛰며, 숨겨진 탭에서는 쉰다', async () => {
  const clock = new Clock();
  const hub = createActivityHub({ now: () => clock.now });
  const asked: number[] = [];
  let hidden = false;
  let second = 0;
  const source = {
    hub, myUniversityId: 1, scheduler: clock, now: () => clock.now, isHidden: () => hidden,
    fetchActivity: async (id: number): Promise<UniversityActivity> => { asked.push(id); second += 1; return { second, clicks: 2, attacks: 0, assists: 1 }; },
  };
  const seen: SchoolActivity[] = [];
  hub.feed(7).subscribe((activity) => seen.push(activity));
  function Probe({ id }: { id: number | null }) { useUniversityActivityFeed(source, id); return null; }
  const view = render(React.createElement(Probe, { id: 7 }));
  await clock.advance(ACTIVITY_POLL_MS);
  await clock.advance(ACTIVITY_POLL_MS);
  assert.deepEqual(asked, [7, 7]);
  assert.deepEqual(seen, [{ clicks: 2, attacks: 0, assists: 1 }, { clicks: 2, attacks: 0, assists: 1 }]);
  // An answer about this school (my click or attack) just reported it: no request this second.
  await clock.advance(ACTIVITY_POLL_MS - 1);
  hub.report(7, { second: 50, clicks: 0, attacks: 0, assists: 0 });
  await clock.advance(1);
  assert.equal(asked.length, 2);
  await clock.advance(ACTIVITY_POLL_MS);
  assert.equal(asked.length, 3);
  hidden = true;
  await clock.advance(ACTIVITY_POLL_MS * 3);
  assert.equal(asked.length, 3);
  hidden = false;
  // Another school: the poll follows the screen; no school, no poll.
  view.rerender(React.createElement(Probe, { id: 8 }));
  await clock.advance(ACTIVITY_POLL_MS);
  assert.equal(asked.at(-1), 8);
  view.rerender(React.createElement(Probe, { id: null }));
  assert.equal(clock.tasks.size, 0);
  view.unmount();
});

test('활동 폴링이 실패하면 2초·4초…최대 10초로 늦추고, 성공하면 다시 1초마다 묻는다', async () => {
  const clock = new Clock();
  const hub = createActivityHub({ now: () => clock.now });
  const askedAt: number[] = [];
  let failing = true;
  let second = 0;
  const source = {
    hub, myUniversityId: 1, scheduler: clock, now: () => clock.now, isHidden: () => false,
    fetchActivity: async (): Promise<UniversityActivity> => {
      askedAt.push(clock.now);
      if (failing) throw new Error('offline');
      second += 1;
      return { second, clicks: 0, attacks: 0, assists: 0 };
    },
  };
  function Probe() { useUniversityActivityFeed(source, 7); return null; }
  const view = render(React.createElement(Probe));
  await clock.advance(30_000);
  const gaps = askedAt.slice(1).map((at, i) => at - askedAt[i]);
  assert.deepEqual(gaps.slice(0, 4), [2000, 4000, 8000, ACTIVITY_MAX_BACKOFF_MS]);
  failing = false;
  const before = askedAt.length;
  await clock.advance(ACTIVITY_MAX_BACKOFF_MS + 3 * ACTIVITY_POLL_MS);
  const recovered = askedAt.slice(before);
  assert.ok(recovered.length >= 3, `회복 뒤 1초마다 물어야 합니다: ${recovered.length}`);
  assert.deepEqual(recovered.slice(1).map((at, i) => at - recovered[i]), recovered.slice(1).map(() => ACTIVITY_POLL_MS));
  view.unmount();
});

test('클릭·공격·지원 파티클은 종류별로 따로 만들고, 공격·지원은 1초에 10개까지, 넘는 수는 +N으로 합친다', async () => {
  const clock = new Clock();
  const feed = createFeed<SchoolActivity>();
  const noOwnClicks = () => 0;
  let particles: Array<{ kind: string; weight: number }> = [];
  function Probe() {
    const result = useOtherClicks({ getMyClicksSinceLastTick: noOwnClicks, scheduler: clock, feed });
    particles = result.particles;
    return React.createElement('output', null, JSON.stringify(result.activity));
  }
  render(React.createElement(Probe));
  await act(async () => { feed.publish({ clicks: 100, attacks: 25, assists: 3 }); });
  assert.equal(screen.getByRole('status').textContent, JSON.stringify({ clicks: 100, attacks: 25, assists: 3 }));
  await clock.advance(1000);
  const byKind = (kind: string) => particles.filter((particle) => particle.kind === kind);
  // A busy second of clicks does not crowd out the few attacks and assists.
  assert.deepEqual([byKind('click').length, byKind('attack').length, byKind('assist').length], [20, 10, 3]);
  assert.deepEqual([100, 25, 3], ['click', 'attack', 'assist'].map((kind) => byKind(kind).reduce((sum, p) => sum + p.weight, 0)));
  await clock.advance(1200);
  assert.equal(particles.length, 0);
});

test('파티클은 종류마다 다른 클래스로 그리고, 클릭만 시간대 색을 쓴다', () => {
  const particle = (id: number, kind: 'click' | 'attack' | 'assist', weight = 1) => ({ id, kind, weight, delayMs: 0, xPct: 50, yPct: 50 });
  render(React.createElement(OtherActivityParticles, {
    particles: [particle(1, 'click'), particle(2, 'attack', 3), particle(3, 'assist')], lifetimeMs: 1200, palette: 'sky', reducedMotion: false,
  }));
  const [click, attack, assist] = screen.getAllByTestId('other-click-particle');
  assert.deepEqual([click.dataset.kind, attack.dataset.kind, assist.dataset.kind], ['click', 'attack', 'assist']);
  assert.ok(click.classList.contains('other-click-animate') && click.querySelector('.other-click-mark'));
  assert.ok(attack.classList.contains('other-attack-animate') && attack.querySelector('.other-attack-mark'));
  assert.ok(assist.classList.contains('other-assist-animate') && assist.querySelector('.other-assist-mark'));
  assert.equal(click.style.color, 'rgb(125, 211, 252)');
  assert.equal(attack.style.color, '');
  assert.equal(attack.textContent, '+3');
});

test('말풍선 요약은 0인 공격·지원을 뺀다', () => {
  const label = (n: number) => `클릭 ${n}회`;
  assert.equal(activitySummary({ clicks: 4, attacks: 0, assists: 0 }, label), '클릭 4회');
  assert.equal(activitySummary({ clicks: 0, attacks: 2, assists: 5 }, label), '클릭 0회 · 공격받음 2회 · 지원받음 5회');
});

const ME: MyInfo = {
  id: 10, nickname: '파티클학생', email: 'test@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 1, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '컴퓨터소프트웨어학부' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 100 }, totalContribScore: 10, totalAttackScore: 0,
};
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status: 200 });

test('API 모드 캠퍼스는 클릭하지 않아도 폴링한 학교 활동으로 파티클과 안내를 띄우고 목업 소스를 호출하지 않는다', async () => {
  const asked: string[] = [];
  let second = 1000;
  const client = createApiClient({ fetch: (async (input) => {
    const path = new URL(String(input), 'http://localhost').pathname;
    if (path.endsWith('/activity')) { asked.push(path); second += 1; return ok({ second, clicks: 5, attacks: 2, assists: 0 }); }
    throw new Error(`Unexpected request: ${path}`);
  }) as typeof fetch });
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  let polled = 0;
  const mock: OtherClicksSource = { exchange: () => { polled += 1; return { otherClicks: 99 }; } };
  const source = { hub: activityHubFor(client), fetchActivity: (id: number) => getUniversityActivity(client, id), myUniversityId: 1 };
  render(React.createElement(ApiProvider, { client, queryClient, children: React.createElement(MockGameProvider, null,
    React.createElement(UniversityActivityContext.Provider, { value: source },
      React.createElement(CampusView, { onTap: () => null, clickPower: 1, basePower: 1, activeStudents: 37, otherClicksSource: mock }))) }));
  assert.ok(screen.getByText('같은 학교의 직전 1초 클릭·공격·지원이 함께 보입니다.'));
  assertAbsent(screen.queryByText(/\[시연\]/), 'API 모드에는 목업 시연 문구가 없어야 합니다.');
  await waitFor(() => assert.ok(screen.getByText('직전 1초 다른 학생 약 5회 클릭 · 공격받음 2회')), { timeout: 2500 });
  await waitFor(() => assert.ok(screen.getAllByTestId('other-click-particle').some((el) => el.dataset.kind === 'attack')), { timeout: 1500 });
  assert.ok(asked.every((path) => path === '/api/v1/universities/1/activity'), asked.join());
  assert.equal(polled, 0);
});

test('클릭 응답에 second가 있으면 그 값을 쓰고 그동안 폴링하지 않으며, 클릭 수에서만 내 이전 클릭을 뺀다', async () => {
  const sent: ClickRequest[] = [];
  const asked: number[] = [];
  const lastSecond = [7, 12];
  // My school received 3 attacks, then 2 assists: other schools' doing, so nothing of mine is taken out of them.
  const attacked = [3, 0];
  const assisted = [0, 2];
  const client = createApiClient({ fetch: (async (input, init) => {
    const path = new URL(String(input), 'http://localhost').pathname;
    if (path.endsWith('/users/me')) return ok(ME);
    // The poll's answer is about an older second, so it never replaces an answer's report.
    if (path.endsWith('/activity')) { asked.push(Date.now()); return ok({ second: 1, clicks: 0, attacks: 0, assists: 0 }); }
    if (path.endsWith('/clicks')) {
      const body = JSON.parse(String(init?.body)) as ClickRequest;
      sent.push(body);
      const total = sent.reduce((sum, request) => sum + request.clickCount, 0);
      return ok({
        acceptedClicks: body.clickCount, earnedScore: body.clickCount, multiplier: 1, myContribScore: total,
        university: { id: 1, totalScore: 100 + total, rank: 1, logoUrl: 'tempUrl' }, department: { id: 7, totalScore: 20 + total },
        second: 100 + sent.length, schoolClicksLastSecond: lastSecond[sent.length - 1] ?? 0, libraryBonus: false, criticalIndex: [], damageRateIndex: [],
        schoolAttacksLastSecond: attacked[sent.length - 1] ?? 0, schoolAssistsLastSecond: assisted[sent.length - 1] ?? 0,
      } satisfies ClickResponse);
    }
    if (path.endsWith('/auth/reissue')) return ok({ accessToken: 'member-a', expiresInSeconds: 3600 });
    throw new Error(`Unexpected request: ${path}`);
  }) as typeof fetch });
  client.session.signIn('member-a');
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  const source = { hub: activityHubFor(client), fetchActivity: (id: number) => getUniversityActivity(client, id), myUniversityId: 1 };
  function Campus() {
    const clicks = useApiCampusClicks();
    const game = useMockGame();
    return React.createElement(UniversityActivityContext.Provider, { value: source },
      React.createElement(ApiPlayerSync, { getPendingScore: clicks.getPendingScore }),
      React.createElement('output', { 'data-testid': 'contribution' }, String(game.state.contribution)),
      React.createElement(CampusView, { onTap: (_x: number, _y: number, isCrit: boolean) => clicks.tap(isCrit), clickPower: 1, basePower: 1, activeStudents: 37 }));
  }
  render(React.createElement(ApiProvider, { client, queryClient, children: React.createElement(MockGameProvider, null, React.createElement(Campus)) }));
  // ApiPlayerSync must load the member before taps count.
  await waitFor(() => assert.equal(screen.getByTestId('contribution').textContent, '10'));
  const area = screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
  fireEvent.click(area);
  fireEvent.click(area);
  await waitFor(() => assert.ok(screen.getByText('직전 1초 다른 학생 약 7회 클릭 · 공격받음 3회')), { timeout: 2500 });
  const answeredAt = Date.now();
  assert.deepEqual(sent.map((request) => request.clickCount), [2]);
  // The next batch goes about 1s after this click, so the first batch's 2 own clicks are subtracted from 12.
  fireEvent.click(area);
  await waitFor(() => assert.ok(screen.getByText('직전 1초 다른 학생 약 10회 클릭 · 지원받음 2회')), { timeout: 2500 });
  assert.deepEqual(sent.map((request) => request.clickCount), [2, 1]);
  // Within a second of each answer the poll does not ask.
  assert.ok(asked.every((at) => at < answeredAt || at >= answeredAt + ACTIVITY_FRESH_MS - 50), asked.map((at) => at - answeredAt).join());
});

test('CampusView는 활동 소스가 없을 때만 목업을 폴링하고 App은 API 모드에서 활동 소스를 Context로 내린다', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(app, /<UniversityActivityContext\.Provider value=\{activitySource\}>/);
  assert.match(app, /api: useApiUniversityActivitySource,\s*mock: \(\) => null/);
  const view = readFileSync(new URL('../src/components/CampusView.tsx', import.meta.url), 'utf8');
  assert.match(view, /useOtherClicks\(\{[^}]*feed: otherClicksFeed, mockPolling: !activitySource \}\)/);
  assert.doesNotMatch(view, /useMockOtherClicks\(/);
});
