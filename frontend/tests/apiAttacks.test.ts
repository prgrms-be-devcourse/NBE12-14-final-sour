// SCRUM-168: real API-mode visit component + root-lifetime attack queue, with deterministic backend responses.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';
import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds';
import type { MyInfo } from '../src/api/users';
import type { UniversityDetail } from '../src/api/universities';
import type { AttackRequest, AttackResponse } from '../src/api/attacks';
import type { AssistResponse } from '../src/api/assists';
import { installDialogStub } from './helpers/dialogStub';
import { assertAbsent } from './helpers/domAssert';

// Vite normally supplies import.meta.env; select API mode explicitly in this isolated Node worker.
const assets = registerHooks({ load(url, context, nextLoad) {
  if (/\/api\/mode\.ts$/.test(url)) return { format: 'module', source: 'export const API_MODE = "api"; export function selectByMode(impl) { return impl.api; }', shortCircuit: true };
  if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: 'export default "/asset";', shortCircuit: true };
  return nextLoad(url, context);
} });
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
installDialogStub(dom.window);
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
const { render, screen, cleanup, waitFor, fireEvent, act } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { userKeys } = await import('../src/api/users');
const { universityKeys } = await import('../src/api/universities');
const { allianceKeys } = await import('../src/api/alliances');
const { MockGameProvider } = await import('../src/hooks/useMockGame');
const { useApiCampusAttacks } = await import('../src/hooks/useCampusAttacks');
const { VisitingScreen1Phone } = await import('../src/components/VisitingScreen1Phone');
const { AttackNotice } = await import('../src/components/VisitAttackBar');
const { soundManager } = await import('../src/utils/sound');
soundManager.enabled = false;
const clients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => { cleanup(); for (const client of clients.splice(0)) client.clear(); });
after(() => { assets.deregister(); dom.window.close(); });

const ME: MyInfo = {
  id: 10, nickname: '공격학생', email: 'attack@hanyang.ac.kr', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: SCHOOL_ID.hanyang, name: '한양대학교', logoUrl: null }, department: { id: 7, name: '국악과' },
  clicker: { level: 1, clickPower: 10, nextLevelRequiredScore: 100 }, totalContribScore: 100, totalAttackScore: 0,
};
const DETAIL: UniversityDetail = { id: SCHOOL_ID.pusan, name: '부산대학교', logoUrl: null, totalScore: 1000, rank: 2, memberCount: 42,
  topDepartments: [], topContributors: [], isMyUniversity: false, isMyAlly: false, rankChange: null };
const ANSWER: AttackResponse = { acceptedClicks: 4, damage: 12, multiplier: 1, isRival: false,
  targetUniversity: { id: DETAIL.id, name: DETAIL.name, totalScore: 988, rank: 3 }, myAttackScore: 12, criticalIndex: [], damageRateIndex: [] };
