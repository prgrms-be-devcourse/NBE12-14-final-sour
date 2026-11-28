// Sound-effect clip URLs. `new URL(literal, import.meta.url)` lets Vite emit each file as a hashed asset, while plain
// Node (the test runner) only builds a file:// URL and never loads the MP3. Sources: src/assets/sfx/CREDITS.md.
// tests/sfxAssets.test.ts checks that this list and the folder hold the same files.

/** Campus tap: one is picked at random per tap. */
export const BUBBLE_POP_CLIPS: readonly string[] = [
  new URL('../assets/sfx/bubblepop/dragon-studio-pop-402322.mp3', import.meta.url).href,
  new URL('../assets/sfx/bubblepop/dragon-studio-pop-402323.mp3', import.meta.url).href,
  new URL('../assets/sfx/bubblepop/freesound_community-pop-39222.mp3', import.meta.url).href,
  new URL('../assets/sfx/bubblepop/linhmitto-bubblepop-254773.mp3', import.meta.url).href,
  new URL('../assets/sfx/bubblepop/u_edtmwfwu7c-pop-331049.mp3', import.meta.url).href,
  new URL('../assets/sfx/bubblepop/universfield-bubble-pop-04-323580.mp3', import.meta.url).href,
  new URL('../assets/sfx/bubblepop/universfield-bubble-pop-06-351337.mp3', import.meta.url).href,
];

/** Attack tap on another school. */
export const ATTACK_PUNCH_CLIPS: readonly string[] = [
  new URL('../assets/sfx/punch/freesound_community-punch-2-37333.mp3', import.meta.url).href,
];

/** Critical attack tap: plays instead of the normal punch. */
export const ATTACK_CRIT_PUNCH_CLIPS: readonly string[] = [
  new URL('../assets/sfx/punch/universfield-punch-02-123106.mp3', import.meta.url).href,
];
