import { useCallback, useEffect, useRef, useState } from 'react';
import { useTurnstileLoader } from './useTurnstileLoader';
import { turnstileSitekey, type TurnstileApi } from '../utils/turnstile';

/** loading: script on its way. ready: widget shown. unavailable: script failed, `onToken('')` was called (plan P6). */
export type TurnstileWidgetPhase = 'loading' | 'ready' | 'unavailable';

/**
 * One Turnstile widget for as long as the calling component is mounted (SCRUM-121, SCRUM-122).
 * Put `containerRef` on an empty element. Each token goes to `onToken`; when the script cannot load, `onToken('')`
 * is called once so the caller can send the request without a widget. The widget is removed on unmount.
 */
export function useTurnstileWidget(action: string, onToken: (token: string) => void) {
  const loadTurnstile = useTurnstileLoader();
  const containerRef = useRef<HTMLDivElement>(null);
  const apiRef = useRef<TurnstileApi | null>(null);
  const widgetRef = useRef<string | undefined>(undefined);
  const onTokenRef = useRef(onToken);
  useEffect(() => { onTokenRef.current = onToken; }, [onToken]);
  const [phase, setPhase] = useState<TurnstileWidgetPhase>('loading');
  const [widgetError, setWidgetError] = useState(false);

  useEffect(() => {
    let active = true;
    loadTurnstile().then((api) => {
      if (!active || !containerRef.current) return;
      apiRef.current = api;
      widgetRef.current = api.render(containerRef.current, {
        sitekey: turnstileSitekey(),
        action,
        // The widget retries on its own after an error (retry: auto), so this only tells the user.
        'error-callback': () => setWidgetError(true),
        callback: (token) => {
          setWidgetError(false);
          onTokenRef.current(token);
        },
      });
      setPhase('ready');
    }, () => {
      if (!active) return;
      setPhase('unavailable');
      onTokenRef.current('');
    });
    return () => {
      active = false;
      if (apiRef.current && widgetRef.current !== undefined) apiRef.current.remove(widgetRef.current);
      apiRef.current = null;
      widgetRef.current = undefined;
    };
  }, [loadTurnstile, action]);

  /** A token can be verified only once: reset the widget for a new one, or without a widget send '' again. */
  const reset = useCallback(() => {
    setWidgetError(false);
    if (apiRef.current && widgetRef.current !== undefined) apiRef.current.reset(widgetRef.current);
    else onTokenRef.current('');
  }, []);

  return { containerRef, phase, widgetError, reset };
}