const ASSIST: AssistResponse = { acceptedClicks: 4, assistScore: 12, targetUniversity: { id: DETAIL.id, name: DETAIL.name, totalScore: 1012, rank: 1 },
  myContribScore: 140, criticalIndex: [], damageRateIndex: [] };
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'SUCCESS', message: '성공', data }), { status: 200 });
const fail = (status: number, code: string) => new Response(JSON.stringify({ code, message: code, data: null }), { status });
let attacks: ReturnType<typeof useApiCampusAttacks>;
function mount({ signedIn = true, respond, busy = false, beforeRead, allies = [] }: {
  signedIn?: boolean; busy?: boolean; respond?: (body: AttackRequest, n: number) => Response | Promise<Response>;
  beforeRead?: (path: string) => Response | Promise<Response> | undefined;
  /** Schools the server reports as the viewer's current alliance (SCRUM-187). */
  allies?: number[];
} = {}) {
  const calls: Array<{ body: AttackRequest; auth: string | null }> = [];
  const assistCalls: Array<{ body: AttackRequest; auth: string | null }> = [];
  let locked = false;
  let unlocks = 0;
  const begin = () => {
    if (busy || locked) return null;
    locked = true;
    return () => { locked = false; unlocks += 1; };
  };
  const client = createApiClient({ fetch: (async (input, init) => {
    const path = new URL(String(input), 'http://localhost').pathname;
    if (path.endsWith('/attacks')) {
      const body = JSON.parse(String(init?.body)) as AttackRequest;
      calls.push({ body, auth: new Headers(init?.headers).get('Authorization') });
      return respond ? respond(body, calls.length) : ok(ANSWER);
    }
    if (path.endsWith('/assists')) {
      const body = JSON.parse(String(init?.body)) as AttackRequest;
      assistCalls.push({ body, auth: new Headers(init?.headers).get('Authorization') });
      return respond ? respond(body, assistCalls.length) : ok(ASSIST);
    }
    const custom = beforeRead?.(path);
    if (custom) return custom;
    if (path.endsWith('/users/me')) return ok(ME);
    if (/\/universities\/\d+$/.test(path)) {
      const id = Number(path.split('/').at(-1));
      return ok({ ...DETAIL, id, isMyUniversity: signedIn && id === ME.university.id, isMyAlly: signedIn && allies.includes(id) });
    }
    throw new Error(`Unexpected path: ${path}`);
  }) as typeof fetch });
  const queryClient = createQueryClient(); clients.push(queryClient);
  if (signedIn) { client.session.signIn('member-a'); queryClient.setQueryData(userKeys.me, ME); }
  function Root({ targetId, visible }: { targetId: SchoolId; visible: boolean }) {
    attacks = useApiCampusAttacks(begin);
    return React.createElement(React.Fragment, null,
      React.createElement(AttackNotice, { attacks }),
      visible ? React.createElement(VisitingScreen1Phone, { schoolId: targetId, attacks }) : null);
  }
  const tree = (targetId: SchoolId, visible: boolean) => React.createElement(MockGameProvider, null,
    React.createElement(ApiProvider, { client, queryClient, children: React.createElement(Root, { targetId, visible }) }));
  const view = render(tree(SCHOOL_ID.pusan, true));
  return { calls, assistCalls, client, queryClient, locked: () => locked, unlocks: () => unlocks,
    navigate: (id: SchoolId, visible = true) => view.rerender(tree(id, visible)), setBusy: (value: boolean) => { busy = value; } };
}
const attackButton = () => screen.getByRole('button', { name: /캠퍼스 공격/ });
const settle = () => waitFor(() => assert.match(screen.getByTestId('attack-result').textContent ?? '', /피해/), { timeout: 2500 });

test('캠퍼스 클릭은 1초 배치·대상 ID·Bearer를 보내고, 확정 점수와 순위를 HUD·정보 시트에 반영한다', async () => {
  const f = mount();
  await waitFor(() => assert.ok(attackButton()));
  const button = attackButton();
  assert.equal(button.tagName, 'BUTTON', '키보드 기본 동작을 가진 native button');
  for (let i = 0; i < 4; i++) fireEvent.click(button);
  assert.equal(f.calls.length, 0, '클릭마다 요청하지 않는다');
  assert.equal(f.locked(), true, '내 학교 클릭·강화·구매 큐를 잠근다');
  assert.equal(screen.getByTestId('visit-score').textContent, '1,000점', '서버 응답 전 피해를 추측하지 않는다');
  await settle();
  assert.equal(f.calls.length, 1);
  assert.deepEqual(f.calls[0], { auth: 'Bearer member-a', body: { submissionId: f.calls[0].body.submissionId, clickCount: 4, targetUniversityId: SCHOOL_ID.pusan } });
  assert.ok(f.calls[0].body.submissionId);
  assert.match(screen.getByTestId('attack-result').textContent ?? '', /일반 공격 ×1.0 · 4회 · 피해 12/);
  assert.equal(screen.getByTestId('visit-score').textContent, '988점');
  assert.equal(screen.getByTestId('visit-rank').textContent, '3위');
  assert.deepEqual(f.queryClient.getQueryData<MyInfo>(userKeys.me), { ...ME, totalAttackScore: 12 }, '기여도·클리커·프로필은 보존');
  assert.equal(f.locked(), false);
  fireEvent.click(screen.getByTestId('visit-info-button'));
  assert.match(screen.getByTestId('visit-info-score').textContent ?? '', /988/);
  assert.equal(f.calls.length, 1, '정보 버튼은 공격으로 전파되지 않는다');
});

