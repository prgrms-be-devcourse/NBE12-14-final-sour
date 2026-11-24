import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import {fileURLToPath} from 'url';
import {defineConfig, loadEnv, type ProxyOptions} from 'vite';

// Same folder as Vite's injected __dirname, but also defined when tests import this file as plain ESM.
const configDir = path.dirname(fileURLToPath(import.meta.url));

/** Backend for the dev proxy when API_PROXY_TARGET is unset: Spring Boot's default port. */
export const DEFAULT_API_PROXY_TARGET = 'http://localhost:8080';

/**
 * Same-origin calls to the backend (SCRUM-146, plan decision D-6). The refresh cookie is SameSite=Strict with
 * Path=/api/v1/auth, so the browser must see the API on the page's own origin. `changeOrigin` stays false: the
 * backend then sees the page's Host, so its same-origin checks (CORS, STOMP handshake) pass from any address.
 *
 * Keys start with `^`, which makes Vite treat them as regular expressions. A plain '/api' key is a prefix match and
 * also forwarded '/apiary' and '/wsx' (checked against the dev server, 2026-11-18). STOMP uses plain WebSocket at
 * /ws with no SockJS paths (ChatWebSocketConfig).
 */
export const API_PROXY_PATHS = { api: '^/api/', ws: '^/ws(?:$|\\?)' } as const;

export function apiProxy(target: string): Record<string, ProxyOptions> {
  return {
    [API_PROXY_PATHS.api]: { target },
    [API_PROXY_PATHS.ws]: { target, ws: true },
  };
}

export default defineConfig(({command, mode, isPreview}) => {
  const testBuild = command === 'build' && mode === 'campus-test';
  const localDev = command === 'serve' && !isPreview;
  // API_PROXY_TARGET has no VITE_ prefix: it configures the dev server only and never reaches the browser bundle.
  const env = loadEnv(mode, configDir, '');
  const proxy = apiProxy(env.API_PROXY_TARGET || DEFAULT_API_PROXY_TARGET);
  return {
    plugins: [react(), tailwindcss()],
    define: {
      __CAMPUS_MOCK_TOOLS__: JSON.stringify(localDev || testBuild),
      __CAMPUS_SERVICE_BUILD__: JSON.stringify(command === 'build' && !testBuild),
    },
    build: {
      // HD campus sprites stay separate files so each layer fetches only the scale it draws
      // (docs/261108-1014-campus_hd_default_plan.md 4-4); other small assets inline as before.
      assetsInlineLimit: (filePath: string) => (/[\\/]campus-hd[\\/]/.test(filePath) ? false : undefined),
    },
    resolve: {
      alias: {
        '@': path.resolve(configDir, '.'),
      },
    },
    server: {
      port: 3000,
      host: '0.0.0.0',
      // HMR is disabled in AI Studio via DISABLE_HMR env var.
      // Do not modify—file watching is disabled to prevent flickering during agent edits.
      hmr: process.env.DISABLE_HMR !== 'true',
      // Disable file watching when DISABLE_HMR is true to save CPU during agent edits.
      watch: process.env.DISABLE_HMR === 'true' ? null : {},
      proxy,
    },
    // `vite preview` serves the built app the same way, so browser tests of a build reach the backend too.
    preview: {
      proxy,
    },
  };
});
