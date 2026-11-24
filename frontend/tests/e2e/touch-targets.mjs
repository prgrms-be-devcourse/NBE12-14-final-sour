/** Touch area sensor: every control on the service screens takes taps 21px from its center (44×44px).
 * User instruction 2026-11-09; plan docs/261109-1357-school_values_touch44_plan.md §3.
 * TOUCH_APP_URL=http://127.0.0.1:4173 TOUCH_CDP_URL=http://127.0.0.1:9222 node tests/e2e/touch-targets.mjs
 * Needs a running Chromium CDP endpoint and Vite preview; it never launches a browser and closes only its own tab.
 * Left out on purpose: [시연] tools and comparison screens (outside [data-touch-area]) and [data-no-touch44]
 * (map markers and clusters; the map list's 방문 button does the same, src/components/MapParts.tsx).
 */
import assert from 'node:assert/strict';
import { pressKey } from './cdp-input.mjs';
import { selectMockScreen } from './cdp-mock-tools.mjs';

const endpoint = process.env.TOUCH_CDP_URL ?? process.env.M6_CDP_URL ?? 'http://127.0.0.1:9222';
// These checks cover the framed demo screens, so pin ?device=framed (mobile-full.mjs covers full).
const app = (() => { const url = new URL(process.env.TOUCH_APP_URL ?? process.env.M6_APP_URL ?? 'http://127.0.0.1:4173'); if (!url.searchParams.has('device')) url.searchParams.set('device', 'framed'); return url.href; })();
const REACH = 21; // 44px area: 22px each side of the center, probed 1px inside.
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const target = await (await fetch(`${endpoint}/json/new?about:blank`, { method: 'PUT' })).json();
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let nextId = 0;
const pending = new Map();
ws.onmessage = ({ data }) => {
  const message = JSON.parse(data);
  pending.get(message.id)?.(message);
  pending.delete(message.id);
};
const send = (method, params = {}) => new Promise((resolve) => {
  const id = ++nextId;
  pending.set(id, resolve);
  ws.send(JSON.stringify({ id, method, params }));
});
async function evaluate(expression) {
  const response = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (response.result.exceptionDetails) throw new Error(JSON.stringify(response.result.exceptionDetails));
  return response.result.result.value;
}
async function press(name) {
  await evaluate(`(() => {
    const name = ${JSON.stringify(name)};
    const match = (scope) => [...scope.querySelectorAll('button, [role=tab]')].filter((b) =>
      b.textContent.trim() === name || b.getAttribute('aria-label') === name || b.title === name);
    const dialog = document.querySelector('dialog[open]');
    const found = dialog ? match(dialog) : match(document);
    if (found.length === 0 && dialog) throw new Error('No control in the open dialog (' + dialog.getAttribute('aria-label') + '): ' + name);
    if (found.length === 0) throw new Error('No control: ' + name);
    found[0].click();
  })()`);
  await pause(350);
}

/** Probes every control in the service area (or the open dialog) at its center and 21px up, down, left and right. */
const probe = () => evaluate(`(async () => {
  const dialog = document.querySelector('dialog[open]');
  const area = dialog ?? document.querySelector('[data-touch-area="44"]');
  if (!area || !area.closest('[data-touch-area="44"]')) throw new Error('No service touch area');
  const controls = [...area.querySelectorAll('button, a[href], [role=button], [role=tab], input:not([type=hidden]), select, textarea')]
    .filter((e) => !e.closest('[data-no-touch44]') && !e.disabled && e.getClientRects().length > 0
      && getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).pointerEvents !== 'none');
  const failures = [];
  for (const e of controls) {
    e.scrollIntoView({ block: 'center', inline: 'center' });
    await new Promise((r) => requestAnimationFrame(r));
    const r = e.getBoundingClientRect();
    const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    const target = e.matches('input[type=checkbox], input[type=radio]') ? (e.closest('label') ?? e) : e;
    const points = { center: [cx, cy], left: [cx - ${REACH}, cy], right: [cx + ${REACH}, cy], up: [cx, cy - ${REACH}], down: [cx, cy + ${REACH}] };
    const missed = [];
    for (const [side, [x, y]] of Object.entries(points)) {
      if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) continue; // past the screen edge
      const hit = document.elementFromPoint(x, y);
      if (!hit || !(hit === target || target.contains(hit))) {
        const label = hit ? (hit.getAttribute('aria-label') || hit.textContent || hit.tagName).trim().replace(/\\s+/g, ' ').slice(0, 24) : 'none';
        missed.push(side + '→' + label);
      }
    }
    if (missed.length) {
      const name = (e.getAttribute('aria-label') || e.title || e.textContent || e.placeholder || e.tagName).trim().replace(/\\s+/g, ' ').slice(0, 32);
      failures.push(name + ' ' + Math.round(r.width) + '×' + Math.round(r.height) + ' [' + missed.join(', ') + ']');
    }
  }
  return { count: controls.length, failures };
})()`);

