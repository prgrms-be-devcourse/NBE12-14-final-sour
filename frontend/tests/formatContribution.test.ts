import assert from 'node:assert/strict';
import { test } from 'node:test';
import { formatContribution } from '../src/utils/formatContribution';

// T5 (docs/261110-1005-mockup_remaining_tasks_plan.md §8): the stats button's contribution, M·B·T truncated to one decimal.

test('999,999까지는 쉼표만 넣고 그대로 쓴다', () => {
  assert.equal(formatContribution(0), '0');
  assert.equal(formatContribution(18560), '18,560');
  assert.equal(formatContribution(999_999), '999,999');
});

test('백만·십억·조 이상은 M·B·T로 줄이고 소수 첫째 자리를 버림한다', () => {
  assert.equal(formatContribution(1_000_000), '1M');
  assert.equal(formatContribution(1_250_000), '1.2M');
  assert.equal(formatContribution(999_999_999), '999.9M', '올림하면 1000.0M이나 1B로 실제보다 커 보인다');
  assert.equal(formatContribution(1_000_000_000), '1B');
  assert.equal(formatContribution(3_456_789_012), '3.4B');
  assert.equal(formatContribution(999_999_999_999), '999.9B');
  assert.equal(formatContribution(1_000_000_000_000), '1T');
  assert.equal(formatContribution(5_678_000_000_000), '5.6T');
  assert.equal(formatContribution(1_234_500_000_000_000), '1234.5T', 'T가 가장 큰 단위다');
});

test('소수 첫째 자리가 0이면 .0을 쓰지 않는다', () => {
  assert.equal(formatContribution(12_000_000), '12M');
  assert.equal(formatContribution(12_099_999), '12M');
  assert.equal(formatContribution(20_000_000_000), '20B');
});
