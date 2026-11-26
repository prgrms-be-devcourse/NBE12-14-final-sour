import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createUuid } from '../src/utils/uuid';

const pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function fixed(value: number): Pick<Crypto, 'getRandomValues'> {
  return { getRandomValues: (array) => {
    if (!(array instanceof Uint8Array)) throw new Error('Expected bytes');
    array.fill(value);
    return array;
  } };
}

test('randomUUID가 있으면 그 결과를 사용한다', () => {
  assert.equal(createUuid({ ...fixed(0), randomUUID: () => 'native-result' }), 'native-result');
});

test('대체 경로는 버전과 variant 비트를 설정한다', () => {
  const zero = createUuid(fixed(0));
  const ones = createUuid(fixed(255));
  assert.equal(zero, '00000000-0000-4000-8000-000000000000');
  assert.equal(ones, 'ffffffff-ffff-4fff-bfff-ffffffffffff');
  assert.match(zero, pattern);
  assert.match(ones, pattern);
});

test('실제 난수 대체 경로의 여러 UUID는 서로 다르다', () => {
  const source = { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) };
  const ids = Array.from({ length: 100 }, () => createUuid(source));
  assert.ok(ids.every((id) => pattern.test(id)));
  assert.equal(new Set(ids).size, ids.length);
});
