/** Focused M6 sheet regressions. Requires existing preview and Chromium CDP;
 * never starts a browser or changes its security settings. Physical Safari retest remains required.
 * M6_APP_URL=http://host:4173 M6_CDP_URL=http://127.0.0.1:9222 node tests/e2e/sheet-interactions.mjs
 */
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pressKey } from './cdp-input.mjs';
import { MOCK_TOOL_BUTTON, selectMockScreen } from './cdp-mock-tools.mjs';

// T2: touch emulation matches (pointer: coarse) and turns on the full-screen phone mode, which hides the demo
// menus these checks drive. They cover the framed demo screens, so pin ?device=framed (mobile-full.mjs covers full).
const app = (() => { const url = new URL(process.env.M6_APP_URL ?? 'http://127.0.0.1:4173'); if (!url.searchParams.has('device')) url.searchParams.set('device', 'framed'); return url.href; })();
const endpoint = process.env.M6_CDP_URL ?? 'http://127.0.0.1:9222';
const out = path.resolve(process.env.M6_QA_OUT ?? '.agent-workflow/261108-2125-m6-browser-qa/sheet-interactions');
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
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
}
async function waitFor(expression) {
  for (let i = 0; i < 50; i++) { if (await evaluate(expression)) return; await pause(100); }
  throw new Error(`Condition not met: ${expression}`);
}
async function button(text) {
  await evaluate(`(() => {
    const buttons = [...document.querySelectorAll('button')].filter(b=>b.textContent.trim()===${JSON.stringify(text)});
    if(buttons.length!==1) throw new Error('Expected one button: '+${JSON.stringify(text)});
    buttons[0].click();
  })()`);
  await pause(100);
}
async function drag(x, startY, endY) {
  await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y: startY }] });
  for (let i = 1; i <= 12; i++) {
    await send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: startY + (endY - startY) * i / 12 }] });
    await pause(25);
  }
  await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await pause(500);
}
async function check(name, run) {
  try { await run(); } catch (error) { failures.push({ name, message: error.message }); }
}
async function capture(name) {
  const { data } = await send('Page.captureScreenshot', { format: 'png' });
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
  const list = 'dialog div[class*="overflow-y-auto"][class*="px-2.5"]';
  for (const [width, height] of [[360,740],[375,812],[390,844],[812,375],[844,390]]) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 2, mobile: true,
      screenOrientation: { type: width > height ? 'landscapePrimary' : 'portraitPrimary', angle: width > height ? 90 : 0 } });
    await send('Emulation.setTouchEmulationEnabled', { enabled: true });
    await send('Page.navigate', { url: app });
    await waitFor(`document.readyState==='complete' && !!document.querySelector(${JSON.stringify(MOCK_TOOL_BUTTON)})`);
    await evaluate('document.fonts.ready.then(()=>true)');
    await selectMockScreen(evaluate, '🎮 메인 게임 화면');
    await evaluate(`window.scrollTo(0,30)`);
    const beforeOpen = await evaluate('scrollY');
    await evaluate(`document.querySelector('[title="우리 학교 방명록 보기"]').click()`);
    await waitFor(`!!document.querySelector('dialog[open]')`);
    const metrics = await evaluate(`(() => {
      const list=document.querySelector(${JSON.stringify(list)}),r=list.getBoundingClientRect();
      return {scrollHeight:list.scrollHeight,clientHeight:list.clientHeight,top:r.top,bottom:r.bottom,x:r.left+r.width/2};
    })()`);
    const background = await evaluate(`({top:document.querySelector('[data-game-main]').getBoundingClientRect().top,main:document.querySelector('[data-game-main]').scrollTop})`);
    const start = Math.min(metrics.bottom - 15, height - 20), end = Math.max(metrics.top + 15, start - 100);
    assert.ok(start > end, 'Visible list has room for a touch drag');
    await drag(metrics.x, start, end);
    const afterList = await evaluate(`({scrollTop:document.querySelector(${JSON.stringify(list)}).scrollTop,top:document.querySelector('[data-game-main]').getBoundingClientRect().top})`);
    await check(`${width}x${height}: own list drag`, () => {
      if (metrics.scrollHeight > metrics.clientHeight + 1) assert.ok(afterList.scrollTop > 0, 'Overflowing list must scroll');
      else assert.ok(metrics.scrollHeight <= metrics.clientHeight + 1, 'Fitting content needs no artificial scrolling');
      assert.ok(Math.abs(afterList.top-background.top)<1, 'Dragging the list must not move the page behind it');
    });
    // Test both scroll boundaries, then the native backdrop. Neither may move the page.
    await evaluate(`document.querySelector(${JSON.stringify(list)}).scrollTop=0`);
    await drag(metrics.x, Math.max(metrics.top+15,20), Math.min(metrics.bottom-15,metrics.top+115));
    await evaluate(`document.querySelector(${JSON.stringify(list)}).scrollTop=99999`);
    await drag(metrics.x, start, end);
    await drag(2, height-30, Math.max(20,height-180));
    const afterBoundary = await evaluate(`({top:document.querySelector('[data-game-main]').getBoundingClientRect().top,main:document.querySelector('[data-game-main]').scrollTop})`);
    await check(`${width}x${height}: no background scroll`, () => {
      assert.ok(Math.abs(afterBoundary.top-background.top)<1, `Background moved: ${background.top} → ${afterBoundary.top}`);
      assert.equal(afterBoundary.main, background.main);
    });
    await capture(`${width}x${height}-own-guestbook`);
    await pressKey(send, 'Escape'); await waitFor(`!document.querySelector('dialog[open]')`);
    await check(`${width}x${height}: scroll restoration`, async () => {
      assert.ok(Math.abs(await evaluate('scrollY')-beforeOpen)<1, 'Closing restores the pre-open page position');
    });
    results.push({ width, height, beforeOpen, metrics, background, afterList, afterBoundary });
  }
  await selectMockScreen(evaluate, '🗺️ 단일 폰 지도');
  await evaluate(`document.querySelector('button[aria-label="KAIST 방문"]').click()`);
  await evaluate(`document.querySelector('[aria-label="KAIST 방명록 보기"]').click()`);
  await waitFor(`!!document.querySelector('dialog[open] textarea')`);
  await button('최신순');
  await evaluate(`(() => {
    const input=document.querySelector('dialog textarea');
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(input,'M6 좋아요 초기값 검사');
    input.dispatchEvent(new Event('input',{bubbles:true}));
  })()`);
  await button('남기기');
  const likeState = await evaluate(`(() => {
    const p=[...document.querySelectorAll('dialog p')].find(p=>p.textContent==='M6 좋아요 초기값 검사');
    const button=p.parentElement.querySelector('button');
    return {count:button.textContent.trim(),filled:button.querySelector('svg').classList.contains('fill-[#e11d48]')};
  })()`);
  await check('new guestbook has no automatic like', () => assert.deepEqual(likeState, { count: '0', filled: false }));
  results.push({ newEntryLike: likeState });
  await capture('new-guestbook-like');
  await writeFile(path.join(out,'report.json'), JSON.stringify({ browser: version.Browser, results, failures }, null, 2));
  console.log(JSON.stringify({ out, results, failures }, null, 2));
  assert.equal(failures.length, 0, 'Sheet regressions failed; see report.json');
} finally {
  for (const call of pending.values()) clearTimeout(call.timer);
  ws?.close();
  if (target) await fetch(`${endpoint}/json/close/${target.id}`).catch(()=>{});
}
