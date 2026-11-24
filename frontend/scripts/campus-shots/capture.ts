/**
 * Captures the growth-stage screenshots: one day scene per level from the dev server's capture mode
 * (`?campusShot=<level>`, src/components/CampusShot.tsx), saved as WebP with the input hashes in
 * shots.json. Plan: docs/261110-0747-campus_level_shots_plan.md (5-1).
 *
 *   npm run dev   # in another terminal
 *   CAMPUS_CDP_URL=http://127.0.0.1:9222 CAMPUS_APP_URL=http://127.0.0.1:3000 npm run campus:shots [-- 1 4 10]
 *
 * Like tests/e2e, it needs a running Chromium CDP endpoint, never launches a browser, and closes only its own tab.
 */
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { SHOTS_DIR, SHOTS_JSON, SHOT_SIZE, shotFile, shotInputHash } from './inputs';

const endpoint = process.env.CAMPUS_CDP_URL ?? 'http://127.0.0.1:9222';
const app = process.env.CAMPUS_APP_URL ?? 'http://127.0.0.1:3000';
/** WebP quality (0-100); chosen in step S1 of the plan after comparing quality on screen. */
const QUALITY = Number(process.env.CAMPUS_SHOT_QUALITY ?? 90);
const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const args = process.argv.slice(2).map(Number);
const levels = args.length ? args : Array.from({ length: 10 }, (_, i) => i + 1);
if (levels.some((l) => !Number.isInteger(l) || l < 1 || l > 10)) throw new Error(`levels must be 1-10, got ${process.argv.slice(2).join(' ')}`);

const target = await (await fetch(`${endpoint}/json/new?about:blank`, { method: 'PUT' })).json();
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let nextId = 0;
const pending = new Map<number, (message: any) => void>();
ws.onmessage = ({ data }) => {
  const message = JSON.parse(String(data));
  pending.get(message.id)?.(message);
  pending.delete(message.id);
};
const send = (method: string, params: object = {}) => new Promise<any>((resolve) => {
  const id = ++nextId;
  pending.set(id, resolve);
  ws.send(JSON.stringify({ id, method, params }));
});
async function evaluate<T>(expression: string): Promise<T> {
  const response = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (response.result.exceptionDetails) throw new Error(JSON.stringify(response.result.exceptionDetails));
  return response.result.result.value;
}

const existing = (() => { try { return JSON.parse(readFileSync(SHOTS_JSON, 'utf8')); } catch { return null; } })();
const shots: Record<string, { file: string; hash: string }> = existing?.levels ?? {};
try {
  await send('Emulation.setDeviceMetricsOverride', { width: 400, height: 800, deviceScaleFactor: SHOT_SIZE.scale, mobile: true });
  await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  for (const level of levels) {
    await send('Page.navigate', { url: `${app}/?campusShot=${level}` });
    for (let i = 0; i < 100 && await evaluate<string>(`location.search + '|' + document.readyState`) !== `?campusShot=${level}|complete`; i++) await pause(100);
    // Wait for the scene, then for every sprite file (1x and the picked HD copy) to decode.
    await evaluate(`(async () => {
      for (let i = 0; i < 100 && !document.querySelector('[data-campus-shot] [data-testid="campus-scene"]'); i++) await new Promise((r) => setTimeout(r, 100));
      if (!document.querySelector('[data-campus-shot] [data-testid="campus-scene"]')) throw new Error('capture mode did not render; is the dev server running?');
      await document.fonts.ready;
      await new Promise((r) => setTimeout(r, 300));
      const hrefs = [...new Set([...document.querySelectorAll('[data-campus-shot] image')].map((e) => e.getAttribute('href')).filter(Boolean))];
      await Promise.all(hrefs.map((href) => { const img = new Image(); img.src = href; return img.decode().catch(() => { throw new Error('image failed: ' + href); }); }));
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    })()`);
    await pause(300);
    const box = await evaluate<{ x: number; y: number; width: number; height: number }>(`(() => { const r = document.querySelector('[data-campus-shot]').getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height }; })()`);
    if (box.width !== SHOT_SIZE.width || box.height !== SHOT_SIZE.height) throw new Error(`Lv.${level}: frame is ${box.width}x${box.height}, expected ${SHOT_SIZE.width}x${SHOT_SIZE.height}`);
    const shot = await send('Page.captureScreenshot', { format: 'webp', quality: QUALITY, clip: { ...box, scale: 1 }, captureBeyondViewport: false });
    if (!shot.result?.data) throw new Error(`Lv.${level}: capture failed ${JSON.stringify(shot.error ?? shot)}`);
    const file = shotFile(level);
    const tmp = path.join(SHOTS_DIR, `.${file}.tmp`);
    writeFileSync(tmp, Buffer.from(shot.result.data, 'base64'));
    renameSync(tmp, path.join(SHOTS_DIR, file));
    shots[level] = { file, hash: shotInputHash(level) };
    console.log(`Lv.${level}: ${file}`);
  }
  const out = { note: 'Generated by npm run campus:shots. Hashes cover each level\'s layout, stage, sky and asset files (scripts/campus-shots/inputs.ts).', width: SHOT_SIZE.width * SHOT_SIZE.scale, height: SHOT_SIZE.height * SHOT_SIZE.scale, quality: QUALITY, levels: shots };
  writeFileSync(`${SHOTS_JSON}.tmp`, `${JSON.stringify(out, null, 2)}\n`);
  renameSync(`${SHOTS_JSON}.tmp`, SHOTS_JSON);
} finally {
  ws.close();
  await fetch(`${endpoint}/json/close/${target.id}`).catch(() => {});
}