test('20회 넘는 공격은 같은 대상으로 20/5로 직렬 전송하고 서로 다른 제출 ID를 쓴다', async () => {
  const f = mount({ respond: (body) => ok({ ...ANSWER, acceptedClicks: body.clickCount }) });
  await waitFor(() => assert.ok(attackButton()));
  for (let i = 0; i < 25; i++) fireEvent.click(attackButton());
  await waitFor(() => assert.equal(attacks.pendingClicks, 0), { timeout: 3500 });
  assert.deepEqual(f.calls.map(({ body }) => body.clickCount), [20, 5]);
  assert.ok(f.calls.every(({ body }) => body.targetUniversityId === SCHOOL_ID.pusan));
  assert.notEqual(f.calls[0].body.submissionId, f.calls[1].body.submissionId);
  assert.equal(f.unlocks(), 1, '전체 큐가 빈 뒤에만 잠금을 푼다');
});

test('공격 전에 시작한 학교·내 정보 조회의 늦은 응답이 확정 점수를 되돌리지 않는다', async () => {
  let blockReads = false;
  const replies: Array<() => void> = [];
  const f = mount({ beforeRead: (path) => blockReads
    ? new Promise<Response>((resolve) => replies.push(() => resolve(ok(path.endsWith('/users/me') ? ME : DETAIL))))
    : undefined });
  await waitFor(() => assert.ok(attackButton()));
  blockReads = true;
  let reads!: Promise<unknown>;
  act(() => {
    reads = Promise.all([
      f.queryClient.refetchQueries({ queryKey: universityKeys.detail(SCHOOL_ID.pusan, ME.id), exact: true }),
      f.queryClient.refetchQueries({ queryKey: userKeys.me, exact: true }),
    ]);
  });
  await waitFor(() => assert.equal(replies.length, 2));
  for (let i = 0; i < 4; i++) fireEvent.click(attackButton());
  await settle();
  await act(async () => { for (const reply of replies) reply(); await reads; });
  assert.equal(screen.getByTestId('visit-score').textContent, '988점');
  assert.equal(f.queryClient.getQueryData<MyInfo>(userKeys.me)?.totalAttackScore, 12);
});

test('라이벌 ×1.1과 실제 0 피해도 서버 응답 그대로 표시한다', async () => {
  mount({ respond: () => ok({ ...ANSWER, damage: 0, isRival: true, multiplier: 1.1, targetUniversity: { ...ANSWER.targetUniversity, totalScore: 0 } }) });
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  await settle();
  assert.match(screen.getByTestId('attack-result').textContent ?? '', /라이벌 공격 ×1.1.*피해 0/);
  assert.equal(screen.getByTestId('visit-score').textContent, '0점');
});

test('로그인 전에는 관전만, 자기 학교에는 공격 요청을 보내지 않는다', async () => {
  const guest = mount({ signedIn: false });
  await waitFor(() => assert.ok(screen.getByTestId('spectator-bar')));
  assertAbsent(screen.queryByRole('button', { name: /캠퍼스 공격/ }));
  act(() => { assert.equal(attacks.tap(SCHOOL_ID.pusan), null); });
  assert.equal(guest.calls.length, 0);
  cleanup();
  const self = mount();
  await waitFor(() => assert.ok(attackButton()));
  act(() => { assert.equal(attacks.tap(SCHOOL_ID.hanyang), null); });
  self.navigate(SCHOOL_ID.hanyang);
  await waitFor(() => assertAbsent(screen.queryByRole('button', { name: /캠퍼스 공격/ })));
  assert.equal(self.calls.length, 0);
});

test('내 학교 작업이 대기 중이면 공격을 모으지 않고 잠금 해제 뒤에만 받는다', async () => {
  const f = mount({ busy: true });
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  assert.equal(attacks.pendingClicks, 0);
  assert.match(screen.getByTestId('attack-notice').textContent ?? '', /내 학교 클릭/);
  f.setBusy(false);
  fireEvent.click(attackButton());
  await settle();
  assert.equal(f.calls.length, 1);
});

test('화면을 떠나도 원래 학교에 보내고, 미확정 공격이 있는 동안 새 학교로 대상을 바꾸지 않는다', async () => {
  const f = mount();
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  f.navigate(SCHOOL_ID.snu);
  await waitFor(() => assert.equal((attackButton() as HTMLButtonElement).disabled, true));
  act(() => { assert.equal(attacks.tap(SCHOOL_ID.snu), null); });
  f.navigate(SCHOOL_ID.snu, false);
  await waitFor(() => assert.equal(attacks.result?.kind === 'attack' && attacks.result.damage, 12), { timeout: 2500 });
  assert.equal(f.calls[0].body.targetUniversityId, SCHOOL_ID.pusan);
  const cached = f.queryClient.getQueryData<UniversityDetail>(universityKeys.detail(SCHOOL_ID.pusan, ME.id));
  assert.equal(cached?.totalScore, 988);
  assert.equal(f.unlocks(), 1);
});

