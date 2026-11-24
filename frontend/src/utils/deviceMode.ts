/**
 * Device mode (T2, docs/261110-1005-mockup_remaining_tasks_plan.md §5).
 * - `framed`: desktop presentation. Fixed 390×844 phone frames, the presentation header and the mock menus.
 * - `full`: a real phone. Service screens fill the visible viewport (100dvh) and the mock menus move into a sheet.
 *
 * Phones held upright match the width query; phones on their side match the height query, so turning a
 * phone keeps the full layout (landscape scrolls the page, per the M6 approval).
 */
export type DeviceMode = 'framed' | 'full';

export const MOBILE_DEVICE_QUERY = '(pointer: coarse) and (max-width: 480px), (pointer: coarse) and (max-height: 480px)';

/** `?device=full` or `?device=framed` forces a mode (demos, e2e); otherwise the media query decides. */
export function resolveDeviceMode(search: string, mobileQueryMatches: boolean): DeviceMode {
  const forced = new URLSearchParams(search).get('device');
  if (forced === 'full' || forced === 'framed') return forced;
  return mobileQueryMatches ? 'full' : 'framed';
}

export type GameLayout = 'wide' | 'compact' | 'legacy';
// Keep ~240 CSS px per sidebar while leaving twice that for the click surface.
export const GAME_WIDE_QUERY = '(min-width: 64rem) and (min-height: 30.0625rem) and (orientation: landscape)';

export function resolveGameLayout(search: string, wide: boolean): GameLayout {
  const forced = new URLSearchParams(search).get('device');
  if (forced === 'framed') return 'legacy';
  if (forced === 'full') return 'compact';
  return wide ? 'wide' : 'compact';
}
