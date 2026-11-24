// Which data source the screens use (SCRUM-146). 'api' calls the backend through the dev-server proxy (/api, /ws);
// 'mock' keeps the mock data and demo state, so the prototype still runs without a backend. Plan:
// .agents/01-PLAN/261118-1021-frontend_prototype_migration_plan.md §5 단계 0.

import { SERVICE_BUILD } from '../utils/mockTools';

export const API_MODES = ['api', 'mock'] as const;
export type ApiMode = (typeof API_MODES)[number];

/** Local development, source tests and designated test builds can run without a backend. */
export const DEFAULT_API_MODE: ApiMode = 'mock';

function isApiMode(value: string): value is ApiMode {
  return (API_MODES as readonly string[]).includes(value);
}

/**
 * `VITE_API_MODE` when set, otherwise the default. An unknown value throws, so a typo such as `apl` fails at start-up
 * instead of quietly running on mock data. Pass `import.meta.env`; tests pass a plain object.
 */
export function resolveApiMode(env: Readonly<Record<string, unknown>> | undefined = import.meta.env): ApiMode {
  const raw = env?.VITE_API_MODE;
  if (raw === undefined || raw === '') return DEFAULT_API_MODE;
  const value = String(raw).trim();
  if (isApiMode(value)) return value;
  throw new Error(`VITE_API_MODE는 ${API_MODES.join(' 또는 ')}여야 합니다: ${JSON.stringify(raw)}`);
}

// The service build cannot enter the mock game, including from a stale VITE_API_MODE=mock setting.
export const API_MODE: ApiMode = SERVICE_BUILD ? 'api' : resolveApiMode();

/**
 * The adapter boundary: a domain hook lists one implementation per mode and gets the one for the current mode.
 * Both implementations share one type, so a screen does not know which source it reads.
 */
export function selectByMode<T>(implementations: Readonly<Record<ApiMode, T>>, mode: ApiMode = API_MODE): T {
  return implementations[mode];
}
