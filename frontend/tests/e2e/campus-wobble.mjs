/** T6 campus building wobble in a real browser (docs/261110-1005-mockup_remaining_tasks_plan.md §9, criterion 8).
 * Requires an existing preview and Chromium CDP; opens and closes only its own tab.
 * WOBBLE_APP_URL=http://127.0.0.1:4173 WOBBLE_CDP_URL=http://127.0.0.1:9222 WOBBLE_QA_OUT=out node tests/e2e/campus-wobble.mjs
 * Lv.7 and Lv.9 have three buildings each. Checks: only building wrappers animate, the click area never moves,
 * rapid taps keep one animation per wrapper, night lights move with their buildings, reduced motion stays still.
 * Frame timing is reported, not asserted: headless Chromium without a GPU is not a phone.
 */
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { openMockTools, selectMockScreen } from './cdp-mock-tools.mjs';

const app = process.env.WOBBLE_APP_URL ?? process.env.M6_APP_URL ?? 'http://127.0.0.1:4173';
const endpoint = process.env.WOBBLE_CDP_URL ?? process.env.M6_CDP_URL ?? 'http://127.0.0.1:9222';
const out = path.resolve(process.env.WOBBLE_QA_OUT ?? '.agent-workflow/261110-t6-campus-wobble/e2e');
const AREA = '[aria-label="캠퍼스 클릭 영역"]';
const TARGETS = '[data-wobble], [data-wobble-lights]';
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
async function button(text) {
  await evaluate(`(() => {
    const found = [...document.querySelectorAll('button')].filter(b => b.textContent.includes(${JSON.stringify(text)}));
    if (found.length !== 1) throw new Error('Expected one button: ${text.replace(/'/g, '')}, got ' + found.length);
    found[0].click();
  })()`);
  await pause(300);
}
/** Desktop framed view (the demo tools and growth sheet live there), Lv.`level`, sky `phase`. */
async function open(level, { phase = 'day', reducedMotion = false } = {}) {
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 1000, deviceScaleFactor: 2, mobile: false });
  await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: reducedMotion ? 'reduce' : 'no-preference' }] });
  await send('Page.navigate', { url: app });
  await waitFor(`document.readyState === 'complete' && !!document.querySelector('${AREA}')`);
  await evaluate('document.fonts.ready.then(() => true)');
  await openMockTools(evaluate);
  await evaluate(`(() => { const s = document.querySelector('select[aria-label="[시연] 시간대"]');
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(s, ${JSON.stringify(phase)});
    s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  await selectMockScreen(evaluate, '🏛️ 10단계 캠퍼스 성장 시트 (5x2)');
  await evaluate(`document.querySelector('[title="Lv.${level} 단계 확인"]').click()`);
  await pause(50);
  await button('성장 시연: 학교 점수를 기준값으로 설정');
  await button('인게임으로');
  await waitFor(`document.querySelector('[data-testid="campus-scene"]')?.dataset.level === '${level}'`);
  await evaluate(`Promise.all([...document.querySelectorAll('${AREA} image')].map(i => i.decode?.().catch(() => {})))`);
  await pause(300);
}
const center = () => evaluate(`(() => { const r = document.querySelector('${AREA}').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; })()`);
async function tap(point) {
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...point, button: 'left', clickCount: 1 });
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...point, button: 'left', clickCount: 1 });
}
/** Running wobble animations, the click area's box and computed transform, read in one go. */
const state = () => evaluate(`(() => {
  const area = document.querySelector('${AREA}'), r = area.getBoundingClientRect();
  const animated = document.getAnimations().filter(a => a.playState !== 'finished' && a.effect?.target?.matches?.('${TARGETS}'));
  return { targets: document.querySelectorAll('${TARGETS}').length, buildings: document.querySelectorAll('[data-wobble]').length,
    lights: document.querySelectorAll('[data-wobble-lights]').length, animated: animated.length,
    others: document.getAnimations().filter(a => a.effect?.target && !a.effect.target.matches('${TARGETS}') && a.effect.target.closest?.('[data-testid="campus-scene"]')).length,
    area: [r.left, r.top, r.width, r.height].map(Math.round), transform: getComputedStyle(area).transform,
    contribution: document.querySelector('[data-testid="campus-stats-button"]').getAttribute('aria-label') };
})()`);

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

  for (const level of [7, 9]) {
    for (const phase of ['day', 'night']) {
      const name = `Lv.${level} ${phase}`;
      await open(level, { phase });
      const point = await center();
      const idle = await state();
      await tap(point);
      const tapped = await state();
      await check(`${name}: three building wrappers`, () => assert.equal(tapped.buildings, 3));
      await check(`${name}: night lights wrap lit buildings only`, () => assert.ok(phase === 'day' || (tapped.lights > 0 && tapped.lights <= 3), JSON.stringify(tapped)));
      await check(`${name}: one tap animates every wrapper once`, () => assert.equal(tapped.animated, tapped.targets, JSON.stringify(tapped)));
      await check(`${name}: nothing else in the scene animates`, () => assert.equal(tapped.others, 0, JSON.stringify(tapped)));
      await check(`${name}: click area does not move`, () => assert.ok(tapped.transform === 'none' && JSON.stringify(tapped.area) === JSON.stringify(idle.area), JSON.stringify({ idle, tapped })));

      // Frames of one wobble, paused at fixed times so the strip does not depend on capture latency.
      if (phase === 'day') {
        await pause(500);
        await tap(point);
        const clip = await evaluate(`(() => { const r = document.querySelector('${AREA}').getBoundingClientRect(); return { x: r.left, y: r.top, width: r.width, height: r.height, scale: 1 }; })()`);
        for (const t of [0, 60, 120, 180, 240, 320]) {
          await evaluate(`document.getAnimations().filter(a => a.effect?.target?.matches?.('${TARGETS}')).forEach(a => { a.pause(); a.currentTime = ${t}; })`);
          await pause(50);
          const { data } = await send('Page.captureScreenshot', { format: 'png', clip });
          await writeFile(path.join(out, `lv${level}-t${String(t).padStart(3, '0')}.png`), Buffer.from(data, 'base64'));
        }
        await evaluate(`document.getAnimations().filter(a => a.effect?.target?.matches?.('${TARGETS}')).forEach(a => a.finish())`);

        // Rapid taps at 20/s for 2s: animations must not pile up; frame gaps are recorded for the report.
        await evaluate(`window.__frames = []; (function loop(t) { window.__frames.push(t); if (window.__frames.length < 2000) requestAnimationFrame(loop); })(performance.now())`);
        let peak = 0;
        for (let i = 0; i < 40; i++) {
          await tap(point);
          if (i % 5 === 4) peak = Math.max(peak, (await state()).animated);
          await pause(50);
        }
        const frames = await evaluate(`(() => { const f = window.__frames, gaps = f.slice(1).map((t, i) => t - f[i]).sort((a, b) => a - b);
          return { frames: f.length, p50: +gaps[Math.floor(gaps.length * 0.5)].toFixed(1), p95: +gaps[Math.floor(gaps.length * 0.95)].toFixed(1), max: +gaps.at(-1).toFixed(1), over50: gaps.filter(g => g > 50).length }; })()`);
        const after = await state();
        await check(`${name}: rapid taps keep at most one animation per wrapper`, () => assert.ok(peak <= after.targets, `${peak} > ${after.targets}`));
        results.push({ name, idle, tapped, rapid: { taps: 40, intervalMs: 50, peakAnimations: peak, frames } });
      } else {
        results.push({ name, idle, tapped });
      }
    }
  }

  await open(9, { reducedMotion: true });
  await tap(await center());
  const reduced = await state();
  await check('Lv.9 reduced motion: no wobble', () => assert.equal(reduced.animated, 0, JSON.stringify(reduced)));
  results.push({ name: 'Lv.9 reduced motion', tapped: reduced });

  await writeFile(path.join(out, 'report.json'), JSON.stringify({ browser: version.Browser, app, results, failures,
    notCovered: ['Real phones: frame timing on device GPUs (headless Chromium here)', 'Visual judgement of the wobble amount: see the lv*-t*.png strips'] }, null, 2));
  console.log(JSON.stringify({ out, results: results.map(r => ({ name: r.name, animated: r.tapped.animated, targets: r.tapped.targets, rapid: r.rapid })), failures }, null, 2));
  assert.equal(failures.length, 0, 'Campus wobble checks failed; see report.json');
} finally {
  for (const call of pending.values()) clearTimeout(call.timer);
  ws?.close();
  if (target) await fetch(`${endpoint}/json/close/${target.id}`).catch(() => {});
}
