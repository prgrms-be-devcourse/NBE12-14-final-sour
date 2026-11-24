import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import React from 'react';
import { JSDOM } from 'jsdom';
import type { LibraryClickControl } from '../src/hooks/useCampusClicks';
import { createClickLocation, type LocationFix } from '../src/utils/clickLocation';
import { assertAbsent } from './helpers/domAssert';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true })) {
  Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
}
const { render, screen, fireEvent, cleanup, act } = await import('@testing-library/react');
const { LibraryBonusControl } = await import('../src/components/LibraryBonusControl');
afterEach(cleanup);
after(() => dom.window.close());

function mount() {
  const requests: Array<{ ok: (fix: LocationFix) => void; fail: (error: { code: number }) => void }> = [];
  const timers = new Set<() => void>();
  const location = createClickLocation({
    isSecure: () => true, now: () => 1000,
    geolocation: () => ({ getCurrentPosition: (ok, fail) => { requests.push({ ok, fail }); } }),
    schedule: (run) => { timers.add(run); return () => { timers.delete(run); }; },
  });
  function Probe({ result }: { result: LibraryClickControl['lastResult'] }) {
    const state = React.useSyncExternalStore(location.subscribe, location.getSnapshot, location.getSnapshot);
    React.useEffect(() => () => location.clear(), []);
    return React.createElement(LibraryBonusControl, { control: { location: state, request: location.request, clear: location.clear, lastResult: result } });
  }
  const ui = render(React.createElement(Probe, { result: null }));
  const fix = (accuracy = 20): LocationFix => ({ timestamp: 1000, coords: { latitude: 37.5, longitude: 127, accuracy } });
  const open = () => fireEvent.click(screen.getByText('📚 도서관 보너스 (선택)'));
  const request = () => fireEvent.click(screen.getByRole('button', { name: '내 위치 확인' }));
  return { location, requests, timers, ui, fix, open, request, response: (result: LibraryClickControl['lastResult']) => ui.rerender(React.createElement(Probe, { result })) };
}

test('목적을 펼쳐도 권한을 묻지 않고 명시적 버튼 뒤에만 조회하며 위치 성공을 보너스로 표시하지 않는다', () => {
  const f = mount();
  assert.equal(f.requests.length, 0);
  // JSDOM does not model closed-details UA visibility; the real browser check covers hidden controls.
  assert.equal(document.querySelector('details')?.open, false);
  f.open();
  screen.getByText(/실제 위경도를 클릭 요청에 보내요/);
  screen.getByText(/새 제출용 위치는 메모리에만 최대 30초/);
  assert.equal(f.requests.length, 0);
  f.request();
  assert.equal(f.requests.length, 1);
  assert.equal((screen.getByRole('button', { name: '위치 확인 중…' }) as HTMLButtonElement).disabled, true);
  assert.match(screen.getByTestId('click-location-status').textContent ?? '', /일반 클릭을 계속/);
  act(() => f.requests[0].ok(f.fix()));
  assert.match(screen.getByTestId('click-location-status').textContent ?? '', /서버가 판단/);
  assertAbsent(screen.queryByTestId('library-bonus-result'));
  assert.doesNotMatch(document.body.textContent ?? '', /37\.5|127/);
  f.response({ libraryBonus: true, multiplier: 1.2 });
  assert.match(screen.getByTestId('library-bonus-result').textContent ?? '', /보너스 적용 · 최종 배율 1.2배/);
  f.response({ libraryBonus: false, multiplier: 1 });
  assert.match(screen.getByTestId('library-bonus-result').textContent ?? '', /보너스 미적용/);
});

test('취소·중지·만료는 새 제출에 위치를 쓰지 않으며 늦은 콜백과 중복 요청을 막는다', () => {
  const f = mount(); f.open(); f.request();
  fireEvent.click(screen.getByRole('button', { name: '위치 확인 취소' }));
  act(() => f.requests[0].ok(f.fix()));
  assert.equal(f.location.getCoordinates(), undefined);
  f.request();
  act(() => f.requests[1].ok(f.fix()));
  assert.ok(f.location.getCoordinates());
  fireEvent.click(screen.getByRole('button', { name: '다음 제출부터 위치 사용 중지' }));
  assert.equal(f.location.getCoordinates(), undefined);
  assert.equal(f.timers.size, 0);
  f.request();
  act(() => f.requests[2].ok(f.fix()));
  act(() => { for (const run of [...f.timers]) run(); });
  assert.match(screen.getByTestId('click-location-status').textContent ?? '', /유효시간이 지났어요/);
  assert.equal(f.location.getCoordinates(), undefined);
  screen.getByText(/이미 전송을 시작한 제출은 재시도할 때도 같은 좌표/);
});

for (const [code, text] of [[1, '권한이 거부'], [2, '가져오지 못'], [3, '시간이 초과']] as const) {
  test(`위치 오류${code}를 설명하고 재시도 버튼을 다시 제공한다`, () => {
    const f = mount(); f.open(); f.request();
    act(() => f.requests[0].fail({ code }));
    assert.match(screen.getByTestId('click-location-status').textContent ?? '', new RegExp(text));
    assert.equal((screen.getByRole('button', { name: '내 위치 확인' }) as HTMLButtonElement).disabled, false);
    assert.equal(f.location.getCoordinates(), undefined);
  });
}

test('부정확한 위치와 언마운트 뒤 늦은 위치를 사용하지 않는다', () => {
  const f = mount(); f.open(); f.request();
  act(() => f.requests[0].ok(f.fix(150)));
  assert.match(screen.getByTestId('click-location-status').textContent ?? '', /100m보다 커서 좌표를 전송하지/);
  f.request(); f.ui.unmount();
  act(() => f.requests[1].ok(f.fix()));
  assert.equal(f.location.getCoordinates(), undefined);
  assert.equal(f.timers.size, 0);
});
