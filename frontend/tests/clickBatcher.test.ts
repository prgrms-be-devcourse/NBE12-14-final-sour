import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { CLICK_BATCH_WINDOW_MS, MAX_CLICK_BATCH_SIZE, submitClicks, type ClickCoordinates, type ClickRequest, type ClickResponse } from '../src/api/clicks';
import { createApiClient } from '../src/api/client';
import { ApiError } from '../src/api/errors';
import { createClickBatcher, type ClickBatchSnapshot } from '../src/utils/clickBatcher';

const response: ClickResponse = {
  acceptedClicks: 2, earnedScore: 4, multiplier: 1, myContribScore: 14,
  university: { id: 1, totalScore: 104, rank: 3, logoUrl: 'tempUrl' },
  department: { id: 7, totalScore: 24 }, schoolClicksLastSecond: 5, libraryBonus: false,
};
const network = () => new ApiError({ kind: 'network', code: 'CLIENT_NETWORK_ERROR', status: null });

function fixture(send: (request: ClickRequest) => Promise<ClickResponse> = async () => response, getCoordinates?: () => ClickCoordinates | undefined) {
  let time = 0;
  let sequence = 0;
  const tasks = new Map<number, { at: number; run: () => void }>();
  const sent: ClickRequest[] = [];
  const accepted: Array<[ClickResponse, number]> = [];
  const snapshots: ClickBatchSnapshot[] = [];
  const controller = createClickBatcher({
    now: () => time,
    getCoordinates,
    createId: () => `submission-${++sequence}`,
    schedule(run, delay) {
      const id = ++sequence;
      tasks.set(id, { at: time + delay, run });
      return () => { tasks.delete(id); };
    },
    send: async (request) => { sent.push({ ...request }); return send(request); },
    onAccepted: (data, pending) => { accepted.push([data, pending]); },
    onChange: (snapshot) => { snapshots.push(snapshot); },
  });
  async function advance(ms: number) {
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
  }
  return { controller, sent, accepted, snapshots, advance, tasks, suspend: (ms: number) => { time += ms; } };
}

for (const initial of [undefined, { latitude: 37.5, longitude: 127 }]) {
  test(`위치${initial ? '있음' : '없음'}: 새 제출에서만 읽고 네트워크 재시도는 위치 변경·중지와 무관하게 본문을 유지한다`, async () => {
    let coordinates = initial;
    let attempts = 0, reads = 0;
    const f = fixture(async () => { if (++attempts === 1) throw network(); return response; }, () => { reads++; return coordinates; });
    f.controller.enqueue(2);
    await f.advance(1000);
    assert.deepEqual(f.sent[0], { submissionId: f.sent[0].submissionId, clickCount: 1, ...initial });
    coordinates = initial ? undefined : { latitude: 0, longitude: 0 };
    f.controller.enqueue(2);
    await f.advance(1000);
    assert.deepEqual(f.sent[1], f.sent[0], '재시도에 위치를 새로 붙이거나 빼지 않는다');
    assert.equal(reads, 1);
    await f.advance(1000);
    assert.deepEqual(f.sent[2], { submissionId: f.sent[2].submissionId, clickCount: 1, ...coordinates });
    assert.equal(reads, 2);
    assert.notEqual(f.sent[2].submissionId, f.sent[0].submissionId);
    f.controller.dispose();
  });
}

test('봇 확인 뒤 재개도 같은 제출에 처음 위치를 유지한다', async () => {
  let coordinates: ClickCoordinates | undefined = { latitude: 37.5, longitude: 127 };
  let attempts = 0;
  const f = fixture(async () => {
    if (++attempts === 1) throw new ApiError({ kind: 'http', status: 403, code: 'ABUSE_VERIFICATION_REQUIRED' });
    return response;
  }, () => coordinates);
  f.controller.enqueue(2);
  await f.advance(1000);
  coordinates = undefined;
  assert.equal(f.controller.resumeAfterVerification(), true);
  await f.advance(1000);
  assert.deepEqual(f.sent[1], f.sent[0]);
  assert.equal(f.sent[1].latitude, 37.5);
  f.controller.dispose();
});

