import assert from 'node:assert/strict';
import { test } from 'node:test';
import { JSDOM } from 'jsdom';
import { lockPageScroll } from '../src/utils/lockPageScroll';

test('페이지 스크롤 잠금은 마지막 모달이 해제될 때만 풀리고 중복 해제는 무시한다', (t) => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>');
  t.after(() => dom.window.close());
  const { document } = dom.window;
  document.body.style.background = 'red';
  document.documentElement.style.setProperty('scroll-behavior', 'smooth', 'important');
  const bodyBefore = document.body.style.cssText;
  const rootBefore = document.documentElement.style.cssText;
  const releaseFirst = lockPageScroll(document);
  const releaseSecond = lockPageScroll(document);
  releaseFirst();
  releaseFirst();
  assert.equal(document.body.style.position, 'fixed');
  assert.equal(document.documentElement.style.overflow, 'hidden');
  document.body.style.color = 'blue'; // Unrelated changes during the lock must survive.
  releaseSecond();
  assert.equal(document.body.style.cssText, `${bodyBefore} color: blue;`);
  assert.equal(document.documentElement.style.cssText, rootBefore);
  releaseSecond();
  assert.equal(document.body.style.position, '');
});

test('서로 다른 문서는 스크롤 잠금 상태를 공유하지 않고 다시 열 수 있다', (t) => {
  const first = new JSDOM('<!doctype html><html><body></body></html>');
  const second = new JSDOM('<!doctype html><html><body></body></html>');
  t.after(() => { first.window.close(); second.window.close(); });
  const a = first.window.document, b = second.window.document;
  const releaseA = lockPageScroll(a), releaseB = lockPageScroll(b);
  releaseA();
  assert.equal(a.body.style.position, '');
  assert.equal(b.body.style.position, 'fixed');
  const reopenA = lockPageScroll(a);
  assert.equal(a.body.style.position, 'fixed');
  reopenA(); releaseB();
  assert.equal(a.body.style.position, '');
  assert.equal(b.body.style.position, '');
});
