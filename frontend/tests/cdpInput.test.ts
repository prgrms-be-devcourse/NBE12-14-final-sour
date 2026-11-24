import assert from 'node:assert/strict';
import test from 'node:test';
import { pressKey } from './e2e/cdp-input.mjs';

test('CDP Enter/Space include the character phase for native button activation', async () => {
  for (const [key, text, code, virtualKey] of [['Enter', '\r', 'Enter', 13], [' ', ' ', 'Space', 32]] as const) {
    const calls: Array<{ method: string; params: Record<string, unknown> }> = [];
    await pressKey(async (method, params) => { calls.push({ method, params }); }, key);
    assert.deepEqual(calls, [
      { method: 'Input.dispatchKeyEvent', params: { key, code, windowsVirtualKeyCode: virtualKey, modifiers: 0, type: 'keyDown', text, unmodifiedText: text } },
      { method: 'Input.dispatchKeyEvent', params: { key, code, windowsVirtualKeyCode: virtualKey, modifiers: 0, type: 'keyUp' } },
    ]);
  }
});

test('CDP navigation keys preserve Shift and do not insert text', async () => {
  for (const key of ['Tab', 'Escape']) {
    const calls: Array<Record<string, unknown>> = [];
    await pressKey(async (_method, params) => { calls.push(params); }, key, true);
    assert.equal(calls.length, 2);
    assert.equal(calls[0].type, 'rawKeyDown');
    assert.equal(calls[1].type, 'keyUp');
    for (const params of calls) {
      assert.equal(params.modifiers, 8);
      assert.equal(params.key, key);
      assert.equal('text' in params, false);
    }
  }
});

test('unsupported CDP QA keys fail before sending input', async () => {
  let sends = 0;
  await assert.rejects(pressKey(async () => { sends++; }, 'Unknown'), /Unsupported QA key/);
  assert.equal(sends, 0);
});