test('idle 잠금은 같은 프레임의 클릭·중복 강화를 막고 이전 release가 다음 잠금을 풀지 못한다', () => {
  const f = fixture();
  const release = f.controller.acquireIdleLock();
  assert.ok(release);
  assert.equal(f.controller.getSnapshot().locked, true);
  assert.equal(f.controller.enqueue(1), false);
  assert.equal(f.controller.acquireIdleLock(), null);
  release();
  const releaseNext = f.controller.acquireIdleLock();
  assert.ok(releaseNext);
  release();
  assert.equal(f.controller.getSnapshot().locked, true, '이전 release는 멱등이며 새 잠금을 건드리지 않는다');
  releaseNext();
  assert.equal(f.controller.enqueue(1), true);
  f.controller.dispose();
});

test('수집·전송·오류 보류·폐기 상태에서는 강화용 잠금을 얻을 수 없다', async () => {
  let resolve!: (value: ClickResponse) => void;
  const f = fixture(() => new Promise((done) => { resolve = done; }));
  f.controller.enqueue(2);
  assert.equal(f.controller.acquireIdleLock(), null);
  await f.advance(1000);
  assert.equal(f.controller.acquireIdleLock(), null);
  resolve(response);
  await f.advance(0);
  const release = f.controller.acquireIdleLock();
  assert.ok(release);
  f.controller.dispose();
  release();
  assert.equal(f.controller.acquireIdleLock(), null);
  const paused = fixture(async () => { throw new ApiError({ kind: 'http', status: 403, code: 'ABUSE_VERIFICATION_REQUIRED' }); });
  paused.controller.enqueue(1);
  await paused.advance(1000);
  assert.equal(paused.controller.acquireIdleLock(), null);
  assert.equal(paused.controller.getSnapshot().pendingClicks, 1);
  paused.controller.dispose();
});

test('첫 클릭부터 1초 모으며 연속 클릭은 타이머를 미루지 않는다; 빈 배치는 보내지 않는다', async () => {
  const f = fixture();
  await f.advance(5000);
  assert.equal(f.sent.length, 0);
  f.controller.enqueue(2);
  await f.advance(999);
  f.controller.enqueue(2);
  assert.equal(f.sent.length, 0);
  await f.advance(1);
  assert.equal(f.sent.length, 1);
  assert.equal(f.sent[0].clickCount, 2);
  assert.deepEqual(f.accepted, [[response, 0]]);
  assert.equal(f.controller.getSnapshot().pendingClicks, 0);
  await f.advance(5000);
  assert.equal(f.sent.length, 1);
});

test('20회 상한을 넘으면 20/20/5로 나누고 각 배치는 1초 이상 간격과 다른 ID를 쓴다', async () => {
  const f = fixture();
  for (let i = 0; i < 45; i++) f.controller.enqueue(2);
  await f.advance(1000);
  assert.deepEqual(f.sent.map((request) => request.clickCount), [20]);
  assert.equal(f.accepted[0][1], 50, '나머지 25회 낙관적 점수를 서버 보정 위에 남긴다');
  await f.advance(2000);
  assert.deepEqual(f.sent.map((request) => request.clickCount), [20, 20, 5]);
  assert.equal(new Set(f.sent.map((request) => request.submissionId)).size, 3);
});

test('느린 응답 중 새 클릭을 보존하고 병렬 요청이나 역순 점수 덮어쓰기를 만들지 않는다', async () => {
  let resolve!: (value: ClickResponse) => void;
  const delayed = new Promise<ClickResponse>((done) => { resolve = done; });
  const f = fixture(async () => delayed);
  f.controller.enqueue(2);
  await f.advance(1000);
  f.controller.enqueue(3);
  f.controller.enqueue(3);
  await f.advance(5000);
  assert.equal(f.sent.length, 1);
  assert.equal(f.controller.getSnapshot().pendingScore, 8);
  resolve(response);
  await f.advance(0);
  assert.deepEqual(f.accepted, [[response, 6]]);
  await f.advance(999);
  assert.equal(f.sent.length, 1);
  await f.advance(1);
  assert.equal(f.sent[1].clickCount, 2);
});

