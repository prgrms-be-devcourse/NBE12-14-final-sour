import { campusShotFor } from '../data/campusShots';
import { CAMPUS_STAGES } from '../data/gameRules';

// Fallback for levels without a composed layout (e.g. above Lv.10): the level's in-game screenshot
// instead of a crop of the 2026-11-06 growth sheet (docs/261110-0747-campus_level_shots_plan.md 5-2).
// The sheet file stays in src/assets/images as an art reference (user instruction 2026-11-10).
export function CampusSprite({ level, className = '' }: { level: number; className?: string; variant?: 'day' | 'night' }) {
  const index = Math.max(0, Math.min(9, level - 1));
  return (
    <img
      src={campusShotFor(index + 1)}
      alt={`캠퍼스 Lv.${index + 1} ${CAMPUS_STAGES[index].name}`}
      className={`object-cover object-top ${className}`}
    />
  );
}
