import assert from 'node:assert/strict';
import { test } from 'node:test';
import { vercelBuildArgs } from '../scripts/vercel-build';
import viteConfig, { checkDeployApiOrigin } from '../vite.config';

test('Vercel preview만 campus-test 빌드를 선택한다', () => {
  assert.deepEqual(vercelBuildArgs({ VERCEL_ENV: 'preview' }), ['build', '--mode', 'campus-test']);
  for (const env of [{ VERCEL_ENV: 'production' }, { VERCEL_ENV: 'development' }, {}]) {
    assert.deepEqual(vercelBuildArgs(env), ['build']);
  }
});

test('배포 origin 검사는 빌드와 환경에 따라 적용한다', () => {
  const build = { command: 'build', testBuild: false, isPreview: false } as const;
  assert.throws(() => checkDeployApiOrigin({ VERCEL: '1' }, build), /VITE_API_ORIGIN/);
  assert.doesNotThrow(() => checkDeployApiOrigin({ VERCEL: '1', VITE_API_ORIGIN: 'https://api.example.com' }, build));
  assert.doesNotThrow(() => checkDeployApiOrigin({ VERCEL: '1' }, { ...build, testBuild: true }));
  assert.doesNotThrow(() => checkDeployApiOrigin({ VERCEL: '1' }, { ...build, isPreview: true }));
  assert.doesNotThrow(() => checkDeployApiOrigin({}, build));
  for (const vercel of [undefined, '1']) {
    assert.throws(() => checkDeployApiOrigin({ VERCEL: vercel, VITE_API_ORIGIN: 'http://api.example.com' }, build));
  }
  assert.doesNotThrow(() => checkDeployApiOrigin({ VERCEL: '1' }, { ...build, command: 'serve' }));
});

test('Vite config는 process.env의 Vercel 배포 설정을 검사한다', () => {
  const vercel = process.env.VERCEL;
  const origin = process.env.VITE_API_ORIGIN;
  process.env.VERCEL = '1';
  // loadEnv는 process.env에 존재하는 키만 파일 값을 덮어쓰므로 빈 값으로 로컬 .env*를 무시한다.
  process.env.VITE_API_ORIGIN = '';
  try {
    if (typeof viteConfig !== 'function') throw new Error('Expected Vite config factory');
    assert.throws(() => viteConfig({ command: 'build', mode: 'production', isPreview: false, isSsrBuild: false }), /VITE_API_ORIGIN/);
  } finally {
    if (vercel === undefined) delete process.env.VERCEL; else process.env.VERCEL = vercel;
    if (origin === undefined) delete process.env.VITE_API_ORIGIN; else process.env.VITE_API_ORIGIN = origin;
  }
});
