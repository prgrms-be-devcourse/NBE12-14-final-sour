// Drives the opt-in mock tools in local dev or a campus-test build (not a service build).
export const MOCK_TOOL_BUTTON = '.game-top-bar button[aria-haspopup="dialog"]';
const DIALOG = 'dialog[open][aria-label="테스트 도구"]';

export async function openMockTools(evaluate) {
  await evaluate(`(() => {
    if (document.querySelector(${JSON.stringify(DIALOG)})) return;
    const button = document.querySelector(${JSON.stringify(MOCK_TOOL_BUTTON)});
    if (!button) throw new Error('Mock tools require Vite dev or a campus-test build');
    button.click();
  })()`);
  for (let i = 0; i < 50; i++) {
    if (await evaluate(`!!document.querySelector(${JSON.stringify(DIALOG)})`)) return;
    await new Promise((resolve) => setTimeout(resolve, 40));
  }
  throw new Error('Mock tools dialog did not open');
}

export async function selectMockScreen(evaluate, label) {
  await openMockTools(evaluate);
  await evaluate(`(() => {
    const label = ${JSON.stringify(label)};
    const buttons = [...document.querySelectorAll(${JSON.stringify(`${DIALOG} nav button`)})];
    const matches = buttons.filter((button) => button.textContent.trim() === label);
    if (matches.length !== 1) throw new Error('Expected one mock screen: ' + label);
    matches[0].click();
  })()`);
  for (let i = 0; i < 50; i++) {
    if (await evaluate(`!document.querySelector(${JSON.stringify(DIALOG)})`)) return;
    await new Promise((resolve) => setTimeout(resolve, 40));
  }
  throw new Error('Mock tools dialog did not close after screen selection');
}
