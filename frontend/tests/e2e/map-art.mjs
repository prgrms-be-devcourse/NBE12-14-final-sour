/** T7 map layers in a real browser (docs/261110-1005-mockup_remaining_tasks_plan.md §10-1).
 * Requires an existing preview and Chromium CDP; opens and closes only its own tab.
 * MAP_ART_APP_URL=http://127.0.0.1:4173 MAP_ART_CDP_URL=http://127.0.0.1:9222 MAP_ART_QA_OUT=out node tests/e2e/map-art.mjs
 * Checks per region: the land layer sits on the old object-cover box, the sea covers the whole map area even when
 * zoomed, clouds and sea glints run and clouds move west to east; with reduced motion nothing in the map animates and
 * clouds rest on their spots in the original. Pixel agreement with the original JPG is `npm run map:check`.
 * 제주 (J1·J2, docs/261110-2005-visit_ui_jeju_map_plan.md §3): the island is centred in the map area and each school
 * marker's anchor lies on an opaque pixel of the islands layer.
 */
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const app = process.env.MAP_ART_APP_URL ?? process.env.M6_APP_URL ?? 'http://127.0.0.1:4173';
const endpoint = process.env.MAP_ART_CDP_URL ?? process.env.M6_CDP_URL ?? 'http://127.0.0.1:9222';
const out = path.resolve(process.env.MAP_ART_QA_OUT ?? '.agent-workflow/261110-t7-map-layers/e2e');
const REGIONS = ['전체', '수도권', '강원', '충청', '영남', '호남', '제주'];
const { jeju } = JSON.parse(await readFile(new URL('../../src/assets/images/map/layers.json', import.meta.url), 'utf8'));
const JEJU_CENTER_TOLERANCE_PX = 8;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const results = [], failures = [], pending = new Map();
let ws, target, nextId = 0;

