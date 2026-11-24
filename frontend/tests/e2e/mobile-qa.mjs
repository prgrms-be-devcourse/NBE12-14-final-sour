/** M6 regression runner. Requires an already running Chromium CDP endpoint and Vite preview.
 * M6_APP_URL=http://127.0.0.1:4173 M6_CDP_URL=http://127.0.0.1:9222 node tests/e2e/mobile-qa.mjs
 * This runner never launches a browser or changes sandbox settings. A successful DOM test is not
 * a substitute for this pass or physical iOS/Android testing. It creates/closes only its own tab.
 */
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pressKey } from './cdp-input.mjs';
import { runMobileFlows } from './mobile-flows.mjs';
import { MOCK_TOOL_BUTTON, selectMockScreen } from './cdp-mock-tools.mjs';

const screen = (label) => selectMockScreen(evaluate, label);

const endpoint = process.env.M6_CDP_URL ?? 'http://127.0.0.1:9222';
// T2: touch emulation matches (pointer: coarse) and turns on the full-screen phone mode, which hides the demo
// menus these checks drive. They cover the framed demo screens, so pin ?device=framed (mobile-full.mjs covers full).
const app = (() => { const url = new URL(process.env.M6_APP_URL ?? 'http://127.0.0.1:4173'); if (!url.searchParams.has('device')) url.searchParams.set('device', 'framed'); return url.href; })();
const out = path.resolve(process.env.M6_QA_OUT ?? '.agent-workflow/261108-2125-m6-browser-qa/browser');
const results = [];
const errors = [];
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let ws;
let target;
let nextId = 0;
const pending = new Map();

