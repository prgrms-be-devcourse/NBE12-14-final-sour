import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createClickLocation, LOCATION_MAX_AGE_MS, type LocationFix, type ClickLocationSnapshot } from '../src/utils/clickLocation';

function fixture() {
  let time = 100_000;
  const requests: Array<{ ok: (fix: LocationFix) => void; fail: (error: { code: number }) => void; options: PositionOptions }> = [];
  const timers = new Set<() => void>();
  const changes: ClickLocationSnapshot[] = [];
  const location = createClickLocation({
    isSecure: () => true, now: () => time,
    geolocation: () => ({ getCurrentPosition: (ok, fail, options) => { requests.push({ ok, fail, options }); } }),
    schedule: (run) => { timers.add(run); return () => { timers.delete(run); }; },
  });
  const unsubscribe = location.subscribe(() => changes.push(location.getSnapshot()));
  const fix = (patch: Partial<LocationFix> = {}): LocationFix => ({ timestamp: time, coords: { latitude: 37.5, longitude: 127, accuracy: 25 }, ...patch });
  return { location, requests, timers, changes, unsubscribe, fix, advance: (ms: number) => { time += ms; } };
}

test('초기에는 권한·위치를 조회하지 않고 명시적 요청만1회 실행하며 추가 정보를 보관하지 않는다', () => {
  const f = fixture();
  assert.equal(f.requests.length, 0);
  assert.equal(f.location.getCoordinates(), undefined);
  f.location.request(); f.location.request();
  assert.equal(f.requests.length, 1);
  assert.deepEqual(f.requests[0].options, { maximumAge: 0, enableHighAccuracy: true, timeout: 10_000 });
  assert.equal(f.location.getCoordinates(), undefined, '위치 대기와 클릭 전송은 독립이다');
  f.requests[0].ok(f.fix());
  assert.deepEqual(f.location.getSnapshot(), { status: 'ready', accuracy: 25 });
  const copy = f.location.getCoordinates()!;
  assert.deepEqual(copy, { latitude: 37.5, longitude: 127 });
  copy.latitude = 0;
  assert.equal(f.location.getCoordinates()?.latitude, 37.5);
  assert.equal(JSON.stringify(f.location.getSnapshot()).includes('latitude'), false, 'UI snapshot에는 좌표가 없다');
  f.location.clear();
  assert.equal(f.timers.size, 0);
});

for (const [code, status] of [[1, 'denied'], [2, 'unavailable'], [3, 'timeout'], [99, 'unavailable']] as const) {
  test(`위치 실패${code}는 ${status}로 안내하고 좌표를 반환하지 않는다`, () => {
    const f = fixture();
    f.location.request(); f.requests[0].fail({ code });
    assert.equal(f.location.getSnapshot().status, status);
    assert.equal(f.location.getCoordinates(), undefined);
    f.requests[0].ok(f.fix());
    assert.equal(f.location.getCoordinates(), undefined, '실패 뒤 늦은 성공도 무시한다');
  });
}

test('비보안·미지원·동기 예외에서도 권한을 반복 요청하지 않는다', () => {
  let probed = 0;
  const insecure = createClickLocation({ isSecure: () => false, geolocation: () => { probed++; return undefined; } });
  insecure.request();
  assert.equal(insecure.getSnapshot().status, 'insecure');
  assert.equal(probed, 0);
  const missing = createClickLocation({ isSecure: () => true, geolocation: () => undefined });
  missing.request();
  assert.equal(missing.getSnapshot().status, 'unsupported');
  const broken = createClickLocation({ isSecure: () => true, geolocation: () => ({ getCurrentPosition() { throw new Error('platform unavailable'); } }) });
  broken.request();
  assert.equal(broken.getSnapshot().status, 'unavailable');
  assert.equal(broken.getCoordinates(), undefined);
});

test('정확도100m·좌표0도는 허용하고 범위 밖·NaN·음수 정확도·100m 초과·미래 시각은 생략한다', () => {
  for (const [latitude, longitude, accuracy, status] of [[0, 0, 100, 'ready'], [90, -180, 0, 'ready'],
    [91, 127, 10, 'unavailable'], [37, 181, 10, 'unavailable'], [NaN, 127, 10, 'unavailable'],
    [37, Infinity, 10, 'unavailable'], [37, 127, -1, 'unavailable'], [37, 127, 100.01, 'inaccurate'], [37, 127, NaN, 'unavailable']] as const) {
    const f = fixture();
    f.location.request(); f.requests[0].ok(f.fix({ coords: { latitude, longitude, accuracy } }));
    assert.equal(f.location.getSnapshot().status, status);
    assert.equal(f.location.getCoordinates() !== undefined, status === 'ready');
    f.location.clear();
  }
  for (const timestamp of [100_001, NaN, 100_000 - LOCATION_MAX_AGE_MS]) {
    const f = fixture();
    f.location.request(); f.requests[0].ok(f.fix({ timestamp }));
    assert.equal(f.location.getCoordinates(), undefined);
  }
});

test('30초 경계와 지연된 타이머를 검증하고 실제 획득 시각부터 유효기간을 계산한다', () => {
  const f = fixture();
  f.location.request(); f.requests[0].ok(f.fix({ timestamp: 99_000 }));
  f.advance(LOCATION_MAX_AGE_MS - 1001);
  assert.ok(f.location.getCoordinates());
  f.advance(1);
  assert.equal(f.location.getCoordinates(), undefined, '타이머를 실행하지 않아도 새 제출에 오래된 위치를 넣지 않는다');
  assert.equal(f.location.getSnapshot().status, 'expired');
  assert.equal(f.timers.size, 0);
  f.location.request(); f.requests[1].ok(f.fix());
  for (const run of [...f.timers]) run();
  assert.equal(f.location.getSnapshot().status, 'expired');
});

test('중지·세션 종료·언마운트의 clear는 늦은 콜백을 무시하고 다시 요청할 수 있다', () => {
  const f = fixture();
  f.location.request(); f.location.clear(); f.location.request();
  f.requests[0].ok(f.fix()); f.requests[0].fail({ code: 1 });
  assert.equal(f.location.getSnapshot().status, 'locating');
  f.requests[1].ok(f.fix());
  assert.equal(f.location.getSnapshot().status, 'ready');
  f.location.clear();
  assert.equal(f.location.getCoordinates(), undefined);
  assert.equal(f.timers.size, 0);
  f.unsubscribe();
  const previous = f.changes.length;
  f.location.clear();
  assert.equal(f.changes.length, previous);
});
