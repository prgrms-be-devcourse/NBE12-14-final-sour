// Bundled school logo URLs by backend school ID (schoolIds.ts). Plan: docs/261108-1930-m1_school_map_data_plan.md 4-3.
// No logo asset has been secured yet, so every school shows the first-syllable badge as a temporary
// stand-in (decision D1, 2026-11-08). That badge does NOT satisfy plan 4-1 #7 ("모든 학교의 로고 출력").

import type { SchoolId } from './schoolIds';

export const SCHOOL_LOGO_ASSETS: Readonly<Record<SchoolId, string>> = {};

export type LogoStatus = 'asset' | 'placeholder';

export function schoolLogoStatus(schoolId: SchoolId): LogoStatus {
  return SCHOOL_LOGO_ASSETS[schoolId] ? 'asset' : 'placeholder';
}