function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++nextId;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 10000);
    pending.set(id, { resolve, reject, timer });
    ws.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const response = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (response.exceptionDetails) throw new Error(JSON.stringify(response.exceptionDetails));
  return response.result.value;
}
async function waitFor(expression) {
  for (let attempt = 0; attempt < 50; attempt++) {
    if (await evaluate(expression)) return;
    await pause(100);
  }
  throw new Error(`Condition not met: ${expression}`);
}
async function button(name) {
  await evaluate(`(() => {
    const matches = [...document.querySelectorAll('button')].filter(b => b.textContent.trim() === ${JSON.stringify(name)});
    if (matches.length !== 1) throw new Error('Expected one button: ' + ${JSON.stringify(name)} + ', got ' + matches.length);
    matches[0].click();
  })()`);
  await pause(100);
}
async function fill(selector, value) {
  await evaluate(`(() => {
    const input = document.querySelector(${JSON.stringify(selector)});
    if (!input) throw new Error('Input not found');
    const proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(input, ${JSON.stringify(value)});
    input.dispatchEvent(new Event('input', { bubbles: true }));
  })()`);
}
async function key(key, shift = false) {
  await pressKey(send, key, shift);
  await pause(40);
}
async function viewport(width, height) {
  const mobile = width !== 1280;
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: mobile ? 2 : 1, mobile, screenOrientation: { type: width > height ? 'landscapePrimary' : 'portraitPrimary', angle: mobile && width > height ? 90 : 0 } });
  await send('Emulation.setTouchEmulationEnabled', { enabled: mobile });
  await pause(100);
}
async function load() {
  await send('Page.navigate', { url: app });
  await waitFor(`document.readyState === 'complete' && !!document.querySelector(${JSON.stringify(MOCK_TOOL_BUTTON)})`);
  await evaluate('document.fonts.ready.then(() => true)');
}
async function screenshot(name, selector) {
  let clip;
  if (selector) {
    await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center'})`);
    await pause(100);
    clip = await evaluate(`(() => { const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect(); return { x:r.left+scrollX, y:r.top+scrollY, width:r.width, height:r.height, scale:1 }; })()`);
  }
  const { data } = await send('Page.captureScreenshot', { format: 'png', ...(clip ? { clip, captureBeyondViewport: true } : {}) });
  await writeFile(path.join(out, `${name}.png`), Buffer.from(data, 'base64'));
}
async function touch(selector) {
  await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({ block: 'center' })`);
  await pause(100);
  const point = await evaluate(`(() => {
    const e = document.querySelector(${JSON.stringify(selector)}), r = e.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    if (!e.contains(document.elementFromPoint(x, y))) throw new Error('Target obscured');
    return { x, y };
  })()`);
  await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...point, radiusX: 2, radiusY: 2, force: 1 }] });
  await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await pause(100);
}
async function checkSheet(selector, label, width, height) {
  await evaluate(`document.querySelector(${JSON.stringify(selector)}).focus()`);
  for (const activation of ['Enter', ' ']) {
    await key(activation);
    await waitFor(`!!document.querySelector('dialog[open]')`);
    assert.equal(await evaluate(`document.querySelector('dialog[open]').matches(':modal')`), true);
    assert.equal(await evaluate(`document.querySelector('dialog[open]').getAttribute('aria-label')`), label);
    const rect = await evaluate(`(() => { const r = document.querySelector('dialog[open]').getBoundingClientRect(); return { top:r.top, bottom:r.bottom, left:r.left, right:r.right }; })()`);
    assert.ok(rect.top >= 0 && rect.bottom <= height + 1 && rect.left >= 0 && rect.right <= width + 1, JSON.stringify(rect));
    // Native modal blocks the document, not browser chrome (HTML sequential focus navigation).
    // Never permit a background control; a body sentinel must return on the very next Tab.
    await evaluate(`(() => {
      const before = document.activeElement;
      document.querySelector(${JSON.stringify(selector)}).focus();
      if (document.activeElement !== before) throw new Error('Modal background accepted focus');
    })()`);
    for (let i = 0; i < 25; i++) {
      await key('Tab', i >= 12);
      const focus = await evaluate(`({ inModal: document.querySelector('dialog[open]').contains(document.activeElement), body: document.activeElement === document.body })`);
      if (!focus.inModal) {
        assert.equal(focus.body, true, 'No background control may receive focus');
        await key('Tab', i >= 12);
      }
      assert.equal(await evaluate(`document.querySelector('dialog[open]').contains(document.activeElement)`), true, 'Tab returns to the sheet, never background controls');
    }
    await screenshot(`${width}x${height}-${label}-${activation === 'Enter' ? 'enter' : 'space'}`);
    await key('Escape');
    await waitFor(`!document.querySelector('dialog[open]')`);
    assert.equal(await evaluate(`document.activeElement === document.querySelector(${JSON.stringify(selector)})`), true, 'Focus returns to opener');
  }
}
async function enterCode(code) {
  for (let i = 0; i < 6; i++) await fill(`[aria-label="인증 코드 ${i + 1}번째 자리"]`, code[i]);
  await button('코드 확인');
}

try {
  await mkdir(out, { recursive: true });
  const version = await (await fetch(`${endpoint}/json/version`)).json();
  target = await (await fetch(`${endpoint}/json/new?about:blank`, { method: 'PUT' })).json();
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  ws.onmessage = ({ data }) => {
    const message = JSON.parse(data);
    const call = pending.get(message.id);
    if (call) {
      clearTimeout(call.timer);
      pending.delete(message.id);
      if (message.error) call.reject(new Error(JSON.stringify(message.error)));
      else call.resolve(message.result);
    }
    if (message.method === 'Runtime.exceptionThrown' || (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error')) errors.push(message);
  };
  await send('Runtime.enable');
  await send('Page.enable');
  const sizes = [[360, 740], [390, 844], [430, 932], [740, 360], [844, 390], [932, 430], [1280, 800]];
  const screens = ['🎮 메인 게임 화면', '📱 순차 온보딩 (Single Phone)', '🗺️ 단일 폰 지도', '🏆 랭킹 화면 (Single Phone)', '🏪 상점 & 강화 (Single Phone)', '🤝 연합 신청 (Alliance Apply)', '🛡️ 현재 연합 (Current Alliance)', '⚔️ 대결 신청 (Battle Apply)', '📊 대결 현황 (Battle Status)'];
  for (const [width, height] of sizes) {
    await viewport(width, height);
    await load();
    for (const [index, name] of screens.entries()) {
      await screen(name);
      const overflow = await evaluate('Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - innerWidth');
      assert.ok(overflow <= 1, `${width}x${height} ${name}: horizontal overflow ${overflow}`);
      await screenshot(`${width}x${height}-screen-${index}`);
      const frameSelector = '[data-phone-shell]';
      assert.equal(await evaluate(`document.querySelectorAll(${JSON.stringify(frameSelector)}).length`), 1, 'One service phone');
      await screenshot(`${width}x${height}-phone-${index}`, frameSelector);
      const reachable = await evaluate(`(() => {
        const frame = document.querySelector(${JSON.stringify(frameSelector)});
        return [...frame.querySelectorAll('nav[aria-label="주요 메뉴"] button, button[type="submit"]')].filter(b=>!b.disabled).map(b=>{
          b.scrollIntoView({block:'center'});
          const r=b.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2;
          return {text:b.textContent.trim(), reachable:x>=0&&x<=innerWidth&&y>=0&&y<=innerHeight&&b.contains(document.elementFromPoint(x,y))};
        });
      })()`);
      for (const b of reachable) assert.equal(b.reachable, true, `Scroll access: ${width}x${height} ${b.text}`);
    }
    await screen('🎮 메인 게임 화면');
    await checkSheet('[title="채팅창 열기"]', '채팅', width, height);
    await checkSheet('[title="우리 학교 방명록 보기"]', '우리 학교 방명록', width, height);
    await button('지도');
    const header = await evaluate(`[...document.querySelectorAll('[data-game-main] button')].map(b => {
      const s = getComputedStyle(b), r = b.getBoundingClientRect();
      return { text:b.textContent.trim(), height:r.height, singleLine:parseFloat(s.lineHeight)+parseFloat(s.paddingTop)+parseFloat(s.paddingBottom)+parseFloat(s.borderTopWidth)+parseFloat(s.borderBottomWidth) };
    })`);
    assert.deepEqual(header.map(b => b.text), ['전국 지도', '돌아가기']);
    for (const b of header) assert.ok(b.height <= b.singleLine + 1, `Header wrapping: ${JSON.stringify(b)}`);
    results.push({ width, height, screens: 9, sheets: 'native keyboard / focus / bounds passed', header });
  }

  await viewport(390, 844);
  await load();
  await screen('🎮 메인 게임 화면');
  await touch('[title="채팅창 열기"]');
  await waitFor(`!!document.querySelector('dialog[open]')`);
  await fill('[aria-label="채팅 메시지"]', '가로 회전 중 작성');
  await evaluate(`window.__m6Dialog = document.querySelector('dialog[open]'); window.__m6Contribution = document.querySelector('[data-testid="campus-stats-button"]').getAttribute('aria-label')`);
  for (const [width, height] of [[844, 390], [390, 844]]) {
    await viewport(width, height);
    assert.equal(await evaluate(`document.querySelector('dialog[open]') === window.__m6Dialog && document.querySelector('[aria-label="채팅 메시지"]').value === '가로 회전 중 작성' && document.querySelector('[data-testid="campus-stats-button"]').getAttribute('aria-label') === window.__m6Contribution`), true);
  }
  await key('Escape');
  results.push({ rotation: 'same modal node, draft and contribution preserved; touch opens chat' });

  await screen('📱 순차 온보딩 (Single Phone)');
  await fill('input[type="email"]', 'm6@kaist.ac.kr');
  await button('인증 코드 받기');
  await waitFor(`!!document.querySelector('[data-testid="mock-mailbox-code"]')`);
  const issued = await evaluate(`document.querySelector('[data-testid="mock-mailbox-code"]').textContent`);
  const wrong = issued === '000000' ? '111111' : '000000';
  for (let i = 0; i < 5; i++) await enterCode(wrong);
  assert.equal(await evaluate(`document.querySelector('[data-testid="attempts-left"]').textContent.includes('0회')`), true);
  await enterCode(issued);
  assert.equal(await evaluate(`document.querySelector('[role="alert"]').textContent.includes('시도 횟수를 모두')`), true);
  await screen('🎮 메인 게임 화면');
  await screen('📱 순차 온보딩 (Single Phone)');
  assert.equal(await evaluate(`document.querySelector('[data-testid="attempts-left"]').textContent.includes('0회')`), true);
  await enterCode(issued);
  assert.equal(await evaluate(`document.querySelector('[role="alert"]').textContent.includes('시도 횟수를 모두')`), true);
  await screenshot('auth-attempt-limit');
  results.push({ auth: 'five wrong attempts lock even the correct code; lock persists when reopening screen' });
  await runMobileFlows({ send, evaluate, waitFor, pause, button, screen, fill, key, viewport, load, screenshot, touch, checkSheet, results });
  assert.deepEqual(errors, [], 'No console errors or unhandled exceptions');
  await writeFile(path.join(out, 'report.json'), JSON.stringify({ browser: version.Browser, app, results, errors, notCovered: ['Physical device/IME/safe areas/browser chrome height/pinch zoom', 'Code reuse already-used branch: inaccessible through the successful UI (model regression exists)', 'Growth screenshots require separate visual review; automated capture is not a visual pass', 'All possible school badges and every list/tab combination'] }, null, 2));
  console.log(`M6 browser checks passed: ${out}/report.json`);
} catch (error) {
  let page;
  if (ws?.readyState === WebSocket.OPEN) {
    try {
      page = await evaluate(`({ title:document.title, active:document.activeElement?.outerHTML, text:document.body.innerText.slice(-6000) })`);
      await screenshot('failure');
    } catch { /* Preserve the original failure if the page or CDP connection is gone. */ }
  }
  await writeFile(path.join(out, 'failure.json'), JSON.stringify({ error: String(error), page, results, errors }, null, 2));
  console.error(error);
  process.exitCode = 1;
} finally {
  for (const call of pending.values()) clearTimeout(call.timer);
  ws?.close();
  if (target?.id) await fetch(`${endpoint}/json/close/${target.id}`).catch(() => {});
}
