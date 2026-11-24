/** Narrow screen sensor: on 360–430px portrait and landscape phones, the bottom nav labels fit inside their buttons (T3),
 * the clicker upgrade preview is not cut off
 * (M6-D1), the map search box is as tall as its 44px input (T44-D3), and the email code step stays one block
 * instead of spreading across the frame (M6-D2 = M4 #5). Plan: docs/261109-1817-narrow_spacing_plan.md.
 * NARROW_APP_URL=http://127.0.0.1:4173 NARROW_CDP_URL=http://127.0.0.1:9222 node tests/e2e/narrow-layout.mjs
 * Needs a running Chromium CDP endpoint and Vite preview; it never launches a browser and closes only its own tab.
 */
import assert from 'node:assert/strict';
import { selectMockScreen } from './cdp-mock-tools.mjs';

const endpoint = process.env.NARROW_CDP_URL ?? process.env.M6_CDP_URL ?? 'http://127.0.0.1:9222';
const app = process.env.NARROW_APP_URL ?? process.env.M6_APP_URL ?? 'http://127.0.0.1:4173';
const CODE_STEP_GAP_MAX = 16; // gap-3 is 12px; the old spread layout left 100px+ between blocks.
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
    const found = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === ${JSON.stringify(name)});
    if (!found) throw new Error('No button: ' + ${JSON.stringify(name)});
    found.click();
  })()`);
  await pause(600);
}

const failures = [];
const report = [];
try {
  for (const [width, height] of [[360, 740], [390, 844], [430, 932], [844, 390]]) {
    const size = `${width}×${height}`;
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 2, mobile: true });
    await send('Page.navigate', { url: app });
    await pause(1500);

    // T3 (2-1-1): the enlarged nav labels stay on one line inside the plaque's inset line
    // (button width − 3px border ×2 − 2px inset ×2), and icons are 24px.
    const nav = await evaluate(`(() => [...document.querySelector('nav[aria-label="주요 메뉴"]').querySelectorAll('button')].map((b) => {
      const label = b.querySelector('span:last-child'), r = label.getBoundingClientRect();
      return { label: label.textContent.trim(), width: Math.round(r.width * 10) / 10, room: Math.round((b.getBoundingClientRect().width - 10) * 10) / 10,
        oneLine: r.height < parseFloat(getComputedStyle(label).fontSize) * 1.6, icon: Math.round(b.querySelector('svg').getBoundingClientRect().width) };
    }))()`);
    const tightest = nav.reduce((a, b) => (b.room - b.width < a.room - a.width ? b : a));
    report.push(`${size} 하단 내비 가장 빠듯한 라벨 ${tightest.label} ${tightest.width}/${tightest.room}px, 아이콘 ${nav.map((n) => n.icon).join('·')}px`);
    const badNav = nav.filter((n) => n.width > n.room || !n.oneLine || n.icon < 24);
    if (badNav.length) failures.push(`${size} 하단 내비 라벨·아이콘 ${JSON.stringify(badNav)}`);

    await selectMockScreen(evaluate, '🏪 상점 & 강화 (Single Phone)');
    const shop = await evaluate(`(() => {
      const preview = document.querySelector('[data-testid="clicker-upgrade-preview"]');
      const button = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('업그레이드'));
      const row = preview.parentElement.getBoundingClientRect();
      const p = preview.getBoundingClientRect(), b = button.getBoundingClientRect();
      return { text: preview.textContent, cut: preview.scrollWidth > preview.clientWidth + 0.5, inside: [p, b].every((r) => r.left >= row.left - 0.5 && r.right <= row.right + 0.5) };
    })()`);
    report.push(`${size} 강화 미리보기 "${shop.text}"`);
    if (shop.cut || !shop.inside) failures.push(`${size} 강화 미리보기 잘림 ${JSON.stringify(shop)}`);

    await selectMockScreen(evaluate, '🗺️ 단일 폰 지도');
    const search = await evaluate(`(() => {
      const box = document.querySelector('[data-testid="map-search-box"]').getBoundingClientRect();
      const input = document.querySelector('input[aria-label="학교 검색"]').getBoundingClientRect();
      return { box: Math.round(box.height * 10) / 10, input: Math.round(input.height * 10) / 10 };
    })()`);
    report.push(`${size} 검색 상자 ${search.box}px (입력 ${search.input}px)`);
    if (search.input < 44 || search.box > search.input + 0.5) failures.push(`${size} 검색 상자 높이 ${JSON.stringify(search)}`);

    await selectMockScreen(evaluate, '📱 순차 온보딩 (Single Phone)');
    await evaluate(`(() => {
      const input = document.querySelector('input[type="email"]');
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, 'narrow@kaist.ac.kr');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    })()`);
    await press('인증 코드 받기');
    await pause(800);
    const gaps = await evaluate(`(() => {
      const blocks = [...document.querySelector('[data-testid="code-step"]').children].map((e) => e.getBoundingClientRect());
      return blocks.slice(1).map((r, i) => Math.round(r.top - blocks[i].bottom));
    })()`);
    report.push(`${size} 인증 코드 단계 블록 간격 ${gaps.join('·')}px`);
    if (gaps.length !== 2 || gaps.some((gap) => gap > CODE_STEP_GAP_MAX)) failures.push(`${size} 인증 코드 단계 간격 ${gaps.join('·')}px`);
  }
} finally {
  await fetch(`${endpoint}/json/close/${target.id}`);
  ws.close();
}

for (const row of report) console.log(`  ${row}`);
for (const failure of failures) console.log(`✖ ${failure}`);
assert.equal(failures.length, 0, `${failures.length} narrow layout problems`);
console.log(`✔ 좁은 화면 배치 ${report.length}개 확인 통과`);
