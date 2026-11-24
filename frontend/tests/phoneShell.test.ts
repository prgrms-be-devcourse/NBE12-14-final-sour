import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { test } from 'node:test';

// T1 (docs/261110-1005-mockup_remaining_tasks_plan.md §4): one phone size, owned by PhoneShell.
// A screen that writes its own frame size or status bar again drifts from the others.

const SRC = join(import.meta.dirname, '..', 'src');
const SHELL = join('components', 'PhoneShell.tsx');

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sources(path);
    return /\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

function filesMatching(pattern: RegExp): string[] {
  return sources(SRC)
    .filter((file) => pattern.test(readFileSync(file, 'utf8')))
    .map((file) => relative(SRC, file));
}

test('폰 프레임 크기는 PhoneShell에만 있고 옛 크기(845·866·758px, 400px 폭)는 어디에도 없다', () => {
  for (const old of [/h-\[845px\]/, /h-\[866px\]/, /h-\[758px\]/, /max-w-\[400px\]/, /w-\[340px\]/]) {
    assert.deepEqual(filesMatching(old), [], `${old} 프레임은 PhoneShell로 바꿔야 한다`);
  }
  assert.deepEqual(filesMatching(/h-\[844px\]/), [SHELL], '폰 크기는 PhoneShell의 PHONE_FRAME 한 곳에서만 정한다');
});

test('상태바(09:41 · LTE)와 홈 바 마크업은 PhoneShell에만 있다', () => {
  assert.deepEqual(filesMatching(/<span>LTE<\/span>/), [SHELL]);
  assert.deepEqual(filesMatching(/className="w-20 h-1 bg-\[#261608\]"/), [SHELL]);
});
