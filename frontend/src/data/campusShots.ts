// Growth-stage screenshots: one real in-game campus scene per level, captured by
// `npm run campus:shots` (scripts/campus-shots/capture.ts). They replace the 2026-11-06 growth sheet
// in the growth-stage viewer and the fallback sprite; the sheet file stays in the repo (user
// instruction 2026-11-10). Plan: docs/261110-0747-campus_level_shots_plan.md.
import lv01 from '../assets/campus-shots/lv01.webp';
import lv02 from '../assets/campus-shots/lv02.webp';
import lv03 from '../assets/campus-shots/lv03.webp';
import lv04 from '../assets/campus-shots/lv04.webp';
import lv05 from '../assets/campus-shots/lv05.webp';
import lv06 from '../assets/campus-shots/lv06.webp';
import lv07 from '../assets/campus-shots/lv07.webp';
import lv08 from '../assets/campus-shots/lv08.webp';
import lv09 from '../assets/campus-shots/lv09.webp';
import lv10 from '../assets/campus-shots/lv10.webp';

/** Screenshot URL per level, index 0 = Lv.1. */
export const CAMPUS_SHOTS: readonly string[] = [lv01, lv02, lv03, lv04, lv05, lv06, lv07, lv08, lv09, lv10];

/** Screenshot URL for a level, clamped to Lv.1-10. */
export const campusShotFor = (level: number): string => CAMPUS_SHOTS[Math.max(0, Math.min(9, Math.floor(level) - 1))];
