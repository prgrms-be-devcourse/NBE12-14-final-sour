import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { extractCodeDigits } from '../src/utils/codeDigits';

test('인증 코드 추출: 독립된 6자리 우선, 없으면 숫자를 모은다', () => {
  const cases: Array<[string, string]> = [
    ['123456', '123456'], ['인증 코드: 123456', '123456'],
    ['앞 12 뒤 345', '12345'], ['숫자 없음', ''],
    ['1234567', '1234567'], ['전화 12 코드 456789 끝', '456789'],
    ['123456 다음', '123456'], ['코드1234567', '1234567'],
  ];
  for (const [input, expected] of cases) assert.equal(extractCodeDigits(input), expected, input);
});

test('프론트 소스에서 정규식 lookbehind를 사용하지 않는다', () => {
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (entry.isFile() && /\.tsx?$/.test(entry.name)) {
        const source = readFileSync(path, 'utf8');
        assert.ok(!source.includes('(?<=') && !source.includes('(?<!'), `${path}: lookbehind 대신 (?:^|\\D) 비캡처 그룹과 캡처 그룹을 사용하세요`);
      }
    }
  };
  walk(new URL('../src', import.meta.url).pathname);
});