test('세션 종료는 전송 전 큐와 늦은 응답을 폐기하고 잠금을 푼다', async () => {
  const before = mount();
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  act(() => before.client.session.signOut());
  assert.equal(before.locked(), false);
  await act(() => new Promise((resolve) => setTimeout(resolve, 1100)));
  assert.equal(before.calls.length, 0);
  cleanup();
  let resolve!: (response: Response) => void;
  const late = mount({ respond: () => new Promise<Response>((done) => { resolve = done; }) });
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  await waitFor(() => assert.equal(late.calls.length, 1), { timeout: 2500 });
  act(() => late.client.session.signOut());
  await act(async () => { resolve(ok(ANSWER)); });
  assert.equal(attacks.result, null);
  assert.equal(late.queryClient.getQueryData<MyInfo>(userKeys.me)?.totalAttackScore, 0);
  assert.equal(late.locked(), false);
});

test('HTTP 전송 전 지연 중 계정이 바뀌어도 이전 공격을 새 토큰으로 보내지 않는다', async () => {
  const f = mount();
  await waitFor(() => assert.ok(attackButton()));
  f.client.faults.setMode('delay');
  fireEvent.click(attackButton());
  // The 1s batch timer has fired, but the client's 1.5s injected delay has not finished.
  await act(() => new Promise((resolve) => setTimeout(resolve, 1100)));
  assert.equal(f.calls.length, 0);
  act(() => {
    f.client.session.signOut();
    f.client.session.signIn('member-b');
    f.queryClient.setQueryData(userKeys.me, { ...ME, id: 11 });
  });
  await act(() => new Promise((resolve) => setTimeout(resolve, 1600)));
  assert.equal(f.calls.length, 0, '이전 회원의 공격은 새 회원의 Bearer로 전송되면 안 된다');
  assert.equal(f.locked(), false);
});

test('429는 자동 재전송하지 않고 명시적으로 거절 배치만 제외한다', async () => {
  const f = mount({ respond: () => fail(429, 'CLICK_TOO_FREQUENT') });
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  await waitFor(() => assert.equal(attacks.blocked, true), { timeout: 2500 });
  assert.equal(f.calls.length, 1);
  assert.equal(screen.getByTestId('visit-score').textContent, '1,000점');
  assert.equal((attackButton() as HTMLButtonElement).disabled, true);
  fireEvent.click(screen.getByRole('button', { name: '거절된 배치를 제외하고 공격 재개' }));
  assert.equal(attacks.pendingClicks, 0);
  assert.equal(f.locked(), false);
});

test('봇 확인 완료 뒤 같은 제출 ID와 대상으로 재개한다', async () => {
  const f = mount({ respond: (_body, n) => n === 1 ? fail(403, 'ABUSE_VERIFICATION_REQUIRED') : ok(ANSWER) });
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  await waitFor(() => assert.ok(attacks.botCheck?.open), { timeout: 2500 });
  assert.equal(f.calls.length, 1);
  // ApiBotCheckDialog has its own server-verification tests; only its successful callback resumes this queue.
  act(() => attacks.botCheck!.onVerified());
  await settle();
  assert.equal(f.calls.length, 2);
  assert.deepEqual(f.calls[0].body, f.calls[1].body);
});

test('응답 유실 뒤 중복 제출 409는 새 ID로 다시 공격하지 않고 보류한다', async () => {
  const f = mount({ respond: (_body, n) => { if (n === 1) throw new TypeError('response lost'); return fail(409, 'CLICK_DUPLICATE_SUBMISSION'); } });
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  await waitFor(() => assert.equal(attacks.blocked, true), { timeout: 3500 });
  assert.equal(f.calls.length, 2);
  assert.deepEqual(f.calls[0].body, f.calls[1].body);
  assert.equal(attacks.retryLimited, null);
  assert.match(screen.getByTestId('attack-notice').textContent ?? '', /미확정 공격 1회/);
  assert.equal(screen.getByTestId('visit-score').textContent, '1,000점');
});