test('통신 실패 1회는 같은 submissionId와 clickCount로 재시도하며 뒤의 클릭은 별도 배치다', async () => {
  let calls = 0;
  const f = fixture(async () => { if (++calls === 1) throw network(); return response; });
  f.controller.enqueue(2);
  await f.advance(1000);
  f.controller.enqueue(2);
  await f.advance(1000);
  assert.deepEqual(f.sent[0], f.sent[1]);
  assert.equal(f.sent[1].clickCount, 1);
  assert.deepEqual(f.accepted, [[response, 2]]);
  await f.advance(1000);
  assert.notEqual(f.sent[2].submissionId, f.sent[0].submissionId);
});

test('통신 재시도도 실패하면 전송을 멈추고 원래 배치와 대기 클릭을 모두 보존한다', async () => {
  const f = fixture(async () => { throw network(); });
  f.controller.enqueue(2);
  await f.advance(1000);
  f.controller.enqueue(2);
  await f.advance(10000);
  assert.equal(f.sent.length, 2);
  assert.equal(f.controller.getSnapshot().pendingClicks, 2);
  assert.equal(f.controller.enqueue(2), false);
  assert.equal(f.accepted.length, 0);
  assert.equal(f.controller.getSnapshot().pendingScore, 4);
});

for (const [status, code] of [
  [429, 'CLICK_TOO_FREQUENT'], [429, 'CLICK_COUNT_EXCEEDED'],
  [403, 'ABUSE_VERIFICATION_REQUIRED'], [409, 'CLICK_DUPLICATE_SUBMISSION'],
  [500, 'COMMON_INTERNAL_ERROR'],
] as const) {
  test(`${status} ${code}: 자동 재전송·클릭 유실 없이 보류하고 후속 입력을 거절한다`, async () => {
    const failure = new ApiError({ kind: 'http', status, code });
    const f = fixture(async () => { throw failure; });
    f.controller.enqueue(3);
    await f.advance(1000);
    assert.equal(f.controller.getSnapshot().error, failure);
    assert.equal(f.controller.getSnapshot().pendingClicks, 1);
    assert.equal(f.controller.enqueue(3), false);
    await f.advance(20000);
    assert.equal(f.sent.length, 1);
    assert.equal(f.controller.getSnapshot().pendingScore, 3);
  });
}

test('최초 403은 확인 뒤 같은 ID·횟수로 재개하고 20회 뒤의 대기 클릭도 보존한다', async () => {
  let calls = 0;
  const f = fixture(async () => {
    if (++calls === 1) throw new ApiError({ kind: 'http', status: 403, code: 'ABUSE_VERIFICATION_REQUIRED' });
    return response;
  });
  for (let i = 0; i < 25; i++) f.controller.enqueue(2);
  await f.advance(1000);
  assert.equal(f.controller.getSnapshot().recoverable, true);
  assert.equal(f.controller.getSnapshot().pendingClicks, 25);
  await f.advance(20000); // Solving the challenge may outlive the original dedupe window: 403 consumed no ID.
  assert.equal(f.controller.resumeAfterVerification(), true);
  await f.advance(1000);
  assert.deepEqual(f.sent[1], f.sent[0]);
  assert.equal(f.controller.getSnapshot().pendingClicks, 5);
  await f.advance(1000);
  assert.equal(f.sent[2].clickCount, 5);
  assert.notEqual(f.sent[2].submissionId, f.sent[0].submissionId);
  assert.equal(f.controller.getSnapshot().pendingClicks, 0);
});

