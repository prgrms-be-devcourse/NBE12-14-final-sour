// Growth-stage screenshots (docs/261110-0747-campus_level_shots_plan.md 5-3, 5-4).
import { after, afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { assertAbsent } from './helpers/domAssert';
import { existsSync, readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import path from 'node:path';
import React from 'react';
import { JSDOM } from 'jsdom';

// Same asset stub as tests/campusScene.test.ts: image imports resolve to their /src/assets path.
const assetHooks = registerHooks({
  load(url, context, nextLoad) {
    if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) {
      const match = new URL(url).pathname.match(/\/src\/assets\/.+$/);
      return { format: 'module', source: `export default ${JSON.stringify(match ? match[0] : '/stub.svg')};`, shortCircuit: true };
    }
    return nextLoad(url, context);
  },
});
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });

const { cleanup, fireEvent, render, screen } = await import('@testing-library/react');
const { CAMPUS_DEFAULT_ASPECT, CAMPUS_SHOT_SIZE } = await import('../src/data/campusGeometry');
const { CAMPUS_SHOTS, campusShotFor } = await import('../src/data/campusShots');
const { CAMPUS_STAGES } = await import('../src/data/gameRules');
const { SpriteSheetViewer } = await import('../src/components/SpriteSheetViewer');
const { ROOT, SHOTS_DIR, SHOTS_JSON, shotFile, shotInputHash } = await import('../scripts/campus-shots/inputs');

after(() => { assetHooks.deregister(); dom.window.close(); });
afterEach(cleanup);

const RECAPTURE = 'run `npm run dev`, then `npm run campus:shots` (needs a Chromium CDP endpoint; see scripts/campus-shots/capture.ts)';
const shots = JSON.parse(readFileSync(SHOTS_JSON, 'utf8')) as { width: number; height: number; levels: Record<string, { file: string; hash: string }> };

/** Width and height from a WebP header (RFC 9649: VP8, VP8L and VP8X chunks). */
function webpSize(file: string): [number, number] {
  const b = readFileSync(file);
  assert.equal(b.toString('ascii', 0, 4), 'RIFF', `${file} is not RIFF`);
  assert.equal(b.toString('ascii', 8, 12), 'WEBP', `${file} is not WebP`);
  const chunk = b.toString('ascii', 12, 16);
  if (chunk === 'VP8 ') return [b.readUInt16LE(26) & 0x3fff, b.readUInt16LE(28) & 0x3fff];
  if (chunk === 'VP8L') { const bits = b.readUInt32LE(21); return [(bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1]; }
  if (chunk === 'VP8X') return [b.readUIntLE(24, 3) + 1, b.readUIntLE(27, 3) + 1];
  throw new Error(`${file}: unknown WebP chunk ${chunk}`);
}

test('the capture frame keeps the in-game aspect, so shots show the in-game view', () => {
  assert.ok(Math.abs(CAMPUS_SHOT_SIZE.height / CAMPUS_SHOT_SIZE.width - CAMPUS_DEFAULT_ASPECT) < 1e-9);
  assert.equal(shots.width, CAMPUS_SHOT_SIZE.width * CAMPUS_SHOT_SIZE.scale);
  assert.equal(shots.height, CAMPUS_SHOT_SIZE.height * CAMPUS_SHOT_SIZE.scale);
});

for (let level = 1; level <= 10; level++) {
  test(`Lv.${level} screenshot exists, has the capture size and matches the current scene inputs`, () => {
    const entry = shots.levels[level];
    assert.ok(entry, `Lv.${level} has no screenshot in shots.json; ${RECAPTURE}`);
    assert.equal(entry.file, shotFile(level));
    const file = path.join(SHOTS_DIR, entry.file);
    assert.ok(existsSync(file), `missing ${path.relative(ROOT, file)}; ${RECAPTURE}`);
    assert.deepEqual(webpSize(file), [shots.width, shots.height]);
    assert.equal(entry.hash, shotInputHash(level), `Lv.${level} screenshot is stale: its layout, stage, sky or asset files changed since the capture; ${RECAPTURE} ${level}`);
  });
}

test('each level maps to its own screenshot; out-of-range levels clamp to Lv.1 and Lv.10', () => {
  assert.equal(CAMPUS_SHOTS.length, 10);
  CAMPUS_SHOTS.forEach((src, i) => assert.match(src, new RegExp(`/campus-shots/${shotFile(i + 1).replace('.', '\\.')}$`)));
  assert.equal(campusShotFor(0), CAMPUS_SHOTS[0]);
  assert.equal(campusShotFor(11), CAMPUS_SHOTS[9]);
});

test('the old growth sheet stays in the repo as an art reference (user instruction 2026-11-10)', () => {
  assert.ok(existsSync(path.join(ROOT, 'src/assets/images/campus_growth_5x2_final_1793921090067.jpg')));
});

test('the growth-stage viewer shows the 10 screenshots and selects a stage from its tile', () => {
  const picked: number[] = [];
  render(React.createElement(SpriteSheetViewer, { currentLevel: 1, onSelectStageForGame: (lv: number) => picked.push(lv) }));
  CAMPUS_STAGES.forEach((stage, i) => {
    const img = screen.getByRole('img', { name: `캠퍼스 Lv.${stage.level} ${stage.name}` });
    assert.equal(img.getAttribute('src'), CAMPUS_SHOTS[i]);
  });
  const tile = screen.getByRole('img', { name: `캠퍼스 Lv.7 ${CAMPUS_STAGES[6].name}` }).closest('button')!;
  fireEvent.click(tile);
  assert.equal(tile.getAttribute('aria-pressed'), 'true');
  assert.ok(screen.getByRole('heading', { name: CAMPUS_STAGES[6].name }));
  fireEvent.click(screen.getByRole('button', { name: /성장 시연: 학교 점수를 기준값으로 설정/ }));
  assert.deepEqual(picked, [7]);
  assertAbsent(document.querySelector('img[src*="campus_growth_5x2"]'), 'the viewer no longer shows the old sheet');
});
