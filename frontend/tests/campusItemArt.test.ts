import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { test } from 'node:test';
import { clickerArt, drinkArt } from '../src/data/campusItemArt.ts';

const folder = new URL('../src/assets/campus-items/', import.meta.url);
const seed = readFileSync(new URL('../../src/main/resources/db/migration/V16__seed_clicker_levels_and_potions.sql', import.meta.url), 'utf8');
const contributionDrink = { type: 'MULTIPLIER', payType: 'CONTRIBUTION' } as const;
const expectedDrinks = { '아메리카노': 'iced-americano.svg', '에너지 드링크': 'energy-can.svg', '밤샘 각성제': 'moon-vial.svg' };

function filename(url: string | undefined) {
  assert.ok(url !== undefined && url.startsWith(folder.href), '캠퍼스 픽셀 에셋은 번들에 포함된 로컬 파일을 참조하세요.');
  return url.slice(folder.href.length);
}

test('서버 레벨 1~10은 별도 클리커 파일을 가리키고 그 외의 값은 그림을 추측하지 않는다', () => {
  const levels = [1, ...[...seed.matchAll(/^[ \t]+\((\d+),[ \t]*\d+,[ \t]*\d+\)/gm)].map((match) => Number(match[1]))];
  assert.deepEqual(levels, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  for (const level of levels) assert.equal(filename(clickerArt(level)), `clicker-${String(level).padStart(2, '0')}.svg`);
  for (const level of [null, 0, 11, -1, 2.5, Infinity, NaN]) assert.equal(clickerArt(level), undefined);
});

test('V16 음료 이름과 판매 유형으로만 그림을 매핑하고 모르는 상품은 공용 아이콘을 사용한다', () => {
  const names = [...seed.matchAll(/\('([^']+)',\s*'[^']+',\s*'MULTIPLIER',\s*'CONTRIBUTION'/g)].map((match) => match[1]);
  assert.deepEqual(names, Object.keys(expectedDrinks));
  for (const [name, file] of Object.entries(expectedDrinks)) {
    assert.equal(filename(drinkArt({ name, ...contributionDrink })), file);
    assert.equal(drinkArt({ name, ...contributionDrink, payType: 'CREDIT' }), undefined);
    assert.equal(drinkArt({ name, ...contributionDrink, type: 'LOGO_PROFILE' }), undefined);
  }
  for (const name of ['서버 새 음료', 'constructor', '__proto__']) assert.equal(drinkArt({ name, ...contributionDrink }), undefined);
});

test('최종 13종 모두 출처에 기록되고 클리커의 테두리 실루엣은 서로 다르다', () => {
  const files = readdirSync(folder).filter((file) => file.endsWith('.svg')).sort();
  const clickers = Array.from({ length: 10 }, (_, index) => filename(clickerArt(index + 1)));
  assert.deepEqual(files, [...clickers, ...Object.values(expectedDrinks)].sort());
  const credits = readFileSync(new URL('261128-2217-asset_credits.md', folder), 'utf8');
  const outlines = new Set<string>();
  for (const file of files) {
    const svg = readFileSync(new URL(file, folder), 'utf8');
    assert.ok(credits.includes(`\`${file}\``), `${file}의 출처와 매핑을 에셋 출처 문서에 적으세요.`);
    assert.match(svg, /viewBox="0 0 24 24" shape-rendering="crispEdges"/, file);
    const outline = svg.match(/<path fill="#261608" d="([^"]+)"/);
    assert.ok(outline, `${file}: 양피지 위에서 보이는 공통 외곽선을 사용하세요.`);
    outlines.add(outline[1]);
  }
  assert.equal(outlines.size, files.length, '새 에셋은 기존 실루엣을 단순 재색칠하지 마세요.');
});