for (const code of ['CLICK_TOO_FREQUENT', 'CLICK_COUNT_EXCEEDED']) {
  test(`${code}: 명시적 재개는 거절 배치만 제외하고 후속 대기분을 새 배치로 전송한다`, async () => {
    let calls = 0;
    const f = fixture(async () => {
      if (++calls === 1) throw new ApiError({ kind: 'http', status: 429, code });
      return response;
    });
    for (let i = 0; i < 25; i++) f.controller.enqueue(2);
    await f.advance(1000);
    assert.equal(f.controller.resumeAfterVerification(), false);
    assert.deepEqual(f.controller.discardRateLimitedBatch(), { clicks: 20, score: 40 });
    assert.equal(f.controller.getSnapshot().pendingClicks, 5);
    assert.equal(f.controller.discardRateLimitedBatch(), null, '같은 배치를 두 번 제외하지 않는다');
    await f.advance(1000);
    assert.equal(f.sent[1].clickCount, 5);
    assert.notEqual(f.sent[1].submissionId, f.sent[0].submissionId);
    assert.equal(f.controller.getSnapshot().pendingScore, 0);
  });
}

for (const [status, code] of [[403, 'ABUSE_VERIFICATION_REQUIRED'], [429, 'CLICK_TOO_FREQUENT'], [409, 'CLICK_DUPLICATE_SUBMISSION']] as const) {
  test(`통신 실패 뒤 ${status}는 최초 요청의 처리 여부가 불명확해 복구 API도 재전송하지 않는다`, async () => {
    let calls = 0;
    const f = fixture(async () => {
      if (++calls === 1) throw network();
      throw new ApiError({ kind: 'http', status, code });
    });
    f.controller.enqueue(2);
    await f.advance(2000);
    assert.deepEqual(f.sent[0], f.sent[1]);
    assert.equal(f.controller.getSnapshot().recoverable, false);
    assert.equal(f.controller.resumeAfterVerification(), false);
    assert.equal(f.controller.discardRateLimitedBatch(), null);
    await f.advance(20000);
    assert.equal(f.sent.length, 2);
    assert.equal(f.controller.getSnapshot().pendingScore, 2);
  });
}

test('성공·진행 중·다른 429·폐기된 큐에는 복구 명령을 적용하지 않는다', async () => {
  const active = fixture(() => new Promise(() => undefined));
  active.controller.enqueue(1);
  await active.advance(1000);
  assert.equal(active.controller.resumeAfterVerification(), false);
  assert.equal(active.controller.discardRateLimitedBatch(), null);
  active.controller.dispose();
  const other = fixture(async () => { throw new ApiError({ kind: 'http', status: 429, code: 'COMMON_TOO_MANY_REQUESTS' }); });
  other.controller.enqueue(1);
  await other.advance(1000);
  assert.equal(other.controller.getSnapshot().recoverable, false);
  assert.equal(other.controller.discardRateLimitedBatch(), null);
  other.controller.dispose();
  assert.equal(other.controller.resumeAfterVerification(), false);
});

test('응답 유실 뒤 409는 성공으로 간주하지 않고 미확정 클릭을 보존한다', async () => {
  let calls = 0;
  const f = fixture(async () => {
    if (++calls === 1) throw network();
    throw new ApiError({ kind: 'http', status: 409, code: 'CLICK_DUPLICATE_SUBMISSION' });
  });
  f.controller.enqueue(2);
  await f.advance(10000);
  assert.deepEqual(f.sent[0], f.sent[1]);
  assert.equal(f.accepted.length, 0);
  assert.equal(f.controller.getSnapshot().pendingClicks, 1);
  assert.equal(f.controller.enqueue(2), false);
});

test('오래 걸린 통신 실패는 dedupe 만료 후 재전송하지 않는다', async () => {
  let reject!: (error: unknown) => void;
  const f = fixture(() => new Promise((_resolve, fail) => { reject = fail; }));
  f.controller.enqueue(2);
  await f.advance(10000);
  reject(network());
  await f.advance(10000);
  assert.equal(f.sent.length, 1);
  assert.equal(f.controller.getSnapshot().pendingClicks, 1);
  assert.equal(f.controller.enqueue(2), false);
});

test('백그라운드 탭에서 재시도 타이머가 늦게 실행되면 dedupe 만료 전에 전송을 보류한다', async () => {
  const f = fixture(async () => { throw network(); });
  f.controller.enqueue(2);
  await f.advance(1000);
  f.suspend(10000);
  await f.advance(0);
  assert.equal(f.sent.length, 1);
  assert.equal(f.controller.getSnapshot().pendingClicks, 1);
  assert.equal(f.controller.enqueue(2), false);
});