test('계약 센서: 공격 경로·필드·자기 학교 거절·공유 한도·응답 배율은 백엔드와 일치한다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/click/', import.meta.url);
  const request = readFileSync(new URL('dto/AttackRequest.java', root), 'utf8');
  for (const field of ['submissionId', 'clickCount', 'targetUniversityId']) assert.match(request, new RegExp(`\\b${field}\\b`));
  assert.match(request, /@Max\(value = 20/);
  const response = readFileSync(new URL('dto/AttackResponse.java', root), 'utf8');
  for (const field of ['acceptedClicks', 'damage', 'multiplier', 'isRival', 'targetUniversity', 'myAttackScore']) assert.match(response, new RegExp(`\\b${field}\\b`));
  assert.match(response, /isRival \? 1\.1 : 1\.0/);
  const controller = readFileSync(new URL('controller/AttackController.java', root), 'utf8');
  assert.match(controller, /@RequestMapping\("\/api\/v1\/attacks"\)/);
  const service = readFileSync(new URL('service/AttackService.java', root), 'utf8');
  assert.match(service, /ATTACK_OWN_UNIVERSITY/);
  assert.match(service, /clickActionGuard.verifyAndGetBaseScore/);
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  assert.match(app, /useCampusAttacks\(campusClicks.beginUpgrade\)/);
  assert.match(app, /attacks=\{campusAttacks\}/);
});

// SCRUM-187: an ally school (server isMyAlly) is assisted through /assists instead of attacked.
const assistButton = () => screen.getByRole('button', { name: /캠퍼스 지원/ });

test('연합 학교는 지원 버튼으로 바뀌어 /assists로 보내고, 서버 점수와 내 기여도를 반영한다', async () => {
  const f = mount({ allies: [SCHOOL_ID.pusan] });
  await waitFor(() => assert.ok(assistButton()));
  assertAbsent(screen.queryByRole('button', { name: /캠퍼스 공격/ }), '연합 학교에는 공격 버튼이 없다');
  assert.equal(screen.getByTestId('visit-attack-bar').dataset.mode, 'assist');
  for (let i = 0; i < 4; i++) fireEvent.click(assistButton());
  assert.equal(f.locked(), true, '공격과 같은 잠금을 쓴다');
  assert.equal(screen.getByTestId('visit-score').textContent, '1,000점', '서버 응답 전 점수를 추측하지 않는다');
  await waitFor(() => assert.match(screen.getByTestId('attack-result').textContent ?? '', /연합 지원/), { timeout: 2500 });
  assert.equal(f.calls.length, 0, '공격 API는 부르지 않는다');
  assert.deepEqual(f.assistCalls.map(({ auth, body }) => ({ auth, clickCount: body.clickCount, target: body.targetUniversityId })),
    [{ auth: 'Bearer member-a', clickCount: 4, target: SCHOOL_ID.pusan }]);
  assert.match(screen.getByTestId('attack-result').textContent ?? '', /연합 지원 · 4회 · 학교 \+12점 · 상대 1,012점 · 내 기여도 140점/);
  assert.equal(screen.getByTestId('visit-score').textContent, '1,012점');
  assert.equal(screen.getByTestId('visit-rank').textContent, '1위');
  assert.deepEqual(f.queryClient.getQueryData<MyInfo>(userKeys.me), { ...ME, totalContribScore: 140 }, '공격 점수는 그대로');
  assert.equal(f.locked(), false);
});

test('공격 점수 복구 중에는 자동 재전송과 미확정 횟수를 안내한다', async () => {
  mount({ respond: () => fail(503, 'RESTORE_SCORE_RESTORING') });
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  await waitFor(() => assert.match(screen.getByRole('alert').textContent ?? '', /점수 복구 중.*자동.*미확정.*1회/), { timeout: 2500 });
});

test('연합 해제 뒤 첫 지원 거절은 대기분을 취소하고 잠금을 풀어 새 입력을 받는다', async () => {
  const allies = [SCHOOL_ID.pusan];
  let detailReads = 0;
  const f = mount({ allies, beforeRead: (path) => {
    if (path.endsWith(`/universities/${SCHOOL_ID.pusan}`)) detailReads++;
    return undefined;
  }, respond: () => {
    allies.length = 0;
    return fail(400, 'ASSIST_NOT_ALLY');
  } });
  await waitFor(() => assert.ok(assistButton()));
  f.queryClient.setQueryData(allianceKeys.mine(ME.id), { marker: true });
  for (let i = 0; i < 25; i++) fireEvent.click(assistButton());
  await waitFor(() => assert.equal(attacks.pendingClicks, 0), { timeout: 2500 });
  assert.equal(f.locked(), false);
  assert.equal(f.assistCalls.length, 1);
  assert.match(screen.getByRole('alert').textContent ?? '', /지원 25회.*취소/);
  await waitFor(() => assert.ok(attackButton()));
  assert.ok(detailReads >= 2);
  assert.equal(f.queryClient.getQueryState(allianceKeys.mine(ME.id))?.isInvalidated, true);
  act(() => { assert.ok(attacks.tap(SCHOOL_ID.pusan, 'attack')); });
});

