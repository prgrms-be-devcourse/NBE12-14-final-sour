/**
 * JSDOM has no matchMedia. Stub it so only the listed queries match.
 * A stub that matches every query also turns on the phone layout (MOBILE_DEVICE_QUERY, T2) and hides the
 * desktop menus, so tests name exactly the media features they mean.
 */
export function stubMatchMedia(window: Window, matching: readonly string[]) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({ media: query, matches: matching.includes(query), addEventListener() {}, removeEventListener() {} }),
  });
}

export const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';
