import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import React, { useState } from 'react';
import { JSDOM } from 'jsdom';
import { assertAbsent } from './helpers/domAssert';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window,
  document: dom.window.document,
  navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement,
  Node: dom.window.Node,
  IS_REACT_ACT_ENVIRONMENT: true,
})) {
  Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
}

const { render, screen, fireEvent, cleanup } = await import('@testing-library/react');
const { DurationPicker, clampDurationHours, formatDurationHours } = await import('../src/components/DurationPicker.tsx');

afterEach(() => cleanup());
after(() => dom.window.close());

function Harness({ initial, min, max, presets }: { initial: number; min: number; max: number; presets: readonly number[] }) {
  const [value, setValue] = useState(initial);
  return React.createElement(DurationPicker, { label: '연합 기간', value, min, max, presets, onChange: setValue });
}

const valueText = () => screen.getByRole('status', { name: '연합 기간 선택값' }).textContent;
const button = (name: string) => screen.getByRole('button', { name }) as HTMLButtonElement;

test('clampDurationHours: 1시간 단위 반올림과 범위 고정', () => {
  assert.equal(clampDurationHours(5, 6, 72), 6);
  assert.equal(clampDurationHours(73, 6, 72), 72);
  assert.equal(clampDurationHours(12.4, 6, 72), 12);
  assert.equal(clampDurationHours(Number.NaN, 1, 24), 1);
});

test('formatDurationHours: 24시간 이상은 일·시간 보조 표기', () => {
  assert.equal(formatDurationHours(6), '6시간');
  assert.equal(formatDurationHours(24), '24시간 (1일)');
  assert.equal(formatDurationHours(30), '30시간 (1일 6시간)');
  assert.equal(formatDurationHours(72), '72시간 (3일)');
});

test('프리셋 선택은 aria-pressed로 표시되고, 범위 밖 프리셋은 숨긴다', () => {
  render(React.createElement(Harness, { initial: 24, min: 6, max: 72, presets: [1, 6, 12, 24, 48, 72, 96] }));
  assertAbsent(screen.queryByRole('button', { name: '1h' }));
  assertAbsent(screen.queryByRole('button', { name: '96h' }));
  assert.equal(button('24h').getAttribute('aria-pressed'), 'true');
  fireEvent.click(button('48h'));
  assert.equal(valueText(), '48시간 (2일)');
  assert.equal(button('48h').getAttribute('aria-pressed'), 'true');
  assert.equal(button('24h').getAttribute('aria-pressed'), 'false');
});

test('스테퍼는 1시간씩 바꾸고 최소·최대에서 비활성화된다 (연합 6~72h, 대결 1~24h 경계)', () => {
  render(React.createElement(Harness, { initial: 7, min: 6, max: 72, presets: [6, 72] }));
  fireEvent.click(button('연합 기간 1시간 줄이기'));
  assert.equal(valueText(), '6시간');
  assert.equal(button('연합 기간 1시간 줄이기').disabled, true);
  fireEvent.click(button('72h'));
  assert.equal(button('연합 기간 1시간 늘리기').disabled, true);
  fireEvent.click(button('연합 기간 1시간 줄이기'));
  assert.equal(valueText(), '71시간 (2일 23시간)');

  cleanup();
  render(React.createElement(Harness, { initial: 24, min: 1, max: 24, presets: [1, 3, 6, 12, 24] }));
  assert.equal(button('연합 기간 1시간 늘리기').disabled, true);
  fireEvent.click(button('1h'));
  assert.equal(button('연합 기간 1시간 줄이기').disabled, true);
  assert.equal(valueText(), '1시간');
});