test('지원 응답 유실 뒤 연합 거절은 미확정 배치와 대기분을 보존한다', async () => {
  const f = mount({ allies: [SCHOOL_ID.pusan], respond: (_body, n) => {
    if (n === 1) throw new TypeError('response lost');
    return fail(400, 'ASSIST_NOT_ALLY');
  } });
  await waitFor(() => assert.ok(assistButton()));
  for (let i = 0; i < 25; i++) fireEvent.click(assistButton());
  await waitFor(() => assert.equal(attacks.blocked, true), { timeout: 3500 });
  assert.equal(f.assistCalls.length, 2);
  assert.deepEqual(f.assistCalls[0].body, f.assistCalls[1].body);
  assert.equal(attacks.pendingClicks, 25);
  assert.equal(f.locked(), true);
  assert.doesNotMatch(screen.getByRole('alert').textContent ?? '', /취소했어요/);
});

test('동맹 공격 거절은 상세를 갱신하고 대기 공격을 취소하되, 지원은 사용자가 다시 눌러야 보낸다', async () => {
  const allies: number[] = [];
  let detailReads = 0;
  const f = mount({ allies, beforeRead: (path) => {
    if (path.endsWith(`/universities/${SCHOOL_ID.pusan}`)) detailReads += 1;
    return undefined;
  }, respond: () => {
    if (f.assistCalls.length) return ok(ASSIST);
    allies.push(SCHOOL_ID.pusan);
    return fail(400, 'ATTACK_ALLY_UNIVERSITY');
  } });
  await waitFor(() => assert.ok(attackButton()));
  for (let i = 0; i < 25; i++) fireEvent.click(attackButton());
  await waitFor(() => assert.ok(assistButton()), { timeout: 2500 });
  assert.ok(detailReads >= 2, '5초 주기 조회를 기다리지 않고 상세를 재조회한다');
  assert.match(screen.getByRole('alert').textContent ?? '', /같은 연합.*공격할 수 없/);
  assert.match(screen.getByRole('alert').textContent ?? '', /25회.*취소/);
  assert.equal(attacks.pendingClicks, 0);
  assert.equal(f.locked(), false);
  assert.equal(f.calls.length, 1, '대기 중이던 다음 공격 묶음도 보내지 않는다');
  assert.equal(f.assistCalls.length, 0, '거절된 공격을 지원으로 자동 전송하지 않는다');
  assert.equal(screen.getByTestId('visit-score').textContent, '1,000점');
  assert.equal(f.queryClient.getQueryData<MyInfo>(userKeys.me)?.totalAttackScore, 0);
  fireEvent.click(assistButton());
  await waitFor(() => assert.equal(f.assistCalls.length, 1), { timeout: 2500 });
  assert.equal(f.assistCalls[0].body.clickCount, 1, '이전 25회가 아니라 새로 누른 지원만 보낸다');
});

test('응답 유실 뒤 동맹 거절은 상세만 갱신하고 이전 공격의 처리 여부가 불명확한 큐는 보존한다', async () => {
  const allies: number[] = [];
  const f = mount({ allies, respond: (_body, n) => {
    if (n === 1) throw new TypeError('response lost');
    allies.push(SCHOOL_ID.pusan);
    return fail(400, 'ATTACK_ALLY_UNIVERSITY');
  } });
  await waitFor(() => assert.ok(attackButton()));
  for (let i = 0; i < 25; i++) fireEvent.click(attackButton());
  await waitFor(() => assert.equal(attacks.blocked, true), { timeout: 3500 });
  await waitFor(() => assert.ok(assistButton()));
  assert.equal((assistButton() as HTMLButtonElement).disabled, true);
  assert.deepEqual(f.calls[0].body, f.calls[1].body, '같은 제출을 확인한 것이지 새 공격을 만든 것이 아니다');
  assert.equal(f.calls.length, 2);
  assert.equal(f.assistCalls.length, 0);
  assert.equal(attacks.pendingClicks, 25);
  assert.equal(f.locked(), true);
  assert.equal(attacks.retryLimited, null);
  assert.match(screen.getByRole('alert').textContent ?? '', /미확정 공격 25회.*보류/);
  assert.doesNotMatch(screen.getByRole('alert').textContent ?? '', /취소했어요/);
});

