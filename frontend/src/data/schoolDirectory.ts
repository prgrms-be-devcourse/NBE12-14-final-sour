// One lookup for school display values: map, rankings and (from M2) visits read the same rows by school ID.
// Plan: docs/261108-1930-m1_school_map_data_plan.md 4-1. Scores are mock values; my school uses the game state.
import { SCHOOL_ID, type SchoolId } from './schoolIds.ts';
import { INITIAL_SCHOOL_SCORE, MY_SCHOOL_ID } from './gameRules.ts';
import { getSchoolRankings } from './mockRankings.ts';
import { REAL_UNIVERSITIES, type UniversityItem } from './universitiesData.ts';
import { schoolLogoStatus, type LogoStatus } from './schoolLogos.ts';
import type { MapRegion, SchoolRegion } from './schoolMapLayout.ts';
import { getCampusStage } from '../utils/gameModel.ts';

/**
 * School shown by the 2-Phone visiting comparison demo (decision D4, 2026-11-08). It replaced the fictional
 * 'blue-sea' school so the demo reads the same rows as the map. Plan: docs/261108-2000-m2_map_visit_return_plan.md.
 */
export const DEMO_VISIT_SCHOOL_ID: SchoolId = SCHOOL_ID.pusan;

export interface SchoolView extends Omit<UniversityItem, 'score' | 'region' | 'filterRegion'> {
  /** '기타' only for my school outside the static list (offListSchoolView, #144). Rows of getSchoolViews never have it. */
  region: UniversityItem['region'] | '기타';
  filterRegion: UniversityItem['filterRegion'] | '기타';
  score: number;
  /** National rank from the shared ranking rule (score desc, then name, then ID). */
  rank: number;
  /** Rank change from the shared ranking rows, e.g. "▲2", "▼1" or "-" (HUD badge, visit screen V2). */
  rankChange: string;
  campusLevel: number;
  isMySchool: boolean;
  isTop3: boolean;
  logoStatus: LogoStatus;
  /** The server's logo (API mode, SCRUM-162). Shown before the bundled logo; absent in the mock. */
  logoUrl?: string | null;
}

const BASE_BY_ID = new Map(REAL_UNIVERSITIES.map((school) => [school.id, school]));

/** All schools ordered by national rank. Reuses getSchoolRankings so the ranking screens agree. */
export function getSchoolViews(schoolScore = INITIAL_SCHOOL_SCORE, mySchoolId = MY_SCHOOL_ID): SchoolView[] {
  return getSchoolRankings(schoolScore, mySchoolId).map((row) => {
    const base = BASE_BY_ID.get(row.id);
    if (!base) throw new Error(`Ranking row without school data: ${row.id}`);
    return {
      ...base,
      score: row.score,
      rank: row.rank,
      rankChange: row.change,
      campusLevel: getCampusStage(row.score).level,
      isMySchool: !!row.isMySchool,
      isTop3: row.rank <= 3,
      logoStatus: schoolLogoStatus(row.id),
    };
  });
}

/**
 * My school when it is outside the static list: the hidden operator school (와플대학교, #144) is kept out of the map,
 * rankings and search on purpose, so it never joins getSchoolViews. Name and score come from the server and game
 * state; it has no region and no national rank (0).
 */
export function offListSchoolView(schoolId: SchoolId, name: string, score: number, isMySchool = true): SchoolView {
  return {
    id: schoolId, name, shortName: name, firstSyllable: name.slice(0, 1), badgeColor: '#6b7280',
    region: '기타', filterRegion: '기타', city: '비공개 학교',
    score, rank: 0, rankChange: '-', campusLevel: getCampusStage(score).level,
    isMySchool, isTop3: false, logoStatus: schoolLogoStatus(schoolId),
  };
}

/** False for a school outside the static list, such as the hidden operator school (#144). */
export function isListedSchool(schoolId: SchoolId): boolean {
  return BASE_BY_ID.has(schoolId);
}

export function getSchoolById(views: readonly SchoolView[], schoolId: SchoolId): SchoolView | undefined {
  return views.find((school) => school.id === schoolId);
}

export function getTopSchools(views: readonly SchoolView[], count = 3): SchoolView[] {
  return [...views].sort((a, b) => a.rank - b.rank).slice(0, count);
}

export function countByRegion(views: readonly SchoolView[]): Map<SchoolRegion, number> {
  const counts = new Map<SchoolRegion, number>();
  for (const school of views) {
    if (school.region === '기타') continue; // Not on the map (offListSchoolView).
    counts.set(school.region, (counts.get(school.region) ?? 0) + 1);
  }
  return counts;
}

export function normalizeQuery(query: string): string {
  return query.trim().toLowerCase();
}

/** Region filter first, then search by name, short name, city or first syllable. Keeps rank order. */
export function filterSchools(views: readonly SchoolView[], region: MapRegion, query: string): SchoolView[] {
  const q = normalizeQuery(query);
  return views.filter((school) => {
    if (region !== '전체' && school.filterRegion !== region) return false;
    if (!q) return true;
    return [school.name, school.shortName, school.city, school.firstSyllable].some((field) => field.toLowerCase().includes(q));
  });
}
