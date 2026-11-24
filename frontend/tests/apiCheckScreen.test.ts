import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import React from 'react';
import { JSDOM } from 'jsdom';
import { assertAbsent } from './helpers/domAssert';

// The dev API check screen through ApiProvider, TanStack Query and the client with a fake fetch (SCRUM-147):
// search results, empty result, server error with retry, injected failure and mock mode. jsdom checks flow, not layout.
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window, document: dom.window.document, navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement, Node: dom.window.Node, IS_REACT_ACT_ENVIRONMENT: true,
})) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });

const { render, screen, fireEvent, cleanup, within } = await import('@testing-library/react');
const { ApiProvider } = await import('../src/api/ApiProvider');
const { createApiClient } = await import('../src/api/client');
const { createApiFaults } = await import('../src/api/faults');
const { createQueryClient } = await import('../src/api/queryClient');
const { ApiCheckScreen } = await import('../src/components/ApiCheckScreen');

const queryClients: Array<ReturnType<typeof createQueryClient>> = [];
afterEach(() => {
  cleanup();
  for (const client of queryClients.splice(0)) client.clear();
});
after(() => dom.window.close());

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const ok = (data: unknown) => json(200, { code: 'SUCCESS', message: '요청이 성공했습니다.', data });

function mount(answers: Array<() => Response>, mode: 'api' | 'mock' = 'api') {
  const urls: string[] = [];
  const fetch = (async (input: RequestInfo | URL) => {
    urls.push(String(input));
    return answers[Math.min(urls.length, answers.length) - 1]();
  }) as typeof globalThis.fetch;
  const faults = createApiFaults({ delayMs: 0, failDelayMs: 0 });
  const queryClient = createQueryClient();
  queryClients.push(queryClient);
  render(React.createElement(ApiProvider, {
    client: createApiClient({ fetch, faults }),
    queryClient,
    children: React.createElement(ApiCheckScreen, { mode }),
  }));
  return { urls, faults };
}

function searchFor(keyword: string) {
  fireEvent.change(screen.getByLabelText('학교 검색어'), { target: { value: keyword } });
  fireEvent.click(screen.getByRole('button', { name: '검색' }));
}

test('API 모드에서 검색하면 GET /api/v1/universities 결과를 이름·ID로 보여 준다', async () => {
  const { urls } = mount([() => ok([
    { id: 12, name: '한양대학교', logoUrl: null },
    { id: 13, name: '한양대학교 ERICA', logoUrl: '/logos/erica.png' },
  ])]);
  assert.equal(urls.length, 0, '검색 전에는 부르지 않는다');

  searchFor(' 한양 ');
  const list = await screen.findByRole('region', { name: '학교 검색 결과' });
  assert.equal(urls.length, 1);
  assert.equal(urls[0], '/api/v1/universities?keyword=%ED%95%9C%EC%96%91&size=20', '앞뒤 공백 없이 보낸다');
  assert.ok(within(list).queryByText('한양대학교') !== null);
  assert.ok(within(list).queryByText('ID 13') !== null);
  assert.equal(within(list).getByRole('status').textContent, '2개 학교');
});

test('빈 검색어는 요청하지 않고, 결과가 없으면 없다고 알린다', async () => {
  const { urls } = mount([() => ok([])]);
  searchFor('   ');
  assert.equal(urls.length, 0);
  assert.ok(screen.queryByText('검색어를 입력하고 검색을 누르세요.') !== null);

  searchFor('없는학교');
  assert.equal((await screen.findByText('검색 결과가 없습니다')).getAttribute('role'), 'status');
});

test('서버 오류는 서버 문구로 실패를 알리고, 다시 시도하면 결과를 보여 준다', async () => {
  let call = 0;
  const { urls } = mount([() => (++call === 1
    ? json(429, { code: 'COMMON_TOO_MANY_REQUESTS', message: '요청이 너무 많습니다. 잠시 후 다시 시도해주세요.', data: null })
    : ok([{ id: 1, name: '서울대학교', logoUrl: null }]))]);

  searchFor('서울');
  const alert = await screen.findByRole('alert');
  assert.match(alert.textContent ?? '', /학교 검색 조회에 실패했습니다/);
  assert.match(alert.textContent ?? '', /요청이 너무 많습니다/);
  assert.equal(urls.length, 1, '4xx는 자동 재시도하지 않는다');

  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
  await screen.findByRole('region', { name: '학교 검색 결과' });
  assertAbsent(screen.queryByRole('alert'));
  assert.equal(urls.length, 2);
});

test('1회 실패 주입은 서버에 보내지 않고 실패를 보여 주며, 다시 시도하면 정상으로 돌아간다', async () => {
  const { urls, faults } = mount([() => ok([{ id: 5, name: '부산대학교', logoUrl: null }])]);
  fireEvent.change(screen.getByLabelText('요청 실패 주입'), { target: { value: 'fail-once' } });
  assert.equal(faults.getMode(), 'fail-once');

  searchFor('부산');
  assert.match((await screen.findByRole('alert')).textContent ?? '', /\[테스트 도구\] 주입한 실패/);
  assert.equal(urls.length, 0);
  assert.equal((screen.getByLabelText('요청 실패 주입') as HTMLSelectElement).value, 'normal', '선택 상자도 정상으로 돌아온다');

  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
  await screen.findByRole('region', { name: '학교 검색 결과' });
  assert.equal(urls.length, 1);
});

test('목업 모드에서는 API를 부르지 않고 전환 방법을 알린다', () => {
  const { urls } = mount([() => ok([])], 'mock');
  assert.match(screen.getByRole('note').textContent ?? '', /VITE_API_MODE=api/);
  assertAbsent(screen.queryByLabelText('학교 검색어'));
  assert.equal(urls.length, 0);
});