test('연합이 아닌 학교는 그대로 공격하고, 지원 대기 중에는 다른 종류로 바꾸지 않는다', async () => {
  const f = mount({ allies: [SCHOOL_ID.pusan] });
  await waitFor(() => assert.ok(assistButton()));
  fireEvent.click(assistButton());
  act(() => { assert.equal(attacks.tap(SCHOOL_ID.pusan, 'attack'), null, '같은 학교라도 묶음 종류는 하나'); });
  assert.match(screen.getByRole('alert').textContent ?? '', /이전 학교의 지원 전송이 끝난 뒤/);
  await waitFor(() => assert.equal(attacks.pendingClicks, 0), { timeout: 2500 });
  f.navigate(SCHOOL_ID.snu);
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  assert.equal(attacks.pendingKind, 'attack');
  await waitFor(() => assert.equal(attacks.pendingClicks, 0), { timeout: 2500 });
  assert.equal(f.assistCalls.length, 1);
  assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].body.targetUniversityId, SCHOOL_ID.snu);
});

test('계약 센서: 지원 경로·필드·동맹 거절·0.3배·기여도는 백엔드와 일치한다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/click/', import.meta.url);
  const request = readFileSync(new URL('dto/AssistRequest.java', root), 'utf8');
  for (const field of ['submissionId', 'clickCount', 'targetUniversityId']) assert.match(request, new RegExp(`\\b${field}\\b`));
  assert.match(request, /@Max\(value = 20/);
  const response = readFileSync(new URL('dto/AssistResponse.java', root), 'utf8');
  for (const field of ['acceptedClicks', 'assistScore', 'targetUniversity', 'myContribScore']) assert.match(response, new RegExp(`\\b${field}\\b`));
  const controller = readFileSync(new URL('controller/AssistController.java', root), 'utf8');
  assert.match(controller, /@RequestMapping\("\/api\/v1\/assists"\)/);
  const service = readFileSync(new URL('service/AssistService.java', root), 'utf8');
  assert.match(service, /ASSIST_NOT_ALLY/);
  assert.match(service, /clickActionGuard.verifyAndGetBaseScore/);
  const api = readFileSync(new URL('../src/api/assists.ts', import.meta.url), 'utf8');
  assert.match(api, /'\/assists'/);
  const phone = readFileSync(new URL('../src/components/VisitingScreen1Phone.tsx', import.meta.url), 'utf8');
  assert.match(phone, /visit\.isMyAlly \? 'assist' : 'attack'/);
});

// ClickActionGuard: each answer decides the next attack/assist batch's crits and damage rates, as for campus clicks.
const SLOT_RATES = [130, 70, ...Array<number>(18).fill(100)];
const tapNumbers = () => screen.queryAllByTestId('tap-floating-number').map((node) => node.textContent);

test('공격 탭은 응답이 정한 크리티컬·배율로 클릭처럼 데미지를 띄우고, 점수는 응답 전까지 바꾸지 않는다', async () => {
  const f = mount({ respond: () => ok({ ...ANSWER, criticalIndex: [0], damageRateIndex: SLOT_RATES }) });
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  assert.deepEqual(tapNumbers(), ['-3'], '첫 응답 전에는 기본 파워 10 × 0.3');
  await settle();
  fireEvent.click(attackButton());
  fireEvent.click(attackButton());
  assert.deepEqual(tapNumbers().slice(-2), ['-7!', '-2'], '0번 칸 10 × 130% × 2배 × 0.3 = 7.8, 1번 칸 10 × 70% × 0.3 = 2.1');
  assert.equal(screen.getByTestId('visit-score').textContent, '988점', '탭 데미지는 표시만 하고 학교 점수는 응답으로만 바꾼다');
  assert.equal(f.calls.length, 1);
});

