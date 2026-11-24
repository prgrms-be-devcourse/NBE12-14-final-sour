import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { loadEnv } from 'vite';

// Data-source switch and dev proxy (SCRUM-146). Plan: .agents/01-PLAN/261118-1021-frontend_prototype_migration_plan.md §5 단계 0.
const { API_MODE, DEFAULT_API_MODE, resolveApiMode, selectByMode } = await import('../src/api/mode');
const { apiProxy, API_PROXY_PATHS, DEFAULT_API_PROXY_TARGET, default: viteConfig } = await import('../vite.config');

test('VITE_API_MODE가 없거나 비어 있으면 목업 모드다', () => {
  assert.equal(DEFAULT_API_MODE, 'mock');
  assert.equal(resolveApiMode(undefined), 'mock');
  assert.equal(resolveApiMode({}), 'mock');
  assert.equal(resolveApiMode({ VITE_API_MODE: '' }), 'mock');
  assert.equal(API_MODE, 'mock', '테스트 환경(import.meta.env 없음)도 목업 모드');
});

test('api와 mock을 받고, 앞뒤 공백은 무시한다', () => {
  assert.equal(resolveApiMode({ VITE_API_MODE: 'api' }), 'api');
  assert.equal(resolveApiMode({ VITE_API_MODE: 'mock' }), 'mock');
  assert.equal(resolveApiMode({ VITE_API_MODE: ' api ' }), 'api');
});

test('모르는 값은 조용히 목업으로 가지 않고 시작할 때 실패한다', () => {
  for (const value of ['apl', 'API', 'real', 'true']) {
    assert.throws(() => resolveApiMode({ VITE_API_MODE: value }), /VITE_API_MODE는 api 또는 mock/, value);
  }
});

test('selectByMode는 현재 모드의 구현을 고른다', () => {
  const implementations = { api: 'from-api', mock: 'from-mock' };
  assert.equal(selectByMode(implementations, 'api'), 'from-api');
  assert.equal(selectByMode(implementations, 'mock'), 'from-mock');
  assert.equal(selectByMode(implementations), 'from-mock', '기본은 API_MODE');
});

test('npm run dev는 API 모드, dev:mock은 목업 모드 dev 서버를 커밋된 .env.<mode>로 띄운다', () => {
  const frontendDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
  const scripts = JSON.parse(readFileSync(path.join(frontendDir, 'package.json'), 'utf8')).scripts;
  // Team decision (2026-11-23): `dev` talks to the backend. An inline `VITE_API_MODE=api vite` fails in Windows cmd;
  // `--mode <name>` loads .env.<name> on every OS.
  const server = 'vite --port=3000 --host=0.0.0.0';
  assert.equal(scripts.dev, `${server} --mode api`);
  assert.equal(scripts['dev:mock'], `${server} --mode mock`);
  // A shell value outranks env files in loadEnv, so this checks the files alone.
  const shellValue = process.env.VITE_API_MODE;
  delete process.env.VITE_API_MODE;
  try {
    assert.equal(resolveApiMode(loadEnv('api', frontendDir)), 'api');
    assert.equal(resolveApiMode(loadEnv('mock', frontendDir)), 'mock');
  } finally {
    if (shellValue !== undefined) process.env.VITE_API_MODE = shellValue;
  }
  if (typeof viteConfig !== 'function') throw new Error('Expected Vite config factory');
  for (const mode of ['api', 'mock']) {
    const config = viteConfig({ command: 'serve', mode, isPreview: false, isSsrBuild: false });
    assert.deepEqual(config.define, { __CAMPUS_MOCK_TOOLS__: 'true', __CAMPUS_SERVICE_BUILD__: 'false' }, `${mode}: dev 서버라 테스트 도구는 그대로`);
  }
});

