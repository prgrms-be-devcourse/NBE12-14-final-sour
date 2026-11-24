import { CampusAmbienceProvider } from '../hooks/useCampusTimePhase';
import { CAMPUS_SHOT_SIZE } from '../data/campusGeometry';
import { CampusScene } from './CampusScene';

/**
 * Capture frame for the growth-stage screenshots (scripts/campus-shots/capture.ts): one campus scene
 * by day, without in-game labels, guestbook, tap hint or chat. Dev/test builds only (`?campusShot=<level>`,
 * src/main.tsx). Plan: docs/261110-0747-campus_level_shots_plan.md (5-1).
 */
export function CampusShot({ level }: { level: number }) {
  return (
    <CampusAmbienceProvider initialOverride="day">
      <div data-campus-shot={level} style={{ width: CAMPUS_SHOT_SIZE.width, height: CAMPUS_SHOT_SIZE.height }}>
        <CampusScene level={level} className="block w-full h-full" />
      </div>
    </CampusAmbienceProvider>
  );
}
