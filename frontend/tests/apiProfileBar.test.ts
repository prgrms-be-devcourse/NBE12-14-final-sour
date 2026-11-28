import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { after, test } from 'node:test';
import React from 'react';
import { JSDOM } from 'jsdom';
import type { MyInfo } from '../src/api/users';
import { stubMatchMedia } from './helpers/matchMedia';
import { assertAbsent, assertSameNode } from './helpers/domAssert';

const assets = registerHooks({ load(url, context, nextLoad) {
  if (/\/api\/mode\.ts$/.test(url)) return {
    format: 'module', source: 'export const API_MODE = "api"; export function selectByMode(impl) { return impl.api; }', shortCircuit: true,
  };
  if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) return { format: 'module', source: 'export default "/asset";', shortCircuit: true };
  return nextLoad(url, context);
} });
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window, document: dom.window.document, navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement, Node: dom.window.Node,
  requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window), cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window),
  IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });

const { default: App } = await import('../src/App');
const { render, cleanup, fireEvent, screen, within } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createQueryClient } = await import('../src/api/queryClient');
const { userKeys } = await import('../src/api/users');
const { soundManager } = await import('../src/utils/sound');
soundManager.enabled = false;
after(() => { assets.deregister(); dom.window.close(); });

const ME: MyInfo = {
  id: 30, nickname: '캠퍼스 학생', email: 'test@example.test', role: 'USER', status: 'ACTIVE', profileImageUrl: null,
  university: { id: 8, name: '한양대학교', logoUrl: null }, department: { id: 1, name: '공학과' },
  clicker: { level: 1, clickPower: 1, nextLevelRequiredScore: 10 }, totalContribScore: 100, totalAttackScore: 0,
};

test('서비스 상단 바의 브랜드·장식 심볼과 내 정보의 캠퍼스 직행을 유지한다', async () => {
  stubMatchMedia(dom.window as unknown as Window, []);
  const client = createApiClient({ fetch: (async (input) => {
    const path = new URL(String(input), 'http://localhost').pathname.replace(/^\/api\/v1/u, '');
    const responses: Record<string, unknown> = {
      '/users/me': ME, '/map/universities': [], '/clickers': [], '/items/me/active': null,
      '/rankings/universities': { items: [], myUniversity: null, hasNext: false, page: 0, size: 20, totalCount: 0 },
      '/items/me/history': { items: [], hasNext: false, page: 0, size: 20, totalCount: 0 },
    };
    return path in responses ? Response.json({ code: 'SUCCESS', message: '', data: responses[path] })
      : Response.json({ code: 'NOT_FOUND', message: 'No test fixture' }, { status: 404 });
  }) as typeof fetch });
  const queryClient = createQueryClient();
  client.session.signIn('test');
  queryClient.setQueryData(userKeys.me, ME);
  try {
    render(React.createElement(ApiProvider, { client, queryClient, children: React.createElement(App) }));
    const brand = screen.getByRole('button', { name: '대대대 내 캠퍼스' });
    assert.equal(brand.textContent, '대대대 / 대학교 대 대학교 - 캠퍼스 대항전');
    assert.equal(brand.querySelector('img[alt=""]')?.getAttribute('src'), '/favicon.svg');
    const profile = document.querySelector('summary')!;
    assert.ok(profile.querySelector('svg[aria-hidden="true"]'));
    const tools = screen.getByRole('button', { name: '테스트 도구' });
    assert.ok(tools.querySelector('svg[aria-hidden="true"]'));

    profile.click();
    fireEvent.click(screen.getByRole('button', { name: '내 정보' }));
    await screen.findByRole('heading', { name: '내 정보' });
    fireEvent.click(screen.getByRole('button', { name: '아이템 구매 내역' }));
    await screen.findByRole('heading', { name: '아이템 구매 내역' });
    fireEvent.click(screen.getByRole('button', { name: '내 정보로 돌아가기' }));
    await screen.findByRole('heading', { name: '내 정보' });
    fireEvent.click(screen.getByRole('button', { name: '뒤로가기' }));
    const campus = document.querySelector<HTMLElement>('[data-game-layout="my-campus"]')!;
    assert.ok(campus);
    assertSameNode(document.activeElement, within(campus).getByRole('main'));
    assertAbsent(screen.queryByText('로그인 · 서버 연동'));
  } finally {
    cleanup();
    queryClient.clear();
    client.session.signOut();
  }
});
