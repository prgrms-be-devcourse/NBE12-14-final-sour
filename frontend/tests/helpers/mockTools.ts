import { fireEvent, screen, within } from '@testing-library/react';
import { installDialogStub } from './dialogStub';

export function openTestTools() {
  const trigger = screen.getByRole('button', { name: '테스트 도구' });
  const win = trigger.ownerDocument.defaultView!;
  if (!win.HTMLDialogElement.prototype.showModal) installDialogStub(win);
  if (!screen.queryByRole('dialog', { name: '테스트 도구' })) fireEvent.click(trigger);
  return within(screen.getByRole('dialog', { name: '테스트 도구' }));
}

export function testViewButton(name: string | RegExp) {
  const tools = openTestTools();
  return tools.getByRole('button', { name });
}

export function clickTestView(name: string | RegExp) {
  fireEvent.click(testViewButton(name));
}

export function withToolSetting<T>(label: string, useControl: (control: HTMLElement) => T): T {
  const tools = openTestTools();
  try {
    return useControl(tools.getByLabelText(label));
  } finally {
    fireEvent.click(tools.getByRole('button', { name: /닫기/ }));
  }
}
