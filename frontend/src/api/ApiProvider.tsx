import React, { createContext, useContext, useState, useSyncExternalStore } from 'react';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { createApiClient, type ApiClient } from './client';
import type { ApiFaultMode } from './faults';
import { createQueryClient } from './queryClient';
import type { SessionState } from './session';

const ApiClientContext = createContext<ApiClient | null>(null);

interface ApiProviderProps {
  /** Tests pass their own client (fake fetch) and query client; the app uses the defaults. */
  client?: ApiClient;
  queryClient?: QueryClient;
  children: React.ReactNode;
}

/** The API client and TanStack Query for everything below it (SCRUM-147). */
export const ApiProvider: React.FC<ApiProviderProps> = ({ client, queryClient, children }) => {
  const [defaultClient] = useState(() => client ?? createApiClient());
  const [defaultQueryClient] = useState(() => queryClient ?? createQueryClient());
  return (
    <ApiClientContext.Provider value={client ?? defaultClient}>
      <QueryClientProvider client={queryClient ?? defaultQueryClient}>{children}</QueryClientProvider>
    </ApiClientContext.Provider>
  );
};

export function useApiClient(): ApiClient {
  const client = useContext(ApiClientContext);
  if (!client) throw new Error('ApiProvider 안에서 useApiClient를 사용하세요.');
  return client;
}

/**
 * The API session state. When it turns to signed-out with `ended` set (another device logged in, suspension, refresh
 * expired), the app shows the login screen with `ended.message` (wired with the login screen, SCRUM-152).
 */
export function useAuthSession(): SessionState {
  const { session } = useApiClient();
  return useSyncExternalStore(session.subscribe, session.getState, session.getState);
}

/** The client's fault-injection mode and its setter, for the test tool. */
export function useApiFaultMode(): [ApiFaultMode, (mode: ApiFaultMode) => void] {
  const { faults } = useApiClient();
  const mode = useSyncExternalStore(faults.subscribe, faults.getMode, faults.getMode);
  return [mode, faults.setMode];
}

/** Whether click submissions are refused with 429 in this browser, and its setter, for the admin test tool. */
export function useApiClickRefusal(): [boolean, (on: boolean) => void] {
  const { faults } = useApiClient();
  const on = useSyncExternalStore(faults.subscribe, faults.getClickRefusal, faults.getClickRefusal);
  return [on, faults.setClickRefusal];
}
