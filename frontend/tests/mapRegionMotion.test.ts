import assert from 'node:assert/strict';
import { test } from 'node:test';
import { MAP_REGIONS, type MapRegion } from '../src/data/schoolMapLayout';
import { mapRegionMotion } from '../src/utils/mapRegionMotion';

// Signs on the national map: west/east for x, north/south for y. Column order matches `regions`.
const regions = ['전체', '수도권', '강원', '충청', '영남', '호남', '제주'] as const;
const expected: Record<MapRegion, string[]> = {
  전체: ['—', '--', '+-', '--', '++', '-+', '-+'],
  수도권: ['++', '—', '+-', '++', '++', '++', '-+'],
  강원: ['-+', '-+', '—', '-+', '++', '-+', '-+'],
  충청: ['++', '--', '+-', '—', '++', '-+', '-+'],
  영남: ['--', '--', '--', '--', '—', '-+', '-+'],
  호남: ['+-', '--', '+-', '+-', '+-', '—', '-+'],
  제주: ['+-', '+-', '+-', '+-', '+-', '+-', '—'],
};
const sign = (value: number) => value === 0 ? '0' : value < 0 ? '-' : '+';

test('방향 검증표는 전체와 현행 여섯 권역을 포함한다', () => assert.deepEqual(regions, MAP_REGIONS));

for (const from of regions) {
  for (const [index, to] of regions.entries()) {
    test(`지도 전환 ${from} → ${to}`, () => {
      const motion = mapRegionMotion(from, to);
      if (from === to) return assert.equal(motion, undefined);
      assert.ok(motion);
      assert.equal(sign(motion.x) + sign(motion.y), expected[from][index]);
      if (from === '전체') assert.ok(motion.scale < 1, 'enter zooms in');
      else if (to === '전체') assert.ok(motion.scale > 1, 'return zooms out');
      else assert.equal(motion.scale, 1, 'between regions pans without scaling');
      const reverse = mapRegionMotion(to, from)!;
      assert.ok(Math.abs(motion.x + reverse.x) < 1e-9);
      assert.ok(Math.abs(motion.y + reverse.y) < 1e-9);
    });
  }
}
