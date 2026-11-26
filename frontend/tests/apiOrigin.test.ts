import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resolveApiOrigin } from '../src/api/origin';

test('빈 API origin은 같은 origin을 쓴다', () => {
  assert.equal(resolveApiOrigin(undefined), '');
  for (const value of [undefined, '', '  ']) assert.equal(resolveApiOrigin({ VITE_API_ORIGIN: value }), '');
});

test('HTTPS origin을 정규화하고 로컬 HTTP를 허용한다', () => {
  for (const value of ['https://api.example.com', 'https://api.example.com/', ' https://api.example.com ']) {
    assert.equal(resolveApiOrigin({ VITE_API_ORIGIN: value }), 'https://api.example.com');
  }
  for (const value of ['http://localhost:8080', 'http://127.0.0.1:8080', 'http://[::1]:8080']) {
    assert.equal(resolveApiOrigin({ VITE_API_ORIGIN: value }), value);
  }
});

test('잘못된 API origin은 입력값을 포함해 거부한다', () => {
  for (const value of ['http://api.example.com', 'https://api.example.com/api/v1', 'https://api.example.com?x=1',
    'https://api.example.com#a', 'https://u:p@api.example.com', 'api.example.com', 'ftp://api.example.com']) {
    assert.throws(() => resolveApiOrigin({ VITE_API_ORIGIN: value }), (error: Error) => error.message.includes(JSON.stringify(value)));
  }
});
