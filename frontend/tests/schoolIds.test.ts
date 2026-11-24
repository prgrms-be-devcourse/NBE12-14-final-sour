import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { FICTIONAL_SCHOOL_ID, SCHOOL_ID } from '../src/data/schoolIds.ts';
import { REAL_UNIVERSITIES } from '../src/data/universitiesData.ts';

// School IDs are the backend university.id (SCRUM-149). The university table is filled only by V2, and its IDENTITY
// column numbers the rows in insertion order, so the Nth name in V2 has ID N. These checks fail when the seed or
// the frontend table changes without the other.

const SEED_URL = new URL('../../src/main/resources/db/migration/V2__seed_universities.sql', import.meta.url);

function seedNames(): string[] {
  const sql = readFileSync(SEED_URL, 'utf8');
  const insert = sql.slice(sql.indexOf('INSERT INTO university (name) VALUES'));
  return [...insert.matchAll(/^\s*\('([^']+)'\)[,;]/gm)].map((match) => match[1]);
}

test('SCHOOL_ID는 V2 시드의 삽입 순서(IDENTITY)와 같다', () => {
  const names = seedNames();
  assert.equal(names.length, 76, `V2 시드 파싱 결과가 예상과 다름: ${names.length}`);
  assert.deepEqual(Object.values(SCHOOL_ID), names.map((_, index) => index + 1),
    'SCHOOL_ID는 1부터 시드 순서대로 빠짐없이 매겨야 한다');
});

test('프론트 학교 목록은 V2 시드와 같은 학교를 같은 ID·이름으로 갖는다', () => {
  const names = seedNames();
  const byId = new Map(REAL_UNIVERSITIES.map((school) => [school.id, school.name]));
  assert.equal(byId.size, REAL_UNIVERSITIES.length, '학교 ID가 중복된다');
  const mismatches = names.flatMap((name, index) => {
    const id = index + 1;
    const mine = byId.get(id);
    return mine === name ? [] : [`${id}: 시드 ${name} / 프론트 ${mine ?? '(없음)'}`];
  });
  assert.deepEqual(mismatches, []);
  assert.equal(REAL_UNIVERSITIES.length, names.length, '시드에 없는 학교가 프론트 목록에 있다');
});

test('가상 학교 ID는 백엔드 ID와 겹칠 수 없다', () => {
  assert.ok(FICTIONAL_SCHOOL_ID < 1, 'IDENTITY는 1부터 시작하므로 가상 학교는 1 미만이어야 한다');
});
