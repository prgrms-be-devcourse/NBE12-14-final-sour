// API origin for SCRUM-286; plan: .agents/01-PLAN/261125-1652-frontend_vercel_api_origin_plan.md §3-1.
// An empty value uses the same origin. A different origin needs credentials: 'include' for the refresh cookie.
export function resolveApiOrigin(env: Readonly<Record<string, unknown>> | undefined = import.meta.env): string {
  const raw = env?.VITE_API_ORIGIN;
  if (raw === undefined || (typeof raw === 'string' && !raw.trim())) return '';
  const invalid = () => new Error(`VITE_API_ORIGIN은 유효한 API origin이어야 합니다: ${JSON.stringify(raw)}`);
  if (typeof raw !== 'string') throw invalid();
  let url: URL;
  try { url = new URL(raw.trim()); } catch { throw invalid(); }
  if (!(url.protocol === 'https:' || (url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)))
    || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw invalid();
  return url.origin;
}

export const API_ORIGIN = resolveApiOrigin();
