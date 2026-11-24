/** T2 full-screen phone mode and T5 campus stats sheet in a real browser (issue #46 stage 3).
 * Requires an existing preview and Chromium CDP; opens and closes only its own tab.
 * FULL_APP_URL=http://127.0.0.1:4173 FULL_CDP_URL=http://127.0.0.1:9222 FULL_QA_OUT=out node tests/e2e/mobile-full.mjs
 * Emulation only: real iPhone/Android, browser chrome height, keyboards and safe-area insets stay with device testing (#31).
 */
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pressKey } from './cdp-input.mjs';

const app = process.env.FULL_APP_URL ?? process.env.M6_APP_URL ?? 'http://127.0.0.1:4173';
const endpoint = process.env.FULL_CDP_URL ?? process.env.M6_CDP_URL ?? 'http://127.0.0.1:9222';
const out = path.resolve(process.env.FULL_QA_OUT ?? '.agent-workflow/261110-t2-t5-mobile-stats/step3/mobile-full');
// Issue #46: the seven phone sizes. 390x664, 360x584 and 412x859 approximate browser chrome taking 180/196/56px.
const SIZES = [[390, 844], [360, 780], [360, 640], [412, 915], [390, 664], [360, 584], [412, 859]];
// The Lv.1-10 feature-visibility validator keeps features clear of this campus fraction; the button must stay inside it.
const geometry = await readFile(new URL('../../src/data/campusGeometry.ts', import.meta.url), 'utf8');
const STATS_OVERLAY = JSON.parse(geometry.match(/'stats-button':\s*(\[[^\]]+\])/)[1]);
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
async function capture(name) {
  const { data } = await send('Page.captureScreenshot', { format: 'png' });
  await writeFile(path.join(out, `${name}.png`), Buffer.from(data, 'base64'));
}
/** A phone is a touch-only small screen; no ?device override, so the app's media-query detection decides. */
async function open(width, height, { phone = true, query = '' } = {}) {
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: phone ? 2 : 1, mobile: phone,
    screenOrientation: { type: width > height ? 'landscapePrimary' : 'portraitPrimary', angle: phone && width > height ? 90 : 0 } });
  await send('Emulation.setTouchEmulationEnabled', phone ? { enabled: true, maxTouchPoints: 5 } : { enabled: false });
  await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  await send('Page.navigate', { url: new URL(query, app).href });
  await waitFor(`document.readyState === 'complete' && !!document.querySelector('[data-phone-shell]')`);
  await evaluate('document.fonts.ready.then(() => true)');
  await pause(300);
}
async function touch(selector) {
  await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({ block: 'center' })`);
  await pause(150);
  const point = await evaluate(`(() => {
    const e = document.querySelector(${JSON.stringify(selector)}), r = e.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    if (!e.contains(document.elementFromPoint(x, y))) throw new Error('Target obscured: ${selector.replace(/'/g, '')} at ' + Math.round(x) + ',' + Math.round(y) + ' by ' + document.elementFromPoint(x, y)?.outerHTML.slice(0, 160));
    return { x, y };
  })()`);
  await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...point, radiusX: 2, radiusY: 2, force: 1 }] });
  await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await pause(150);
}
/** Clicks the one button in `scope` whose text contains `text`. */
async function button(text, scope = 'document') {
  await evaluate(`(() => {
    const found = [...${scope}.querySelectorAll('button')].filter(b => b.textContent.includes(${JSON.stringify(text)}));
    if (found.length !== 1) throw new Error('Expected one button: ${text.replace(/'/g, '')}, got ' + found.length);
    found[0].click();
  })()`);
  await pause(400);
}
const fits = () => evaluate(`(() => {
  const shell = document.querySelector('[data-phone-shell]');
  const nav = [...document.querySelectorAll('nav')].filter(n => n.getBoundingClientRect().height > 0 && !n.closest('dialog')).at(-1);
  return { shell: shell.dataset.phoneShell, chrome: !!shell.querySelector('[data-phone-status-bar]'),
    toolButton: !!document.querySelector('.game-top-bar button[aria-haspopup="dialog"]'), demoNav: !!document.querySelector('nav[aria-label="서비스 흐름"]'),
    navBottom: nav ? Math.round(nav.getBoundingClientRect().bottom) : null, vh: innerHeight,
    pageScroll: document.documentElement.scrollHeight - innerHeight };
})()`);
const contribution = () => evaluate(`Number(document.querySelector('[data-testid="campus-stats-button"]').getAttribute('aria-label').replace(/[^0-9]/g, ''))`);

async function measureMain(width, height) {
  await open(width, height);
  const r = await evaluate(`(() => {
    const nav = document.querySelector('nav[aria-label="주요 메뉴"]'), nb = nav.getBoundingClientRect();
    const buttons = [...nav.querySelectorAll('button')].map(b => { const x = b.getBoundingClientRect();
      return { label: b.textContent.trim(), ok: x.top >= 0 && x.bottom <= innerHeight && x.width > 0 && document.elementFromPoint(x.left + x.width / 2, x.top + x.height / 2)?.closest('button') === b }; });
    const area = document.querySelector('[aria-label="캠퍼스 클릭 영역"]'), campus = area.getBoundingClientRect();
    const main = document.querySelector('main[data-game-main]');
    const stats = document.querySelector('[data-testid="campus-stats-button"]');
    const value = stats.querySelector('[data-testid="campus-stats-contribution"]');
    const overlap = (a, b) => a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    const others = [document.querySelector('[aria-label="우리 학교 방명록 보기"]'),
      [...area.children].find(e => e.classList.contains('top-2.5') && e.classList.contains('right-2.5'))].filter(Boolean).map(e => e.getBoundingClientRect());
    const measure = () => { const s = stats.getBoundingClientRect();
      return { clipped: [...stats.querySelectorAll('span')].some(e => e.scrollWidth > e.clientWidth + 0.5) || s.right > campus.right - 4 || s.bottom > campus.bottom - 4,
        overlap: others.some(o => overlap(o, s)),
        frac: [(s.left - campus.left - 4) / (campus.width - 8), (s.top - campus.top - 4) / (campus.height - 8)].map(v => +v.toFixed(3)) }; };
    const now = measure(), shown = value.textContent;
    value.textContent = '1234.5T'; // widest label formatContribution produces below 10,000T (same length as 999,999)
    const widest = measure(); value.textContent = shown;
    return { shell: document.querySelector('[data-phone-shell]').dataset.phoneShell, navBottom: Math.round(nb.bottom), vh: innerHeight,
      buttons, pageScroll: document.documentElement.scrollHeight - innerHeight, mainOverflow: main.scrollHeight - main.clientHeight,
      campus: [Math.round(campus.width), Math.round(campus.height)], now, widest };
  })()`);
  const name = `${width}x${height}`;
  await check(`${name} full shell`, () => assert.equal(r.shell, 'full'));
  await check(`${name} nav inside viewport`, () => assert.ok(r.navBottom <= r.vh, `${r.navBottom} > ${r.vh}`));
  await check(`${name} five nav buttons hit`, () => assert.deepEqual(r.buttons.filter(b => !b.ok), [], `of ${r.buttons.length}`));
  await check(`${name} five nav buttons`, () => assert.equal(r.buttons.length, 5));
  await check(`${name} no page scroll`, () => assert.ok(r.pageScroll <= 0, `${r.pageScroll}`));
  await check(`${name} no main overflow`, () => assert.ok(r.mainOverflow <= 0, `${r.mainOverflow}`));
  await check(`${name} campus >= 220px`, () => assert.ok(r.campus[1] >= 220, `${r.campus}`));
  for (const [label, m] of [['stats button', r.now], ['widest stats label', r.widest]]) {
    await check(`${name} ${label} not clipped or overlapping`, () => assert.ok(!m.clipped && !m.overlap, JSON.stringify(m)));
    await check(`${name} ${label} inside validator overlay`, () => assert.ok(m.frac[0] >= STATS_OVERLAY[0] && m.frac[1] >= STATS_OVERLAY[1], `${m.frac} vs ${STATS_OVERLAY}`));
  }
  results.push({ size: name, ...r, buttons: r.buttons.length });
  await capture(`main-${name}`);
}

async function statsSheet(width, height) {
  const name = `${width}x${height}`;
  await open(width, height);
  const label = await evaluate(`document.querySelector('[data-testid="campus-stats-button"]').getAttribute('aria-label')`);
  const shown = await evaluate(`document.querySelector('[data-testid="campus-stats-contribution"]').textContent`);
  await check(`${name} accessible name has the full number`, () => assert.match(label, /^상세 스탯 보기, 보유 기여도 [\d,]+$/));
  await check(`${name} compact label below 1M is the full number`, () => assert.equal(shown, label.replace(/^.* /, '')));
  const before = await contribution();
  await touch('[data-testid="campus-stats-button"]');
  await waitFor(`!!document.querySelector('dialog[open][aria-label="상세 스탯"]')`);
  await check(`${name} opening the sheet does not score`, async () => assert.equal(await contribution(), before));
  const sheet = await evaluate(`(() => {
    const d = document.querySelector('dialog[open]'), r = d.getBoundingClientRect(), body = d.querySelector(':scope > div');
    const shop = [...d.querySelectorAll('button')].find(b => b.textContent.includes('상점에서 강화하기'));
    shop.scrollIntoView({ block: 'nearest' });
    const s = shop.getBoundingClientRect();
    return { modal: d.matches(':modal'), top: Math.round(r.top), bottom: Math.round(r.bottom), vh: innerHeight,
      bodyScroll: body.scrollHeight - body.clientHeight, shopReachable: s.top >= r.top && s.bottom <= r.bottom,
      pageScroll: document.documentElement.scrollHeight - innerHeight };
  })()`);
  await check(`${name} sheet is modal inside viewport`, () => assert.ok(sheet.modal && sheet.top >= 0 && sheet.bottom <= sheet.vh + 1, JSON.stringify(sheet)));
  await check(`${name} sheet content reachable by scrolling`, () => assert.ok(sheet.shopReachable, JSON.stringify(sheet)));
  await capture(`stats-sheet-${name}`);
  await pressKey(send, 'Escape'); await pause(150);
  await waitFor(`!document.querySelector('dialog[open]')`);
  await check(`${name} Esc returns focus to the stats button`, async () => assert.equal(await evaluate(`document.activeElement?.dataset.testid ?? null`), 'campus-stats-button'));
  await touch('[data-testid="campus-stats-button"]');
  await waitFor(`!!document.querySelector('dialog[open][aria-label="상세 스탯"]')`);
  await button('상점에서 강화하기', `document.querySelector('dialog[open]')`);
  await check(`${name} shop button closes the sheet and opens the shop tab`, async () => assert.deepEqual(await evaluate(`({
    open: !!document.querySelector('dialog[open]'), campus: !!document.querySelector('[data-testid="campus-stats-button"]'),
    shop: document.querySelector('[data-game-main]').textContent.includes('업그레이드') })`), { open: false, campus: false, shop: true }));
  results.push({ size: name, statsSheet: sheet, label, shown });
}

/** The national map shows every region cluster and score ribbon at each size (the map crops to cover its area). */
async function openTools() {
  await touch('.game-top-bar button[aria-haspopup="dialog"]');
  await waitFor(`!!document.querySelector('dialog[open][aria-label="테스트 도구"]')`);
}

async function mapOverview(width, height) {
  const name = `${width}x${height}`;
  await open(width, height);
  await openTools();
  await button('🗺️ 단일 폰 지도', `document.querySelector('dialog[open]')`);
  const r = await evaluate(`(() => {
    const a = document.querySelector('[data-testid="map-area"]').getBoundingClientRect();
    const clipped = e => { const r = e.getBoundingClientRect(); return r.top < a.top || r.bottom > a.bottom || r.left < a.left || r.right > a.right; };
    return { area: [Math.round(a.width), Math.round(a.height)],
      clusters: [...document.querySelectorAll('[data-cluster-region]')].filter(clipped).map(e => e.dataset.clusterRegion),
      ribbons: [...document.querySelectorAll('[data-top-rank]')].filter(clipped).map(e => e.dataset.topRank) };
  })()`);
  await check(`${name} map overview shows every cluster and ribbon`, () => assert.deepEqual({ clusters: r.clusters, ribbons: r.ribbons }, { clusters: [], ribbons: [] }, `area ${r.area}`));
  results.push({ size: name, map: r });
  await capture(`map-${name}`);
}

async function serviceScreens(width, height) {
  const name = `${width}x${height}`;
  const screens = ['🎮 메인 게임 화면', '📱 순차 온보딩', '🗺️ 단일 폰 지도', '🏆 랭킹 화면', '🏪 상점 & 강화', '🤝 연합 신청', '현재 연합', '⚔️ 대결 신청', '📊 대결 현황'];
  for (const screen of [...screens, 'visiting']) {
    await open(width, height);
    await openTools();
    await button(screen === 'visiting' ? '🗺️ 단일 폰 지도' : screen, `document.querySelector('dialog[open]')`);
    await check(`${name} ${screen}: menu pick closes the mock sheet`, async () => assert.equal(await evaluate(`!!document.querySelector('dialog[open]')`), false));
    if (screen === 'visiting') {
      const region = await evaluate(`(() => { const a = document.querySelector('[data-testid="map-area"]').getBoundingClientRect();
        return [...document.querySelectorAll('[data-cluster-region]')].find(e => { const r = e.getBoundingClientRect(); return r.top >= a.top && r.bottom <= a.bottom; })?.dataset.clusterRegion; })()`);
      await touch(`[data-cluster-region="${region}"]`);
      await touch('button[data-school-id]');
      await waitFor(`!!document.querySelector('[aria-label$="캠퍼스 (관전 모드)"]')`);
      await pause(300);
    }
    const r = await fits();
    await check(`${name} ${screen}: full shell without chrome`, () => assert.ok(r.shell === 'full' && !r.chrome && r.toolButton && !r.demoNav, JSON.stringify(r)));
    await check(`${name} ${screen}: no page scroll`, () => assert.ok(r.pageScroll <= 0, JSON.stringify(r)));
    await check(`${name} ${screen}: nav inside viewport`, () => assert.ok(r.navBottom === null || r.navBottom <= r.vh, JSON.stringify(r)));
    results.push({ size: name, screen, ...r });
    await capture(`screen-${name}-${screen.replace(/[^\w가-힣]/g, '')}`);
  }
  for (const comparison of ['💬 2-Phone 채팅 비교', '📜 2-Phone 우리 방명록']) {
    await open(width, height);
    await openTools();
    await button(comparison, `document.querySelector('dialog[open]')`);
    const r = await evaluate(`({ shells: [...document.querySelectorAll('[data-phone-shell]')].map(e => e.dataset.phoneShell),
      stats: document.querySelectorAll('[data-testid="campus-stats-button"]').length,
      oldPanel: document.body.innerText.includes('내 누적 기여 점수'), pageScroll: document.documentElement.scrollHeight - innerHeight })`);
    await check(`${name} ${comparison}: framed demo, same campus as the new main`, () => assert.ok(r.shells.length > 0 && r.shells.every(s => s === 'framed') && r.stats > 0 && !r.oldPanel, JSON.stringify(r)));
    results.push({ size: name, comparison, ...r });
  }
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

  // Narrow windows use the real single-column flow regardless of pointer type. A wide game has no phone chrome.
  for (const [label, width, height, phone, query, shell] of [
    ['touch phone', 390, 844, true, '', 'full'], ['narrow mouse window', 390, 844, false, '', 'full'],
    ['desktop', 1280, 1000, false, '', 'embedded'], ['phone ?device=framed', 390, 844, true, '?device=framed', 'framed'],
    ['desktop ?device=full', 1280, 1000, false, '?device=full', 'full']]) {
    await open(width, height, { phone, query });
    const r = await fits();
    await check(`detection: ${label}`, () => assert.ok(r.shell === shell && r.toolButton && !r.demoNav && r.chrome === (shell === 'framed'), JSON.stringify(r)));
    results.push({ detection: label, ...r });
  }
  await capture('desktop-1280x1000-full-override');

  for (const [width, height] of SIZES) await measureMain(width, height);
  for (const [width, height] of SIZES) await mapOverview(width, height);
  for (const [width, height] of [[390, 844], [360, 584]]) await statsSheet(width, height);
  for (const [width, height] of [[390, 844], [360, 584]]) await serviceScreens(width, height);

  // Landscape phone keeps the full shell (min-height 480px) and scrolls the page, as approved for M6.
  await open(844, 390);
  const landscape = await fits();
  await check('landscape 844x390: full shell, page scrolls to the nav', () => assert.ok(landscape.shell === 'full' && landscape.pageScroll > 0, JSON.stringify(landscape)));
  results.push({ landscape });
  await capture('landscape-844x390');

  await writeFile(path.join(out, 'report.json'), JSON.stringify({ browser: version.Browser, app, statsOverlay: STATS_OVERLAY, results, failures,
    notCovered: ['Real iPhone/Android (#31): browser chrome height, safe-area insets, keyboard, rotation on device', 'M/B/T values above 1M are covered by unit tests (formatContribution, campusStats); here only the widest label width'] }, null, 2));
  console.log(JSON.stringify({ out, checks: results.length, failures }, null, 2));
  assert.equal(failures.length, 0, 'Full-screen phone checks failed; see report.json');
} finally {
  for (const call of pending.values()) clearTimeout(call.timer);
  ws?.close();
  if (target) await fetch(`${endpoint}/json/close/${target.id}`).catch(() => {});
}
