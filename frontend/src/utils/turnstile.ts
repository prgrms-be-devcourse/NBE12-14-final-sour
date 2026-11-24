// Cloudflare Turnstile in the browser (SCRUM-121, plan .agents/01-PLAN/261117-0848-turnstile_bot_verification_plan.md §3-4).
// Values follow the Cloudflare docs: testing page (updated 2026-11-05) and client-side rendering page (2026-11-16).
// The token only proves something once the server sends it to siteverify; the browser never calls siteverify.

/** Explicit rendering, loaded from Cloudflare itself. Proxying or caching this file breaks Turnstile on updates. */
export const TURNSTILE_SCRIPT_URL = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';

/** Cloudflare's visible test sitekey: always passes, works on any domain including localhost. */
export const TURNSTILE_TEST_SITEKEY = '1x00000000000000000000AA';

/** Must match the server's expected action (AbuseVerificationService.CLICK_ACTION, SupportBotCheck.GUEST_ACTION). */
export const TURNSTILE_ACTIONS = { click: 'abuse_click', supportGuest: 'support_guest' } as const;

/** Waiting longer than this for the script counts as "could not load" (plan P6: verify without a token). */
export const TURNSTILE_LOAD_TIMEOUT_MS = 8000;

export interface TurnstileRenderOptions {
  sitekey: string;
  action?: string;
  callback?: (token: string) => void;
  'error-callback'?: (code: string) => void;
  'expired-callback'?: () => void;
  theme?: 'auto' | 'light' | 'dark';
  size?: 'normal' | 'flexible' | 'compact';
  language?: string;
}

/** The part of `window.turnstile` this app uses. `render` returns the widget id the other calls take. */
export interface TurnstileApi {
  render(container: HTMLElement | string, options: TurnstileRenderOptions): string | undefined;
  reset(widgetId?: string): void;
  remove(widgetId?: string): void;
}

export type TurnstileLoader = () => Promise<TurnstileApi>;

/** `VITE_TURNSTILE_SITEKEY` when set, otherwise the test key. Pass `import.meta.env`; tests pass a plain object. */
export function turnstileSitekey(env: Readonly<Record<string, unknown>> | undefined = import.meta.env): string {
  const value = env?.VITE_TURNSTILE_SITEKEY;
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : TURNSTILE_TEST_SITEKEY;
}

/**
 * Loads the Turnstile script into `doc` once. Concurrent calls share one load; a failed load (error event,
 * missing `window.turnstile` or timeout) removes its script so the next call tries again.
 */
export function createScriptTurnstileLoader(doc: Document, timeoutMs = TURNSTILE_LOAD_TIMEOUT_MS): TurnstileLoader {
  let loading: Promise<TurnstileApi> | null = null;
  const current = () => (doc.defaultView as (Window & { turnstile?: TurnstileApi }) | null)?.turnstile;

  return () => {
    const ready = current();
    if (ready) return Promise.resolve(ready);
    if (loading) return loading;
    loading = new Promise<TurnstileApi>((resolve, reject) => {
      const script = doc.createElement('script');
      script.src = TURNSTILE_SCRIPT_URL;
      script.async = true;
      const fail = (reason: string) => {
        clearTimeout(timer);
        script.remove();
        loading = null;
        reject(new Error(`Turnstile 스크립트를 불러오지 못했습니다 (${reason})`));
      };
      const timer = setTimeout(() => fail('시간 초과'), timeoutMs);
      script.addEventListener('load', () => {
        const api = current();
        if (!api) {
          fail('window.turnstile 없음');
          return;
        }
        clearTimeout(timer);
        resolve(api);
      });
      script.addEventListener('error', () => fail('네트워크 오류'));
      doc.head.appendChild(script);
    });
    return loading;
  };
}

let sharedLoader: TurnstileLoader | null = null;

/** The loader for this page's document, created on first use. */
export function documentTurnstileLoader(): TurnstileLoader {
  sharedLoader ??= createScriptTurnstileLoader(document);
  return sharedLoader;
}
