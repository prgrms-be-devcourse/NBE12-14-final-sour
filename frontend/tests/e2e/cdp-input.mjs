/** Dispatch a complete native keystroke, not a DOM KeyboardEvent.
 * rawKeyDown alone omits the character phase needed for native Enter activation.
 * Protocol: https://chromedevtools.github.io/devtools-protocol/tot/Input/#method-dispatchKeyEvent
 */
/** @param {(method: string, params: Record<string, unknown>) => Promise<unknown>} send
 * @param {string} key
 * @param {boolean} [shift]
 */
export async function pressKey(send, key, shift = false) {
  const keys = {
    Enter: { code: 'Enter', windowsVirtualKeyCode: 13 },
    ' ': { code: 'Space', windowsVirtualKeyCode: 32 },
    Tab: { code: 'Tab', windowsVirtualKeyCode: 9 },
    Escape: { code: 'Escape', windowsVirtualKeyCode: 27 },
  };
  const definition = keys[key];
  if (!definition) throw new Error(`Unsupported QA key: ${key}`);
  const params = { key, ...definition, modifiers: shift ? 8 : 0 };
  const text = key === 'Enter' ? '\r' : key === ' ' ? ' ' : undefined;
  await send('Input.dispatchKeyEvent', text === undefined
    ? { ...params, type: 'rawKeyDown' }
    : { ...params, type: 'keyDown', text, unmodifiedText: text });
  await send('Input.dispatchKeyEvent', { ...params, type: 'keyUp' });
}
