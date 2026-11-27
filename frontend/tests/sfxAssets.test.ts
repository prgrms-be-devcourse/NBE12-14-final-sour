// Checks the committed sound-effect clips against src/assets/sfx/CREDITS.md.
// Every clip must be a processed file (scripts/sfx/normalize-sfx.sh), never a raw download, and must name its source.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BUBBLE_POP_CLIPS } from '../src/data/sfxClips';

const SFX_DIR = join(import.meta.dirname, '..', 'src', 'assets', 'sfx');
/** A processed clip is mono 96 kbps and at most 0.6 s, about 8 KB. Raw downloads in use were 23–34 KB. */
const MAX_CLIP_BYTES = 12 * 1024;

function clipPaths(): string[] {
  return readdirSync(SFX_DIR, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .flatMap((dir) => readdirSync(join(SFX_DIR, dir.name))
      .filter((file) => file.endsWith('.mp3'))
      .map((file) => `${dir.name}/${file}`))
    .sort();
}

/** `folder/file.mp3` for every table row under a `## folder/` heading in CREDITS.md. */
function creditedPaths(): string[] {
  const credited: string[] = [];
  let folder = '';
  for (const line of readFileSync(join(SFX_DIR, 'CREDITS.md'), 'utf8').split('\n')) {
    const heading = /^## (\S+)\//.exec(line);
    if (heading) folder = heading[1];
    const row = /^\| (\S+\.mp3) \|/.exec(line);
    if (row && folder) credited.push(`${folder}/${row[1]}`);
  }
  return credited.sort();
}

test('the campus tap has bubble-pop clips to choose from', () => {
  assert.ok(clipPaths().filter((path) => path.startsWith('bubblepop/')).length >= 2);
});

test('every sound-effect clip has exactly one row in CREDITS.md, and every row has a clip', () => {
  assert.deepEqual(creditedPaths(), clipPaths(),
    'Add a row (file, title, contributor, source URL) under the clip folder heading in src/assets/sfx/CREDITS.md.');
});

test('every sound-effect clip is a processed file, not a raw download', () => {
  for (const path of clipPaths()) {
    const bytes = statSync(join(SFX_DIR, path)).size;
    assert.ok(bytes > 0 && bytes <= MAX_CLIP_BYTES,
      `${path} is ${bytes} bytes; run scripts/sfx/normalize-sfx.sh on the raw download and commit its output.`);
  }
});

test('BUBBLE_POP_CLIPS lists exactly the files in src/assets/sfx/bubblepop', () => {
  const listed = BUBBLE_POP_CLIPS.map((url) => fileURLToPath(url).split(/[\\/]src[\\/]assets[\\/]sfx[\\/]/)[1].replace(/\\/g, '/'));
  assert.deepEqual([...listed].sort(), clipPaths().filter((path) => path.startsWith('bubblepop/')),
    'Add or remove the clip in src/data/sfxClips.ts to match the folder.');
});