test('지원 탭은 + 데미지를 띄우고, 공격으로 받은 칸은 지원에 쓰지 않는다', async () => {
  const f = mount({ allies: [SCHOOL_ID.snu], respond: (body) => body.targetUniversityId === SCHOOL_ID.snu
    ? ok({ ...ASSIST, criticalIndex: [1], damageRateIndex: SLOT_RATES })
    : ok({ ...ANSWER, criticalIndex: [0], damageRateIndex: SLOT_RATES }) });
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  await settle();
  await waitFor(() => assert.equal(attacks.pendingClicks, 0), { timeout: 2500 });
  f.navigate(SCHOOL_ID.snu);
  await waitFor(() => assert.ok(assistButton()));
  fireEvent.click(assistButton());
  assert.deepEqual(tapNumbers().slice(-1), ['+3'], '공격 응답의 0번 크리티컬을 지원에 쓰지 않는다');
  await waitFor(() => assert.equal(f.assistCalls.length, 1), { timeout: 2500 });
  await waitFor(() => assert.equal(attacks.pendingClicks, 0), { timeout: 2500 });
  fireEvent.click(assistButton());
  fireEvent.click(assistButton());
  assert.deepEqual(tapNumbers().slice(-2), ['+3', '+4!'], '지원 응답이 정한 칸: 10 × 130% × 0.3 = 3.9, 10 × 70% × 2배 × 0.3 = 4.2');
});

test('공격 탭은 건물을 좌우로, 주변은 반대로 흔들고, 지원 탭은 클릭처럼 건물만 출렁이며, 거절된 탭은 흔들지 않는다', async () => {
  const proto = dom.window.Element.prototype as unknown as { animate?: unknown };
  const animated: Array<{ el: Element; frames: Keyframe[] }> = [];
  proto.animate = function (this: Element, frames: Keyframe[]) { animated.push({ el: this, frames }); return { cancel() {} }; };
  try {
    const f = mount({ allies: [SCHOOL_ID.snu] });
    await waitFor(() => assert.ok(attackButton()));
    fireEvent.click(attackButton());
    const buildings = animated.filter((a) => a.el.matches('[data-wobble], [data-wobble-lights]'));
    const surroundings = animated.filter((a) => a.el.matches('[data-wobble-back]'));
    assert.ok(buildings.length > 0, '공격 탭에 건물이 흔들린다');
    assert.ok(surroundings.length > 0, '공격 탭에 주변도 흔들린다');
    assert.equal(buildings.length + surroundings.length, animated.length, '하늘·땅·클릭 영역은 흔들지 않는다');
    assert.ok(animated.every((a) => a.el.closest('[data-campus-stage]')));
    assert.equal(buildings[0].frames[1].transform, 'translateX(-2px)', '건물이 왼쪽으로 갈 때');
    assert.equal(surroundings[0].frames[1].transform, 'translateX(2px)', '주변은 오른쪽으로 간다');
    await waitFor(() => assert.equal(attacks.pendingClicks, 0), { timeout: 2500 });

    const afterAttack = animated.length;
    f.navigate(SCHOOL_ID.snu);
    await waitFor(() => assert.ok(assistButton()));
    fireEvent.click(assistButton());
    const assisted = animated.slice(afterAttack);
    assert.ok(assisted.length > 0, '지원 탭에도 흔들린다');
    assert.ok(assisted.every((a) => a.el.matches('[data-wobble], [data-wobble-lights]')), '지원 탭은 건물만 흔들고 주변은 그대로');
    assert.equal(assisted[0].frames[1].transform, 'scale(1.04, 0.95)', '캠퍼스 클릭과 같은 출렁임');
    await waitFor(() => assert.equal(attacks.pendingClicks, 0), { timeout: 2500 });

    const afterAssist = animated.length;
    f.setBusy(true);
    act(() => { f.navigate(SCHOOL_ID.pusan); });
    await waitFor(() => assert.ok(attackButton()));
    fireEvent.click(attackButton());
    assert.equal(animated.length, afterAssist, '내 학교 큐가 바빠 거절된 공격 탭은 흔들지 않는다');
  } finally { delete proto.animate; }
});

test('경쟁 학교 공격은 응답의 isRival을 받은 뒤부터 0.33배로 띄운다', async () => {
  mount({ respond: () => ok({ ...ANSWER, isRival: true, multiplier: 0.33, criticalIndex: [0, 1], damageRateIndex: [130, 130, ...Array<number>(18).fill(100)] }) });
  await waitFor(() => assert.ok(attackButton()));
  fireEvent.click(attackButton());
  assert.deepEqual(tapNumbers(), ['-3'], '경쟁 학교인지 모르는 첫 탭은 0.3배');
  await settle();
  fireEvent.click(attackButton());
  assert.deepEqual(tapNumbers().slice(-1), ['-8!'], '10 × 130% × 2배 × 0.33 = 8.58 (0.3배면 7)');
});
