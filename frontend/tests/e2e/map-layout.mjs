/** Map layout sensor (M1-D1~D4): markers keep pointing at the same map image spot when the list sheet opens,
 * do not overlap each other, stay inside the map area, and TOP 3 ribbons leave the region counts readable.
 * MAP_APP_URL=http://127.0.0.1:4173 MAP_CDP_URL=http://127.0.0.1:9222 node tests/e2e/map-layout.mjs
 * Needs a running Chromium CDP endpoint and Vite preview; it never launches a browser and closes only its own tab.
 * Plan: docs/261109-1749-map_integrity_plan.md.
 */
import assert from 'node:assert/strict';
import { selectMockScreen } from './cdp-mock-tools.mjs';

const endpoint = process.env.MAP_CDP_URL ?? process.env.M6_CDP_URL ?? 'http://127.0.0.1:9222';
const app = process.env.MAP_APP_URL ?? process.env.M6_APP_URL ?? 'http://127.0.0.1:4173';
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
const pressText = async (name) => {
  await selectMockScreen(evaluate, name);
  await pause(600);
};

/** Per map area: marker positions in map image pixels, overlaps, clipping and covered region counts. */
const inspect = () => evaluate(`(() => {
  return [...document.querySelectorAll('[data-testid="map-area"]')].map((area) => {
    const a = area.getBoundingClientRect();
    const img = area.querySelector('img');
    const r = img.getBoundingClientRect();
    const s = Math.max(r.width / img.naturalWidth, r.height / img.naturalHeight);
    const left = r.left + (r.width - img.naturalWidth * s) / 2;
    const top = r.top + (r.height - img.naturalHeight * s) / 2;
    const union = (el) => {
      const rects = [el, ...el.querySelectorAll('*')].filter((e) => getComputedStyle(e).position !== 'absolute' || e === el)
        .map((e) => e.getBoundingClientRect()).filter((b) => b.width > 0 && b.height > 0);
      return { left: Math.min(...rects.map((b) => b.left)), top: Math.min(...rects.map((b) => b.top)), right: Math.max(...rects.map((b) => b.right)), bottom: Math.max(...rects.map((b) => b.bottom)) };
    };
    const markers = [...area.querySelectorAll('button[data-school-id]')].map((m) => {
      const badge = m.querySelector('.flex.justify-center > *') ?? m;
      const b = badge.getBoundingClientRect();
      // Badge and short-name label only: leader lines and ribbons are drawn outside on purpose.
      const parts = [...m.querySelectorAll(':scope > .flex.justify-center > *')].map((e) => e.getBoundingClientRect());
      const box = parts.length ? { left: Math.min(...parts.map((p) => p.left)), top: Math.min(...parts.map((p) => p.top)), right: Math.max(...parts.map((p) => p.right)), bottom: Math.max(...parts.map((p) => p.bottom)) } : union(m);
      return { id: m.dataset.schoolId, top3: m.hasAttribute('data-top-rank'), image: [(b.left + b.width / 2 - left) / s, (b.top + b.height / 2 - top) / s], box, whole: m.hasAttribute('data-top-rank') ? union(m) : box };
    });
    const hit = (p, q) => p.left < q.right - 1 && q.left < p.right - 1 && p.top < q.bottom - 1 && q.top < p.bottom - 1;
    const overlaps = [];
    for (let i = 0; i < markers.length; i++) for (let j = i + 1; j < markers.length; j++) if (hit(markers[i].box, markers[j].box)) overlaps.push(markers[i].id + '/' + markers[j].id);
    const clipped = markers.filter(({ box }) => box.left < a.left || box.right > a.right || box.top < a.top || box.bottom > a.bottom).map(({ id }) => id);
    const counts = [...area.querySelectorAll('[data-cluster-region] > div:last-child > span')];
    const covered = [];
    for (const c of counts) for (const m of markers.filter((x) => x.top3)) if (hit(c.getBoundingClientRect(), m.whole)) covered.push(c.parentElement.parentElement.dataset.clusterRegion + '←' + m.id);
    return { size: Math.round(a.width) + '×' + Math.round(a.height), region: area.dataset.region, image: Object.fromEntries(markers.map((m) => [m.id, m.image])), overlaps, clipped, covered };
  });
})()`);

const REGIONS = ['전체', '수도권', '강원', '충청', '영남', '호남', '제주'];
const failures = [];
const report = [];
async function selectRegion(region, index) {
  await evaluate(`(() => {
    const group = document.querySelectorAll('[aria-label="권역 선택"]')[${index}];
    [...group.querySelectorAll('button')].find((b) => b.textContent.trim() === ${JSON.stringify(region)}).click();
  })()`);
  await pause(700);
}
async function toggleSheet() {
  await evaluate(`document.querySelector('[data-testid="school-list-sheet"] > button').click()`);
  await pause(700);
}

try {
  for (const width of [360, 390, 430]) {
    await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 2, mobile: true });
    await send('Page.navigate', { url: app });
    await pause(1500);
    await pressText('🗺️ 단일 폰 지도');
    for (const region of REGIONS) {
      await selectRegion(region, 0);
      const [collapsed] = await inspect();
      await toggleSheet();
      const [expanded] = await inspect();
      await toggleSheet();
      const drift = Object.keys(collapsed.image).map((id) => Math.hypot(collapsed.image[id][0] - expanded.image[id][0], collapsed.image[id][1] - expanded.image[id][1]));
      const maxDrift = Math.max(0, ...drift);
      const row = `${width}px 단일 ${region} ${collapsed.size}: 마커 ${Object.keys(collapsed.image).length}, 시트 펼침 이동 ${maxDrift.toFixed(1)}px`;
      report.push(row);
      if (maxDrift > 1) failures.push(`${row} — 이미지 기준 이동`);
      for (const [kind, list] of [['겹침', collapsed.overlaps], ['잘림', collapsed.clipped], ['학교 수 가림', collapsed.covered]]) if (list.length) failures.push(`${row} — ${kind}: ${list.join(', ')}`);
    }
  }
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: app });
  await pause(1500);
  await pressText('📱📱 2-Phone 지도 비교');
  for (const region of REGIONS) {
    await selectRegion(region, 0);
    for (const area of await inspect()) {
      const row = `2-Phone ${area.region} ${area.size}: 마커 ${Object.keys(area.image).length}`;
      report.push(row);
      for (const [kind, list] of [['겹침', area.overlaps], ['잘림', area.clipped], ['학교 수 가림', area.covered]]) if (list.length) failures.push(`${row} — ${kind}: ${list.join(', ')}`);
    }
  }
} finally {
  await fetch(`${endpoint}/json/close/${target.id}`);
  ws.close();
}

for (const row of report) console.log(`  ${row}`);
for (const failure of failures) console.log(`✖ ${failure}`);
assert.equal(failures.length, 0, `${failures.length} map layout problems`);
console.log(`✔ 지도 배치 ${report.length}개 상태 통과`);