test('프록시는 /api/…와 /ws(WebSocket)를 같은 백엔드로 보낸다', () => {
  const proxy = apiProxy('http://backend:9090');
  assert.deepEqual(Object.keys(proxy).sort(), [API_PROXY_PATHS.api, API_PROXY_PATHS.ws].sort());
  assert.deepEqual(proxy[API_PROXY_PATHS.api], { target: 'http://backend:9090' });
  assert.deepEqual(proxy[API_PROXY_PATHS.ws], { target: 'http://backend:9090', ws: true });
  // Host를 바꾸면 백엔드의 같은 출처 검사(CORS·STOMP handshake)가 3000번 이외 주소에서 실패한다.
  for (const options of Object.values(proxy)) assert.equal('changeOrigin' in options, false);
});

/** Vite's rule: a key starting with '^' is a RegExp tested against the URL, any other key is a prefix. */
function forwarded(url: string): string | null {
  const keys = Object.keys(apiProxy('http://backend:9090'));
  return keys.find((key) => (key.startsWith('^') ? new RegExp(key).test(url) : url.startsWith(key))) ?? null;
}

test('백엔드 경로만 넘기고, 앞부분만 같은 프론트 경로는 넘기지 않는다', () => {
  for (const url of ['/api/v1/universities', '/api/v1/universities?keyword=x', '/api/v1/auth/reissue']) {
    assert.equal(forwarded(url), API_PROXY_PATHS.api, url);
  }
  for (const url of ['/ws', '/ws?token=x']) assert.equal(forwarded(url), API_PROXY_PATHS.ws, url);
  for (const url of ['/', '/api', '/apiary', '/wsx', '/ws/', '/src/api/mode.ts', '/assets/ws.png']) {
    assert.equal(forwarded(url), null, url);
  }
});

test('SCRUM-212: 도구는 로컬 dev와 지정 테스트 빌드에서만 켜지고 preview가 빌드 설정을 풀지 않는다', () => {
  assert.equal(typeof viteConfig, 'function');
  if (typeof viteConfig !== 'function') throw new Error('Expected Vite config factory');
  for (const [command, mode, isPreview, mockTools, serviceBuild] of [
    ['serve', 'development', false, true, false],
    ['build', 'campus-test', false, true, false],
    ['build', 'production', false, false, true],
    ['build', 'development', false, false, true],
    ['build', 'staging', false, false, true],
    ['serve', 'campus-test', true, false, false],
  ] as const) {
    const config = viteConfig({ command, mode, isPreview, isSsrBuild: false });
    assert.deepEqual(config.define, { __CAMPUS_MOCK_TOOLS__: String(mockTools), __CAMPUS_SERVICE_BUILD__: String(serviceBuild) });
  }
});

async function resolvedConfig() {
  const config = typeof viteConfig === 'function'
    ? viteConfig({ command: 'serve', mode: 'test', isSsrBuild: false, isPreview: false })
    : viteConfig;
  return { server: config.server?.proxy, preview: config.preview?.proxy };
}

test('개발 서버와 preview 서버에 프록시가 걸리고, 기본 대상은 8080번이다', async () => {
  const saved = process.env.API_PROXY_TARGET;
  delete process.env.API_PROXY_TARGET;
  try {
    const { server, preview } = await resolvedConfig();
    assert.equal(DEFAULT_API_PROXY_TARGET, 'http://localhost:8080');
    assert.deepEqual(server, apiProxy(DEFAULT_API_PROXY_TARGET));
    assert.deepEqual(preview, apiProxy(DEFAULT_API_PROXY_TARGET));
  } finally {
    if (saved !== undefined) process.env.API_PROXY_TARGET = saved;
  }
});

test('API_PROXY_TARGET으로 백엔드 주소를 바꾼다', async () => {
  const saved = process.env.API_PROXY_TARGET;
  process.env.API_PROXY_TARGET = 'http://127.0.0.1:18080';
  try {
    const { server, preview } = await resolvedConfig();
    assert.deepEqual(server, apiProxy('http://127.0.0.1:18080'));
    assert.deepEqual(preview, apiProxy('http://127.0.0.1:18080'));
  } finally {
    if (saved === undefined) delete process.env.API_PROXY_TARGET;
    else process.env.API_PROXY_TARGET = saved;
  }
});
