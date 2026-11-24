import { createContext, useContext, type ReactNode } from 'react';
import { documentTurnstileLoader, type TurnstileLoader } from '../utils/turnstile';

// Which Turnstile loader the bot check uses (SCRUM-121). The app loads the Cloudflare script; tests pass a fake.
const TurnstileLoaderContext = createContext<TurnstileLoader | null>(null);

export function TurnstileLoaderProvider({ loader, children }: { loader?: TurnstileLoader; children: ReactNode }) {
  return <TurnstileLoaderContext.Provider value={loader ?? null}>{children}</TurnstileLoaderContext.Provider>;
}

export function useTurnstileLoader(): TurnstileLoader {
  return useContext(TurnstileLoaderContext) ?? documentTurnstileLoader();
}