function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++nextId;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 10000);
    pending.set(id, { resolve, reject, timer });
    ws.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? JSON.stringify(result.exceptionDetails));
  return result.result.value;
}
async function waitFor(expression) {
  for (let i = 0; i < 50; i++) { if (await evaluate(expression)) return; await pause(100); }
  throw new Error(`Condition not met: ${expression}`);
}
async function check(name, run) {
  try { await run(); } catch (error) { failures.push({ name, message: error.message }); }
}
async function open({ reducedMotion }) {
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: reducedMotion ? 'reduce' : 'no-preference' }] });
  await send('Page.navigate', { url: app });
  await waitFor(`document.readyState === 'complete' && [...document.querySelectorAll('button')].some(b => b.textContent.includes('단일 폰 지도'))`);
  await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.includes('단일 폰 지도')).click()`);
  await waitFor(`!!document.querySelector('[data-testid="map-area"] [data-map-art]')`);
}
async function selectRegion(region) {
  await evaluate(`[...document.querySelector('[aria-label="권역 선택"]').querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(region)}).click()`);
  await waitFor(`document.querySelector('[data-testid="map-area"]')?.dataset.region === ${JSON.stringify(region)}`);
  await evaluate(`Promise.all([...document.querySelectorAll('[data-map-art] img')].map(i => i.decode().catch(() => {})))`);
  await pause(450); // camera transition is 300ms
}
/** Geometry in the untransformed design frame (offset sizes), plus what the browser renders on screen. */
const state = () => evaluate(`(() => {
  const area = document.querySelector('[data-testid="map-area"]'), a = area.getBoundingClientRect();
  const art = area.querySelector('[data-map-art]'), frame = art.parentElement;
  const box = art.querySelector('[data-map-image-box]'), land = area.querySelector('img');
  const sea = art.querySelector('[data-map-sea]').getBoundingClientRect();
  const clouds = [...art.querySelectorAll('[data-map-cloud]')].map(row => {
    const m = new DOMMatrixReadOnly(getComputedStyle(row).transform);
    return { id: row.dataset.mapCloud, shiftPct: +(m.m41 / box.offsetWidth * 100).toFixed(3), widthPct: +(row.firstElementChild.offsetWidth / box.offsetWidth * 100).toFixed(3), inline: row.style.transform };
  });
  const animations = document.getAnimations().filter(an => art.contains(an.effect?.target ?? null));
  return {
    region: area.dataset.region, area: [a.width, a.height].map(Math.round),
    frame: [frame.offsetWidth, frame.offsetHeight], box: [box.offsetLeft, box.offsetTop, box.offsetWidth, box.offsetHeight],
    firstImg: land.dataset.mapLayerArt, landNatural: [land.naturalWidth, land.naturalHeight], landSize: [land.offsetWidth, land.offsetHeight],
    seaCovers: sea.left <= a.left + 0.5 && sea.top <= a.top + 0.5 && sea.right >= a.right - 0.5 && sea.bottom >= a.bottom - 0.5,
    clouds, animations: animations.map(an => an.animationName ?? an.constructor.name).sort(),
    running: animations.filter(an => an.playState === 'running').length,
  };
})()`);
/** 제주 view: island box centre vs map area centre (screen px) and the islands-layer alpha under each marker anchor. */
const jejuState = () => evaluate(`(() => {
  const area = document.querySelector('[data-testid="map-area"]'), a = area.getBoundingClientRect();
  const img = area.querySelector('img[data-map-layer-art="islands"]'), r = img.getBoundingClientRect();
  const sx = r.width / img.naturalWidth, sy = r.height / img.naturalHeight, j = ${JSON.stringify({ x0: jeju.x0, y0: jeju.y0, x1: jeju.x1, y1: jeju.y1 })};
  const island = { x: r.left + (j.x0 + j.x1 + 1) / 2 * sx, y: r.top + (j.y0 + j.y1 + 1) / 2 * sy };
  const canvas = document.createElement('canvas');
  canvas.width = img.naturalWidth; canvas.height = img.naturalHeight;
  const ctx = canvas.getContext('2d'); ctx.drawImage(img, 0, 0);
  const markers = [...area.querySelectorAll('[data-school-id]')].map(m => {
    const b = m.getBoundingClientRect(), cx = b.left + b.width / 2, cy = b.top + b.height / 2;
    const ix = Math.floor((cx - r.left) / sx), iy = Math.floor((cy - r.top) / sy);
    return { id: m.dataset.schoolId, alpha: ctx.getImageData(ix, iy, 1, 1).data[3], box: [b.left, b.top, b.right, b.bottom].map(Math.round) };
  });
  return { offset: [island.x - (a.left + a.width / 2), island.y - (a.top + a.height / 2)].map(v => +v.toFixed(1)), markers };
})()`);
async function shot(name) {
  // The single-phone map starts below the 844px viewport; the clip is in page coordinates.
  const clip = await evaluate(`(() => { const r = document.querySelector('[data-testid="map-area"]').getBoundingClientRect(); return { x: r.left + scrollX, y: r.top + scrollY, width: r.width, height: r.height, scale: 1 }; })()`);
  const { data } = await send('Page.captureScreenshot', { format: 'png', clip, captureBeyondViewport: true });
  await writeFile(path.join(out, `${name}.png`), Buffer.from(data, 'base64'));
}

try {
  await mkdir(out, { recursive: true });
  const version = await (await fetch(`${endpoint}/json/version`)).json();
  target = await (await fetch(`${endpoint}/json/new?about:blank`, { method: 'PUT' })).json();
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  ws.onmessage = ({ data }) => {
    const message = JSON.parse(data), call = pending.get(message.id);
    if (!call) return;
    clearTimeout(call.timer); pending.delete(message.id);
    if (message.error) call.reject(new Error(JSON.stringify(message.error))); else call.resolve(message.result);
  };
  await send('Page.enable'); await send('Runtime.enable');

  for (const reducedMotion of [false, true]) {
    await open({ reducedMotion });
    for (const region of REGIONS) {
      await selectRegion(region);
      const name = `${region}${reducedMotion ? ' reduced' : ''}`;
      const s = await state();
      await check(`${name}: first image is the land layer at the source size`, () => assert.deepEqual([s.firstImg, ...s.landNatural], ['land', 848, 1264]));
      await check(`${name}: image box is the old object-cover box`, () => {
        const [fw, fh] = s.frame, scale = Math.max(fw / 848, fh / 1264);
        const expected = [(fw - 848 * scale) / 2, (fh - 1264 * scale) / 2, 848 * scale, 1264 * scale];
        assert.ok(s.box.every((v, i) => Math.abs(v - expected[i]) <= 1), JSON.stringify({ box: s.box, expected }));
        assert.deepEqual(s.landSize, [s.box[2], s.box[3]]);
      });
      await check(`${name}: sea covers the whole map area`, () => assert.ok(s.seaCovers));
      if (region === '제주') {
        const j = await jejuState();
        await check(`${name}: 제주도 is centred in the map area`, () =>
          assert.ok(j.offset.every(v => Math.abs(v) <= JEJU_CENTER_TOLERANCE_PX), `island centre off by ${j.offset} px; see REGION_FOCUS in schoolMapLayout.ts`));
        await check(`${name}: every school marker stands on 제주도`, () => {
          assert.equal(j.markers.length, 2, JSON.stringify(j.markers));
          assert.deepEqual(j.markers.filter(m => m.alpha === 0).map(m => m.id), [], 'marker anchors over the sea; move them in PLACED_POINTS');
        });
        results.push({ name: `${name} jeju`, ...j });
      }
      if (reducedMotion) {
        await check(`${name}: nothing in the map animates`, () => assert.equal(s.animations.length, 0, JSON.stringify(s.animations)));
        await check(`${name}: clouds rest on their source spots`, () => {
          for (const c of s.clouds) assert.ok(Math.abs(c.shiftPct - Number(c.inline.match(/-?[\d.]+/)[0])) < 0.05, JSON.stringify(c));
        });
      } else {
        await check(`${name}: three cloud drifts and two glint layers run`, () => {
          assert.deepEqual(s.animations, ['map-cloud-drift', 'map-cloud-drift', 'map-cloud-drift', 'map-sea-flow', 'map-sea-flow', 'map-sea-glint', 'map-sea-glint']);
          assert.equal(s.running, 7);
        });
      }
      await shot(`${region}${reducedMotion ? '-reduced' : ''}`);
      results.push({ name, ...s });
    }
    if (!reducedMotion) {
      await selectRegion('전체');
      const before = await state();
      await pause(3000);
      const after = await state();
      await check('전체: clouds move west to east', () => {
        for (const c of after.clouds) {
          const b = before.clouds.find(x => x.id === c.id);
          // A cloud that left the east edge meanwhile re-entered from the west: its path is 100% + its width long.
          const moved = (c.shiftPct - b.shiftPct + (100 + c.widthPct)) % (100 + c.widthPct);
          assert.ok(moved > 0 && moved < 20, `${c.id}: ${b.shiftPct} → ${c.shiftPct} (moved ${moved.toFixed(2)}%)`);
        }
      });
      results.push({ name: '전체 drift over 3s', clouds: after.clouds.map(c => ({ id: c.id, from: before.clouds.find(x => x.id === c.id).shiftPct, to: c.shiftPct })) });
    }
  }

  await writeFile(path.join(out, 'report.json'), JSON.stringify({ browser: version.Browser, app, results, failures,
    notCovered: ['Real phones (headless Chromium here)', 'Pixel agreement with the JPG: npm run map:check', 'Visual judgement of drift speed and glints: see the screenshots'] }, null, 2));
  console.log(JSON.stringify({ out, regions: results.length, failures }, null, 2));
  assert.equal(failures.length, 0, 'Map art checks failed; see report.json');
} finally {
  for (const call of pending.values()) clearTimeout(call.timer);
  ws?.close();
  if (target) await fetch(`${endpoint}/json/close/${target.id}`).catch(() => {});
}