test('폐기는 예약 전송을 취소하고 진행 중 응답이 다음 계정 점수를 바꾸지 못한다', async () => {
  const idle = fixture();
  idle.controller.enqueue(1);
  idle.controller.dispose();
  await idle.advance(2000);
  assert.equal(idle.sent.length, 0);
  assert.equal(idle.tasks.size, 0);

  let resolve!: (value: ClickResponse) => void;
  const active = fixture(() => new Promise((done) => { resolve = done; }));
  active.controller.enqueue(1);
  await active.advance(1000);
  active.controller.dispose();
  resolve(response);
  await active.advance(2000);
  assert.equal(active.accepted.length, 0);
  assert.equal(active.controller.enqueue(1), false);
});

test('POST /clicks에는 인증 토큰과 DTO 두 필드만 보내고 만료 토큰 재시도에도 같은 ID를 쓴다', async () => {
  const sent: Array<{ path: string; body: unknown; token: string | null }> = [];
  const client = createApiClient({ fetch: (async (input, init) => {
    const path = String(input);
    sent.push({ path, body: init?.body ? JSON.parse(String(init.body)) : null, token: new Headers(init?.headers).get('Authorization') });
    const body = path.endsWith('/reissue') ? { accessToken: 'new', expiresInSeconds: 3600 }
      : sent.length === 1 ? null : response;
    const expired = sent.length === 1;
    return new Response(JSON.stringify({ code: expired ? 'COMMON_EXPIRED_TOKEN' : 'SUCCESS', message: '응답', data: body }), { status: expired ? 401 : 200 });
  }) as typeof fetch });
  client.session.signIn('old');
  const request = { submissionId: 'same-id', clickCount: 2 };
  assert.deepEqual(await submitClicks(client, request), response);
  assert.deepEqual(sent, [
    { path: '/api/v1/clicks', body: request, token: 'Bearer old' },
    { path: '/api/v1/auth/reissue', body: null, token: null },
    { path: '/api/v1/clicks', body: request, token: 'Bearer new' },
  ]);
});

test('클릭 응답 필드·입력 상한·중복 TTL 센서: Java 계약 변경 시 프론트 배치 정책을 재검토한다', () => {
  const javaRoot = new URL('../../src/main/java/com/back/nbe1214finalsour/domain/click/', import.meta.url);
  const source = readFileSync(new URL('dto/ClickResponse.java', javaRoot), 'utf8');
  const records = new Map([...source.matchAll(/record (\w+)\(([^)]*)\)/g)].map((m) => [
    m[1], m[2].split(',').map((part) => part.trim().split(/\s+/).at(-1)),
  ]));
  const fix = 'src/api/clicks.ts의 DTO와 배치 정책을 Java 클릭 계약에 맞추세요';
  assert.deepEqual(records.get('ClickResponse'), Object.keys(response), fix);
  assert.deepEqual(records.get('ClickUniversityResponse'), Object.keys(response.university), fix);
  assert.deepEqual(records.get('ClickDepartmentResponse'), Object.keys(response.department), fix);
  const requestSource = readFileSync(new URL('dto/ClickRequest.java', javaRoot), 'utf8');
  assert.match(requestSource, new RegExp(`@Max\\(value = ${MAX_CLICK_BATCH_SIZE},`), fix);
  assert.match(requestSource, /@Min\(value = 1,/, fix);
  // Location remains optional: declining it must not block ordinary clicks.
  assert.match(requestSource, /Integer clickCount,\s*Double latitude,\s*Double longitude\s*\)/, fix);
  assert.equal(CLICK_BATCH_WINDOW_MS, 1000);
  assert.match(readFileSync(new URL('service/ClickActionGuard.java', javaRoot), 'utf8'), /Duration.ofSeconds\(10L\)/,
    '중복 방지 TTL이 바뀌면 clickBatcher의 재시도 안전 시간도 재검토하세요');
});
