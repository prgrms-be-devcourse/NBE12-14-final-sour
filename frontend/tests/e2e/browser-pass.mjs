/**
 * E2E Interactive Browser Pass for P4 (resolves R4-2, P2-6, P3-5)
 *
 * Build first with `npm run build -- --mode campus-test` (mock mode).
 * Drives real Chromium against the test build using Chrome DevTools Protocol (CDP)
 * over Node 26 global WebSocket without external packages.
 */

import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import { createServer } from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { selectMockScreen } from './cdp-mock-tools.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const ROOT_DIR = path.resolve(__dirname, '../..');
const REPORT_DIR = process.env.BROWSER_PASS_OUT
  ? path.resolve(process.env.BROWSER_PASS_OUT)
  : path.join(ROOT_DIR, '.agent-workflow/261107-1310-terra-medium/p4/attempt-1/browser');
const USER_DATA_DIR = path.join(REPORT_DIR, '../chromium-profile');

// 목업 초기 데이터에는 다른 연합의 대결도 있으므로 대결 수락·상태 확인은 우리 연합 대결로 한정한다.
const OUR_ALLIANCE = '한양대·부산대 연합';
const OPPONENT_ALLIANCE = '수도권 동맹';

async function findFreePort() {
  return new Promise((resolve, reject) => {
    const srv = createServer();
    srv.listen(0, '127.0.0.1', () => {
      const port = srv.address().port;
      srv.close(() => resolve(port));
    });
    srv.on('error', reject);
  });
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

class CDPClient {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.ws = null;
    this.idCounter = 0;
    this.pending = new Map();
    this.eventListeners = new Map();
  }

  async connect() {
    this.ws = new WebSocket(this.wsUrl);
    await new Promise((resolve, reject) => {
      this.ws.onopen = resolve;
      this.ws.onerror = reject;
    });

    this.ws.onmessage = (event) => {
      const msg = JSON.parse(event.data);
      if (msg.id != null && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) {
          reject(new Error(`CDP error in response: ${JSON.stringify(msg.error)}`));
        } else {
          resolve(msg.result);
        }
      } else if (msg.method) {
        const listeners = this.eventListeners.get(msg.method) || [];
        for (const fn of listeners) {
          try { fn(msg.params); } catch (err) { console.error('Listener error:', err); }
        }
      }
    };
  }

  on(method, listener) {
    if (!this.eventListeners.has(method)) {
      this.eventListeners.set(method, []);
    }
    this.eventListeners.get(method).push(listener);
  }

  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.idCounter;
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  async evaluate(expression) {
    const res = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (res.exceptionDetails) {
      throw new Error(`Runtime.evaluate failed: ${JSON.stringify(res.exceptionDetails)}`);
    }
    return res.result?.value;
  }

  async close() {
    if (this.ws) {
      this.ws.close();
    }
  }
}