const SCREENS = [
  '🎮 메인 게임 화면', '📱 순차 온보딩 (Single Phone)', '🗺️ 단일 폰 지도', '🏆 랭킹 화면 (Single Phone)',
  '🏪 상점 & 강화 (Single Phone)', '🤝 연합 신청 (Alliance Apply)', '🛡️ 현재 연합 (Current Alliance)',
  '⚔️ 대결 신청 (Battle Apply)', '📊 대결 현황 (Battle Status)',
];
const MAIN = '🎮 메인 게임 화면';
/** Screens and sheets reached from the main game screen: [steps, label, steps to leave]. */
const FLOWS = [
  [['우리 학교 방명록 보기'], '시트: 우리 학교 방명록', ['Escape']],
  [['채팅창 열기'], '시트: 채팅', ['Escape']],
  [[MAIN, '순위'], '게임 탭: 순위', []],
  [[MAIN, '상점'], '게임 탭: 상점', []],
  [[MAIN, '연합·대결'], '게임 탭: 연합·대결', []],
  [[MAIN, '지도'], '게임 탭: 지도', []],
  [['🗺️ 단일 폰 지도', '__toggleSheet__'], '지도 목록 펼침', ['__toggleSheet__']],
  [['🗺️ 단일 폰 지도', '__visit__'], '방문 학교', []],
  [['부산대학교 방명록 보기'], '시트: 방문 학교 방명록', ['Escape']],
  [['__visitInfo__'], '시트: 방문 학교 정보', ['Escape']],
];

/** Opens or closes the map school list sheet (its handle bar is the sheet's first button). */
async function toggleSheet() {
  await evaluate(`(() => {
    const handle = document.querySelector('[data-testid="school-list-sheet"] > button');
    if (!handle) throw new Error('No map school list sheet');
    handle.click();
  })()`);
  await pause(600);
}

const report = [];
async function run(width, height, orientation) {
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 2, mobile: true });
  await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  await send('Page.navigate', { url: app });
  await pause(1500);
  for (const screen of SCREENS) {
    await selectMockScreen(evaluate, screen);
    report.push({ orientation, screen, ...(await probe()) });
  }
  await selectMockScreen(evaluate, MAIN);
  for (const [steps, label, leave] of FLOWS) {
    for (const step of steps) {
      if (step === '__toggleSheet__') await toggleSheet();
      else if (step === '__visit__') {
        await evaluate(`(() => {
          const visit = document.querySelector('button[aria-label="부산대학교 방문"]');
          if (!visit) throw new Error('No 부산대학교 방문 button on the map list');
          visit.click();
        })()`);
        await pause(1200);
      } else if (step === '__visitInfo__') {
        await evaluate(`document.querySelector('[data-testid="visit-info-button"]').click()`);
        await pause(350);
      } else if (SCREENS.includes(step)) await selectMockScreen(evaluate, step);
      else await press(step);
    }
    report.push({ orientation, screen: label, ...(await probe()) });
    for (const step of leave) {
      if (step === 'Escape') await pressKey(send, 'Escape');
      if (step === '__toggleSheet__') await toggleSheet();
      await pause(250);
    }
  }
}

try {
  await run(360, 740, '세로 360');
  await run(390, 844, '세로');
  await run(844, 390, '가로');
} catch (error) {
  console.log(JSON.stringify(report.map((row) => [row.orientation, row.screen, row.count, row.failures.length])));
  throw error;
} finally {
  await fetch(`${endpoint}/json/close/${target.id}`);
  ws.close();
}

let failed = 0;
for (const row of report) {
  console.log(`${row.failures.length ? '✖' : '✔'} ${row.orientation} ${row.screen}: ${row.count}개 중 ${row.failures.length}개 미달`);
  for (const failure of row.failures) console.log(`    ${failure}`);
  failed += row.failures.length;
}
assert.ok(report.every((row) => row.count > 0), 'A screen had no controls; the flow did not reach it.');
assert.equal(failed, 0, `${failed} controls take taps on less than 44×44px.`);
