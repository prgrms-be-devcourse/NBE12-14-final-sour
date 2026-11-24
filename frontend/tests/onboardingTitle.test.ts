// Onboarding title art (docs/261110-0837-onboarding_title_screen_plan.md 6): a square composed title scene
// with the text logo, no pasted screenshot, and a school badge strip below it.
import { after, afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { assertAbsent } from './helpers/domAssert';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';

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

const { cleanup, render, screen } = await import('@testing-library/react');
const { EmailStep, TITLE_SCENE_LABEL } = await import('../src/components/onboarding/OnboardingSteps');
const { CampusAmbienceProvider } = await import('../src/hooks/useCampusTimePhase');
const { CAMPUS_SUN_POSITION, TITLE_LAYOUT, TITLE_LOGO_BOX, validateSceneLayout } = await import('../src/data/campusLayouts');
const { spriteBox } = await import('../src/data/campusGeometry');
const { REAL_UNIVERSITIES } = await import('../src/data/universitiesData');

after(() => { assetHooks.deregister(); dom.window.close(); });
afterEach(cleanup);

const noop = () => {};
/** The email step at night: the title still shows a day sky. */
function renderEmailStep() {
  return render(React.createElement(CampusAmbienceProvider, { initialOverride: 'night', children: React.createElement(EmailStep, { email: '', onEmailChange: noop, onRequestCode: noop }) }));
}

test('the title scene layout passes the campus placement rules', () => {
  assert.deepEqual(validateSceneLayout('Title', TITLE_LAYOUT), []);
});

test('the sun and clouds stay clear of the text logo in the title sky', () => {
  const [l, t, r, b] = TITLE_LOGO_BOX;
  const sun = TITLE_LAYOUT.sun ?? CAMPUS_SUN_POSITION;
  for (const item of [{ asset: 'sun' as const, ...sun }, ...TITLE_LAYOUT.clouds]) {
    const [il, it, ir, ib] = spriteBox(item);
    assert.ok(ir <= l || il >= r || ib <= t || it >= b, `${item.asset} at ${item.x},${item.y} overlaps the logo box ${TITLE_LOGO_BOX.join(',')}`);
  }
});

test('a taller sky lowers the cloud limit with the far hills', () => {
  const low = { ...TITLE_LAYOUT, clouds: [{ asset: 'cloud-1' as const, x: 42, y: 90 }] };
  assert.match(validateSceneLayout('Title', low).join('\n'), /cloud-1 at 42,90 sinks behind the far hills; keep its bottom at y 86 or above/);
});

test('the email step shows a square title scene by day instead of the pasted campus screenshot', () => {
  renderEmailStep();
  const art = screen.getByTestId('title-art');
  assert.match(art.className, /\baspect-square\b/, 'the title art keeps a square frame');
  assertAbsent(art.querySelector('img'), 'no pasted image inside the title art');
  assertAbsent(document.querySelector('img[src*="pixel_campus_day"]'));
  const scene = screen.getByRole('img', { name: TITLE_SCENE_LABEL });
  assert.equal(scene.querySelector('[data-sky]')?.getAttribute('data-sky'), 'day', 'the title sky is pinned to day');
  assert.ok(art.textContent?.includes('대대대'), 'the text logo stays readable text');
});

test('a badge strip under the title shows every school of the mock league in one loop', () => {
  renderEmailStep();
  const belt = screen.getByTestId('school-belt');
  assert.ok(belt.textContent?.includes(`전국 ${REAL_UNIVERSITIES.length}개 대학 대항전`));
  const track = belt.querySelector('.school-belt-track')!;
  assert.equal(track.closest('[aria-hidden="true"]') !== null, true, 'the badges are decoration');
  assert.equal(track.children.length, REAL_UNIVERSITIES.length * 2, 'the list is drawn twice so the loop is seamless');
});

test('the logo bounce and the badge loop stop for reduced motion', () => {
  const css = readFileSync(new URL('../src/index.css', import.meta.url), 'utf8');
  const blocks = [...css.matchAll(/@media \(prefers-reduced-motion: reduce\) \{([^@]*?)\n\}/g)].map((m) => m[1]).join('\n');
  for (const cls of ['.animate-logo-bounce', '.school-belt-track']) {
    assert.match(blocks, new RegExp(`\\${cls}[^{]*\\{[^}]*animation: none`), `${cls} keeps moving under reduced motion`);
  }
});