async function run() {
  console.log('=== Starting P4 Interactive Browser Pass ===');
  await fs.mkdir(REPORT_DIR, { recursive: true });
  await fs.mkdir(USER_DATA_DIR, { recursive: true });

  const previewPort = await findFreePort();
  const cdpPort = await findFreePort();
  console.log(`Ports allocated: Preview=${previewPort}, CDP=${cdpPort}`);

  // 1. Start Vite Preview
  console.log('Spawning Vite preview...');
  const previewProc = spawn('npx', ['vite', 'preview', '--host', '127.0.0.1', '--port', String(previewPort)], {
    cwd: ROOT_DIR,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let previewUrl = `http://127.0.0.1:${previewPort}`;
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Vite preview startup timeout')), 10000);
    previewProc.stdout.on('data', (chunk) => {
      const out = chunk.toString();
      if (out.includes('Local:')) {
        clearTimeout(timeout);
        resolve();
      }
    });
    previewProc.stderr.on('data', (chunk) => {
      console.error('preview stderr:', chunk.toString());
    });
    previewProc.on('error', (err) => {
      clearTimeout(timeout);
      reject(err);
    });
  });
  console.log(`Vite preview ready at ${previewUrl} (PID ${previewProc.pid})`);

  // 2. Start Chromium
  console.log('Spawning Chromium...');
  const chromiumProc = spawn('/usr/bin/chromium', [
    '--headless=new',
    `--remote-debugging-port=${cdpPort}`,
    `--user-data-dir=${USER_DATA_DIR}`,
    '--no-sandbox',
    '--disable-gpu',
    '--disable-dev-shm-usage',
    'about:blank',
  ], {
    stdio: 'ignore',
  });
  console.log(`Chromium spawned (PID ${chromiumProc.pid})`);

  let cdpClient = null;
  const consoleErrors = [];
  const uncaughtExceptions = [];
  const tabSequences = [];
  const overflowReports = [];
  const screenshots = [];

  try {
    // 3. Connect to Chromium via CDP
    let pageTarget = null;
    for (let i = 0; i < 30; i++) {
      try {
        const res = await fetch(`http://127.0.0.1:${cdpPort}/json/list`);
        const list = await res.json();
        pageTarget = list.find((t) => t.type === 'page');
        if (pageTarget?.webSocketDebuggerUrl) break;
      } catch {
        // wait
      }
      await delay(200);
    }
    if (!pageTarget?.webSocketDebuggerUrl) {
      throw new Error('Failed to find Chromium page target webSocketDebuggerUrl');
    }

    console.log(`Connecting CDP to ${pageTarget.webSocketDebuggerUrl}`);
    cdpClient = new CDPClient(pageTarget.webSocketDebuggerUrl);
    await cdpClient.connect();

    await cdpClient.send('Runtime.enable');
    await cdpClient.send('Page.enable');
    await cdpClient.send('DOM.enable');

    cdpClient.on('Runtime.consoleAPICalled', (params) => {
      if (params.type === 'error') {
        console.error('[Browser Console Error]', params.args);
        consoleErrors.push(params);
      }
    });

    cdpClient.on('Runtime.exceptionThrown', (params) => {
      console.error('[Browser Exception]', params.exceptionDetails);
      uncaughtExceptions.push(params);
    });

    // Helpers
    async function clickSelector(selector, description = '') {
      console.log(`Clicking [${description || selector}]`);
      const coords = await cdpClient.evaluate(`
        (() => {
          const el = document.querySelector(${JSON.stringify(selector)});
          if (!el) return null;
          el.scrollIntoView({ block: 'center', inline: 'center' });
          const rect = el.getBoundingClientRect();
          return {
            x: rect.left + rect.width / 2,
            y: rect.top + rect.height / 2,
            width: rect.width,
            height: rect.height,
            visible: rect.width > 0 && rect.height > 0
          };
        })()
      `);

      if (!coords || !coords.visible) {
        console.warn(`Coordinates not visible for ${selector}, using element.click() fallback`);
        await cdpClient.evaluate(`document.querySelector(${JSON.stringify(selector)})?.click()`);
        return 'Runtime.evaluate(click)';
      }

      await cdpClient.send('Input.dispatchMouseEvent', {
        type: 'mouseMoved',
        x: coords.x,
        y: coords.y,
      });
      await cdpClient.send('Input.dispatchMouseEvent', {
        type: 'mousePressed',
        x: coords.x,
        y: coords.y,
        button: 'left',
        clickCount: 1,
      });
      await cdpClient.send('Input.dispatchMouseEvent', {
        type: 'mouseReleased',
        x: coords.x,
        y: coords.y,
        button: 'left',
        clickCount: 1,
      });
      return 'Input.dispatchMouseEvent';
    }

    async function clickByText(tag, textSubstr, description = '') {
      const selector = await cdpClient.evaluate(`
        (() => {
          const els = Array.from(document.querySelectorAll(${JSON.stringify(tag)}));
          const el = els.find(e => (e.innerText || e.textContent || '').includes(${JSON.stringify(textSubstr)}));
          if (!el) return null;
          if (!el.id) {
            el.setAttribute('data-e2e-id', 'e2e-' + Math.random().toString(36).slice(2, 9));
          }
          return '[' + (el.id ? 'id=\"' + el.id + '\"' : 'data-e2e-id=\"' + el.getAttribute('data-e2e-id') + '\"') + ']';
        })()
      `);
      if (!selector) {
        throw new Error(`Element with tag <${tag}> containing "${textSubstr}" not found`);
      }
      return clickSelector(selector, description || `<${tag}> "${textSubstr}"`);
    }

    // 우리 연합 대결 카드(대결 현황 화면)의 상태 배지와 카드 텍스트.
    async function readOurBattleCard() {
      const result = await cdpClient.evaluate(`
        (() => {
          const cards = Array.from(document.querySelectorAll('div.rpg-box')).filter((d) =>
            (d.firstElementChild?.textContent || '').includes('(연합별')
            && (d.textContent || '').includes(${JSON.stringify(OUR_ALLIANCE)})
            && (d.textContent || '').includes(${JSON.stringify(OPPONENT_ALLIANCE)}));
          if (cards.length !== 1) return { error: 'our battle cards: ' + cards.length };
          const status = (cards[0].firstElementChild?.querySelector(':scope > span')?.textContent || '').trim();
          return { status, text: cards[0].innerText };
        })()
      `);
      if (result?.error) throw new Error(result.error);
      return result;
    }

    async function advanceClockUntilOurBattle(expectedStatus, buttonText, maxClicks) {
      let state = await readOurBattleCard();
      for (let i = 0; i < maxClicks && state.status !== expectedStatus; i++) {
        await clickByExactText('button', buttonText, `Advance virtual clock ${buttonText}`);
        await delay(150);
        state = await readOurBattleCard();
      }
      if (state.status !== expectedStatus) {
        throw new Error(`Our battle did not reach "${expectedStatus}" within ${maxClicks} x ${buttonText}: ${JSON.stringify(state)}`);
      }
      return state;
    }

    async function clickByExactText(tag, exactText, description = '') {
      const selector = await cdpClient.evaluate(`
        (() => {
          const els = Array.from(document.querySelectorAll(${JSON.stringify(tag)}));
          const el = els.find(e => (e.innerText || e.textContent || '').trim() === ${JSON.stringify(exactText)});
          if (!el) return null;
          if (!el.id) {
            el.setAttribute('data-e2e-id', 'e2e-' + Math.random().toString(36).slice(2, 9));
          }
          return '[' + (el.id ? 'id=\"' + el.id + '\"' : 'data-e2e-id=\"' + el.getAttribute('data-e2e-id') + '\"') + ']';
        })()
      `);
      if (!selector) {
        throw new Error(`Element with tag <${tag}> with exact text "${exactText}" not found`);
      }
      return clickSelector(selector, description || `<${tag}> exact "${exactText}"`);
    }

    async function recordTabSequence(screenName, tabCount = 6) {
      console.log(`Recording Tab sequence on ${screenName} (${tabCount} tabs)...`);
      const steps = [];
      for (let i = 0; i < tabCount; i++) {
        await cdpClient.send('Input.dispatchKeyEvent', {
          type: 'rawKeyDown',
          key: 'Tab',
          code: 'Tab',
          windowsVirtualKeyCode: 9,
          nativeVirtualKeyCode: 9,
        });
        await cdpClient.send('Input.dispatchKeyEvent', {
          type: 'keyUp',
          key: 'Tab',
          code: 'Tab',
          windowsVirtualKeyCode: 9,
          nativeVirtualKeyCode: 9,
        });
        await delay(100);

        const activeInfo = await cdpClient.evaluate(`
          (() => {
            const el = document.activeElement;
            if (!el) return { tagName: 'NONE', role: null, label: null };
            return {
              tagName: el.tagName,
              role: el.getAttribute('role') || el.role || null,
              label: el.getAttribute('aria-label') || el.innerText || el.textContent?.trim()?.slice(0, 40) || null,
              id: el.id || null,
              className: el.className ? String(el.className).slice(0, 30) : null
            };
          })()
        `);
        steps.push(activeInfo);
      }
      tabSequences.push({ screen: screenName, steps });
    }

    async function captureScreenAndCheckOverflow(screenName) {
      console.log(`Capturing screenshots & checking overflow for ${screenName}...`);
      for (const width of [1280, 360]) {
        const height = width === 1280 ? 900 : 800;
        await cdpClient.send('Emulation.setDeviceMetricsOverride', {
          width,
          height,
          deviceScaleFactor: 1,
          mobile: width < 600,
        });
        await delay(200);

        const overflow = await cdpClient.evaluate(`
          (() => {
            const doc = document.documentElement;
            const body = document.body;
            const scrollWidth = Math.max(doc.scrollWidth, body.scrollWidth);
            const clientWidth = doc.clientWidth;
            return {
              scrollWidth,
              clientWidth,
              hasOverflow: scrollWidth > clientWidth,
              overflowPx: Math.max(0, scrollWidth - clientWidth)
            };
          })()
        `);

        overflowReports.push({
          screen: screenName,
          viewportWidth: width,
          ...overflow,
        });

        const shot = await cdpClient.send('Page.captureScreenshot', {
          format: 'png',
          captureBeyondViewport: true,
        });
        const filename = `${screenName}-${width}.png`;
        const filepath = path.join(REPORT_DIR, filename);
        await fs.writeFile(filepath, Buffer.from(shot.data, 'base64'));
        screenshots.push({ screen: screenName, width, path: filepath, filename });
      }

      // Reset to 1280 for regular interaction
      await cdpClient.send('Emulation.setDeviceMetricsOverride', {
        width: 1280,
        height: 900,
        deviceScaleFactor: 1,
        mobile: false,
      });
      await delay(100);
    }

    // 4. Navigate to App
    console.log(`Navigating to ${previewUrl}...`);
    await cdpClient.send('Page.navigate', { url: previewUrl });
    await delay(2000);

    const initialButtons = await cdpClient.evaluate(`
      Array.from(document.querySelectorAll('button')).map(b => (b.innerText || b.textContent || '').trim()).slice(0, 20)
    `);
    console.log('Initial buttons found:', initialButtons);

    // =========================================================================
    // STEP 1: Shop & School Image Change
    // =========================================================================
    console.log('\n--- Step 1: Shop & School Image Change ---');
    // Open Gameplay view, then open Shop tab inside phone frame so TopHUD is visible
    await selectMockScreen(cdpClient.evaluate.bind(cdpClient), '🎮 메인 게임 화면');
    await delay(500);

    await clickByExactText('button', '상점', 'Open Shop tab in gameplay via bottom nav');
    await delay(500);

    // Click school image change product
    await clickByText('button', '학교 이미지 변경 · 500원', 'Select school image change');
    await delay(500);

    // Verify dialog open
    const dialogOpened = await cdpClient.evaluate(`
      Boolean(document.querySelector('[aria-label="학교 이미지 변경 확인"]'))
    `);
    if (!dialogOpened) throw new Error('School image change dialog not opened');

    // Tab accessibility on shop confirmation dialog
    await recordTabSequence('shop-confirmation', 6);

    // Verify confirm button initial disabled state & styling (P4-4 / P4-5)
    const initialConfirmBtn = await cdpClient.evaluate(`
      (() => {
        const btn = Array.from(document.querySelectorAll('button')).find(b => b.innerText?.includes('모의 결제'));
        return {
          exists: Boolean(btn),
          disabled: Boolean(btn?.disabled),
          className: btn?.className || '',
          hasDisabledClass: Boolean(btn?.className?.includes('opacity-50') && btn?.className?.includes('cursor-not-allowed'))
        };
      })()
    `);
    console.log('Confirm button initial disabled state & styling:', initialConfirmBtn);
    if (!initialConfirmBtn.exists || !initialConfirmBtn.disabled || !initialConfirmBtn.hasDisabledClass) {
      throw new Error(`Confirm button should be disabled and styled as disabled when URL is empty: ${JSON.stringify(initialConfirmBtn)}`);
    }

    // Verify all dialog actions have non-empty className
    const dialogBtnStyles = await cdpClient.evaluate(`
      Array.from(document.querySelectorAll('[role="dialog"] button')).map(b => ({
        text: (b.innerText || '').trim(),
        className: b.className || '',
        hasClassName: Boolean(b.className && b.className.trim().length > 0)
      }))
    `);
    console.log('Dialog action buttons styling:', dialogBtnStyles);
    if (dialogBtnStyles.some(b => !b.hasClassName)) {
      throw new Error(`Dialog action buttons must have non-empty className: ${JSON.stringify(dialogBtnStyles)}`);
    }

    // Capture screenshots & check overflow
    await captureScreenAndCheckOverflow('shop-confirmation');

    // Click local sample
    console.log('Dialog status before sample click:', await cdpClient.evaluate(`
      Boolean(document.querySelector('[aria-label="학교 이미지 변경 확인"]'))
    `));
    await clickByText('button', '로컬 샘플 사용', 'Use local sample SVG');
    await delay(300);

    const inputVal = await cdpClient.evaluate(`document.querySelector('[aria-label="학교 이미지 변경 확인"] input')?.value`);
    if (!inputVal?.includes('mock_identity_sample')) throw new Error('Sample URL was not applied to the image URL input');
    console.log('Input value after sample click:', inputVal);

    // Verify confirm button becomes enabled and active
    const activeConfirmBtn = await cdpClient.evaluate(`
      (() => {
        const btn = Array.from(document.querySelectorAll('button')).find(b => b.innerText?.includes('모의 결제'));
        return {
          disabled: Boolean(btn?.disabled),
          className: btn?.className || '',
          hasPointerClass: Boolean(btn?.className?.includes('cursor-pointer'))
        };
      })()
    `);
    console.log('Confirm button after sample selection:', activeConfirmBtn);
    if (activeConfirmBtn.disabled || !activeConfirmBtn.hasPointerClass) {
      throw new Error(`Confirm button should be enabled after sample selection: ${JSON.stringify(activeConfirmBtn)}`);
    }

    // Confirm mock payment success
    console.log('Confirm button exists / enabled:', await cdpClient.evaluate(`
      Array.from(document.querySelectorAll('button')).map(b => ({ text: b.innerText, disabled: b.disabled })).filter(b => b.text?.includes('모의 결제'))
    `));

    await clickByText('button', '모의 결제 성공 및 즉시 적용', 'Apply mock payment success');
    await delay(600);

    // Check status message
    const shopSuccessMsg = await cdpClient.evaluate(`
      (() => {
        const statuses = Array.from(document.querySelectorAll('[role="status"]')).map(el => el.innerText || '');
        return statuses.find(t => t.includes('결제')) || statuses.join(' | ');
      })()
    `);
    console.log(`Shop status message: "${shopSuccessMsg}"`);
    if (!shopSuccessMsg?.includes('모의 결제 성공')) {
      throw new Error(`Expected mock payment success message, got: ${shopSuccessMsg}`);
    }

    // Verify HUD image changed
    const headerHtml = await cdpClient.evaluate(`
      document.querySelector('header')?.innerHTML
    `);
    console.log('Header innerHTML after sample apply:', headerHtml);

    const allImgs = await cdpClient.evaluate(`
      Array.from(document.querySelectorAll('img')).map(img => ({ alt: img.alt, src: img.src }))
    `);
    console.log('All <img> elements found in DOM:', allImgs);

    const hudImgSrc = await cdpClient.evaluate(`
      document.querySelector('img[alt="한양대학교 이미지"]')?.getAttribute('src')
    `);
    console.log(`HUD image src after sample apply: "${hudImgSrc}"`);
    if (!hudImgSrc?.includes('mock_identity_sample')) {
      throw new Error(`HUD image src does not include mock_identity_sample: ${hudImgSrc}`);
    }

    // Test Broken URL fallback
    console.log('Testing broken image URL fallback...');
    await clickByText('button', '학교 이미지 변경 · 500원', 'Select school image change for broken URL test');
    await delay(400);

    // Enter broken URL in the input using native setter so React detects onChange
    await cdpClient.evaluate(`
      (() => {
        const inputs = document.querySelectorAll('[aria-label="학교 이미지 변경 확인"] input');
        if (inputs.length !== 1) throw new Error('Expected one scoped image URL input');
        const input = inputs[0];
        const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
        if (nativeSetter) {
          nativeSetter.call(input, 'http://127.0.0.1:9999/nonexistent-broken-logo.svg');
        } else {
          input.value = 'http://127.0.0.1:9999/nonexistent-broken-logo.svg';
        }
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.dispatchEvent(new Event('change', { bubbles: true }));
      })()
    `);
    await delay(300);

    const brokenConfirmDisabled = await cdpClient.evaluate(`
      Array.from(document.querySelectorAll('button')).find(b => b.innerText?.includes('모의 결제'))?.disabled
    `);
    console.log('Broken URL confirm button disabled?:', brokenConfirmDisabled);
    if (brokenConfirmDisabled !== false) throw new Error('Broken URL must be accepted before testing load failure');
    const brokenInputValue = await cdpClient.evaluate(`document.querySelector('[aria-label="학교 이미지 변경 확인"] input')?.value`);
    if (brokenInputValue !== 'http://127.0.0.1:9999/nonexistent-broken-logo.svg') throw new Error('Broken URL was not entered in the image form');

    await clickByText('button', '모의 결제 성공 및 즉시 적용', 'Apply broken URL mock payment');
    await delay(600);

    const brokenApplied = await cdpClient.evaluate(`!document.querySelector('[aria-label="학교 이미지 변경 확인"]') && [...document.querySelectorAll('[role="status"]')].some(e => e.textContent.includes('모의 결제 성공'))`);
    if (!brokenApplied) throw new Error('Broken image URL was not successfully applied');
    // Wait for image load failure and fallback to render
    await delay(500);
    const fallbackRendered = await cdpClient.evaluate(`
      Boolean(document.querySelector('[aria-label="한양대학교 기본 배지"]'))
    `);
    console.log(`Fallback badge rendered after broken URL: ${fallbackRendered}`);
    if (!fallbackRendered) {
      throw new Error('ImageFallback badge not rendered after broken URL');
    }

    // Restore local sample image
    console.log('Restoring valid sample logo...');
    await clickByText('button', '학교 이미지 변경 · 500원', 'Re-select school image change');
    await delay(400);
    await clickByText('button', '로컬 샘플 사용', 'Restore sample SVG');
    await delay(200);
    await clickByText('button', '모의 결제 성공 및 즉시 적용', 'Re-apply sample');
    await delay(500);

    // =========================================================================
    // STEP 2: Alliance Application & Formation
    // =========================================================================
    console.log('\n--- Step 2: Alliance Application & Formation ---');
    await selectMockScreen(cdpClient.evaluate.bind(cdpClient), '🛡️ 현재 연합 (Current Alliance)');
    await delay(500);

    // Verify empty alliance state
    const emptyAllianceText = await cdpClient.evaluate(`
      document.body.innerText.includes('소속 연합 없음')
    `);
    if (!emptyAllianceText) throw new Error('Expected "소속 연합 없음" on initial current alliance screen');

    await captureScreenAndCheckOverflow('current-alliance');

    // Click "연합 신청하러 가기"
    await clickByText('button', '연합 신청하러 가기', 'Navigate to alliance apply');
    await delay(500);

    // Tab accessibility on alliance application screen
    await recordTabSequence('alliance-apply', 6);

    // Select candidate school (부산대학교)
    await clickByText('button', '부산대학교', 'Select 부산대학교 candidate');
    await delay(300);

    // 연합 기간 72시간: 아래 대결 진행·종료를 위해 시계를 앞당겨도 연합이 만료되지 않게 한다 (기본 24시간)
    await clickByText('button', '72h', 'Select 72h alliance duration');
    await delay(300);

    // Send application
    await clickByText('button', '연합 신청 보내기', 'Submit alliance application');
    await delay(500);

    // Demo accept under demo controls
    await clickByText('button', '[시연] 상대 학교 명의로 수락', 'Demo accept alliance application');
    await delay(500);

    // Navigate to Current Alliance
    await clickByText('button', '현재 연합', 'Navigate back to current alliance');
    await delay(500);

    // Verify formed alliance
    const allianceFormed = await cdpClient.evaluate(`
      document.body.innerText.includes('한양대·부산대 연합')
    `);
    console.log(`Alliance "한양대·부산대 연합" formed: ${allianceFormed}`);
    if (!allianceFormed) throw new Error('Formed alliance "한양대·부산대 연합" not found');

    // =========================================================================
    // STEP 3: Battle Application, Clock Advance & Lifecycle
    // =========================================================================
    console.log('\n--- Step 3: Battle Application, Clock Advance & Lifecycle ---');
    await selectMockScreen(cdpClient.evaluate.bind(cdpClient), '⚔️ 대결 신청 (Battle Apply)');
    await delay(500);

    await captureScreenAndCheckOverflow('battle-apply');

    // Select opponent (수도권 동맹)
    await clickByText('button', '수도권 동맹', 'Select 수도권 동맹 opponent');
    await delay(300);

    // Submit battle application
    await clickByText('button', '대결 신청 보내기', 'Send battle application');
    await delay(500);

    // Demo accept under demo controls — 우리 연합 대기 신청 행의 수락 버튼만 누른다.
    // 목업 초기 데이터에 다른 연합의 대기 대결도 있으므로 이름만으로 찾으면 다른 대결을 수락할 수 있다.
    const ourAcceptSelector = await cdpClient.evaluate(`
      (() => {
        const rows = Array.from(document.querySelectorAll('div')).filter((d) =>
          (d.firstElementChild?.textContent || '').startsWith(${JSON.stringify(`${OUR_ALLIANCE} → ${OPPONENT_ALLIANCE}`)}) && d.querySelector('button'));
        if (rows.length !== 1) return { error: 'our pending battle rows: ' + rows.length };
        const btn = Array.from(rows[0].querySelectorAll('button')).find((b) => (b.textContent || '').includes('상대 연합 명의로 수락'));
        if (!btn) return { error: 'accept button not found in our row' };
        btn.setAttribute('data-e2e-id', 'e2e-accept-our-battle');
        return { selector: '[data-e2e-id="e2e-accept-our-battle"]' };
      })()
    `);
    if (!ourAcceptSelector?.selector) throw new Error(`Our battle accept button: ${ourAcceptSelector?.error}`);
    await clickSelector(ourAcceptSelector.selector, 'Demo accept our battle');
    await delay(500);

    // Navigate to Battle Status
    await clickByText('button', '대결 현황', 'Navigate to battle status');
    await delay(500);

    await captureScreenAndCheckOverflow('battle-status');

    // 이하 상태 확인은 페이지 전체가 아니라 우리 연합 대결 카드 안에서만 한다.
    // Check "경기 예정" state and timing (P4-2 / P4-5: 시작/종료 시각 및 시작까지 카운트다운)
    const scheduledStateCheck = await readOurBattleCard();
    console.log('Our battle card (scheduled) check:', scheduledStateCheck);
    if (scheduledStateCheck.status !== '경기 예정' || !scheduledStateCheck.text.includes('시작:') || !scheduledStateCheck.text.includes('종료:') || !scheduledStateCheck.text.includes('시작까지')) {
      throw new Error(`Expected our battle "경기 예정" with start/end time and "시작까지" countdown: ${JSON.stringify(scheduledStateCheck)}`);
    }

    // Advance clock +1h until our battle is "진행 중".
    // 기본 시작은 현재+60분 이후의 09–22시(KST) 첫 슬롯이라 실행 시각에 따라 최대 약 12시간 뒤다.
    // 대결은 두 연합 중 빠른 만료(수도권 동맹 +22h) 전에 끝나야 하므로 그 안에서만 신청된다.
    const activeStateCheck = await advanceClockUntilOurBattle('진행 중', '+1h', 36);
    console.log('Our battle card (active) check:', activeStateCheck);
    if (!activeStateCheck.text.includes('시작:') || !activeStateCheck.text.includes('종료:') || !activeStateCheck.text.includes('종료까지')) {
      throw new Error(`Expected our battle "진행 중" with start/end time and "종료까지" countdown: ${JSON.stringify(activeStateCheck)}`);
    }

    // Advance clock +24h -> "종료됨" (기본 경기 3시간, 최대 24시간), 수락 시 0:0 → 무승부
    const finishedStateCheck = await advanceClockUntilOurBattle('종료됨', '+24h', 2);
    const drawResultFound = finishedStateCheck.text.includes('무승부 (DRAW)');
    console.log(`Our battle "종료됨": true, result "무승부 (DRAW)": ${drawResultFound}`);
    if (!drawResultFound) throw new Error(`Expected our battle result "무승부 (DRAW)": ${JSON.stringify(finishedStateCheck)}`);

    // =========================================================================
    // STEP 4: Back Navigation & Preserved State
    // =========================================================================
    console.log('\n--- Step 4: Back Navigation & State Preservation ---');
    // Back from battle status -> current alliance
    await clickByText('button', '뒤로', 'Back from battle status');
    await delay(400);

    const curAlliancePersisted = await cdpClient.evaluate(`
      document.body.innerText.includes('한양대·부산대 연합')
    `);
    if (!curAlliancePersisted) throw new Error('Alliance state not persisted after returning from battle');

    // Back from current alliance -> gameplay
    await clickByText('button', '뒤로', 'Back from current alliance');
    await delay(400);

    const gameplayReached = await cdpClient.evaluate(`
      Boolean(document.querySelector('button[title="전국 대학 순위 보기"]') || document.querySelector('[role="banner"]'))
    `);
    if (!gameplayReached) throw new Error('Failed to navigate back to gameplay');

    // Check final errors
    if (consoleErrors.length > 0) {
      throw new Error(`Encountered ${consoleErrors.length} browser console errors`);
    }
    if (uncaughtExceptions.length > 0) {
      throw new Error(`Encountered ${uncaughtExceptions.length} uncaught exceptions`);
    }

    console.log('\nAll interactive browser checks passed successfully!');

    // Generate Reports
    const reportData = {
      timestamp: new Date().toISOString(),
      ports: { previewPort, cdpPort },
      pids: { previewPid: previewProc.pid, chromiumPid: chromiumProc.pid },
      screenshots,
      overflowReports,
      tabSequences,
      consoleErrorsCount: consoleErrors.length,
      uncaughtExceptionsCount: uncaughtExceptions.length,
      status: 'PASS',
    };

    await fs.writeFile(
      path.join(REPORT_DIR, 'browser-report.json'),
      JSON.stringify(reportData, null, 2),
      'utf8',
    );

    const markdownReport = [
      '# Browser E2E Pass Report (P4)',
      '',
      `- Timestamp: ${reportData.timestamp}`,
      `- Preview Port: ${previewPort} (PID: ${previewProc.pid})`,
      `- Chromium CDP Port: ${cdpPort} (PID: ${chromiumProc.pid})`,
      `- Status: **${reportData.status}**`,
      '',
      '## 1. Viewport Overflow Check',
      '',
      '| Screen | Viewport Width | scrollWidth | clientWidth | Overflow (px) | Result |',
      '|---|---|---|---|---|---|',
      ...overflowReports.map((r) =>
        `| ${r.screen} | ${r.viewportWidth}px | ${r.scrollWidth}px | ${r.clientWidth}px | ${r.overflowPx}px | ${r.hasOverflow ? '⚠️ FAIL' : '✅ PASS'} |`
      ),
      '',
      '## 2. Keyboard Navigation (Tab Reachability)',
      '',
      ...tabSequences.map((ts) => [
        `### Screen: \`${ts.screen}\``,
        '',
        '| Step | Tag | Role | Label / Text |',
        '|---|---|---|---|',
        ...ts.steps.map((st, i) => `| Tab #${i + 1} | \`<${st.tagName}>\` | ${st.role ?? '-'} | ${st.label?.replace(/\n/g, ' ') ?? '-'} |`),
        '',
      ]).flat(),
      '',
      '## 3. Screenshots Captured',
      '',
      ...screenshots.map((s) => `- \`${s.filename}\` (${s.width}px): ${s.screen}`),
      '',
      '## 4. Console Errors & Uncaught Exceptions',
      '',
      `- Console Errors: ${consoleErrors.length}`,
      `- Uncaught Exceptions: ${uncaughtExceptions.length}`,
    ].join('\n');

    await fs.writeFile(
      path.join(REPORT_DIR, 'browser-report.md'),
      markdownReport,
      'utf8',
    );
    console.log(`Report written to ${path.join(REPORT_DIR, 'browser-report.md')}`);

  } finally {
    console.log('\nCleaning up processes...');
    if (cdpClient) {
      try { await cdpClient.close(); } catch {}
    }
    if (chromiumProc && !chromiumProc.killed) {
      chromiumProc.kill('SIGTERM');
      await delay(500);
      try { process.kill(chromiumProc.pid, 0); chromiumProc.kill('SIGKILL'); } catch {}
    }
    if (previewProc && !previewProc.killed) {
      previewProc.kill('SIGTERM');
      await delay(500);
      try { process.kill(previewProc.pid, 0); previewProc.kill('SIGKILL'); } catch {}
    }
    try {
      await fs.rm(USER_DATA_DIR, { recursive: true, force: true });
    } catch {}
    console.log('Clean up complete.');
  }
}

run().catch((err) => {
  console.error('Browser pass failed:', err);
  process.exit(1);
});
