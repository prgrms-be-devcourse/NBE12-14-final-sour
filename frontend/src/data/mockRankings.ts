import { SCHOOL_ID, type SchoolId } from './schoolIds.ts';
import { DEFAULT_PLAYER, INITIAL_CONTRIBUTION, INITIAL_DEPARTMENT_SCORE, INITIAL_SCHOOL_SCORE, MY_SCHOOL_ID, getSchoolDepartments, initialSchoolScore } from './gameRules.ts';
import { REAL_UNIVERSITIES } from './universitiesData.ts';
import type { MockGameState } from '../utils/gameModel.ts';

/** School rows carry the backend university ID; department and personal rows keep mock string IDs. */
export interface RankingRow<Id extends string | number = string | number> {
  id: Id;
  name: string;
  score: number;
  rank: number;
  change: string;
  region: string;
  firstSyllable: string;
  badgeColor: string;
  mascot: string;
  crestColor: string;
  crestSymbol: string;
  isMySchool?: boolean;
}

type RankInput<Id extends string | number> = Omit<RankingRow<Id>, 'rank' | 'change' | 'mascot' | 'crestColor' | 'crestSymbol'>;

function compareIds(a: string | number, b: string | number): number {
  return typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b));
}

// 동점은 이름, 동일 이름은 ID로 정렬한다. 순위 번호는 연속 번호의 목업 임시 규칙.
export function rankEntries<T extends { id: string | number; name: string; score: number }>(entries: readonly T[]) {
  return [...entries]
    .sort((a, b) => b.score - a.score || a.name.localeCompare(b.name, 'ko') || compareIds(a.id, b.id))
    .map((entry, index) => ({ ...entry, rank: index + 1 }));
}

function present<Id extends string | number>(entries: RankInput<Id>[], previous = entries): RankingRow<Id>[] {
  const previousRanks = new Map(rankEntries(previous).map((entry) => [entry.id, entry.rank]));
  return rankEntries(entries).map((entry) => {
    const delta = (previousRanks.get(entry.id) ?? entry.rank) - entry.rank;
    let change = '-';
    if (delta > 0) change = `▲${delta}`;
    else if (delta < 0) change = `▼${-delta}`;
    return {
      ...entry,
      change,
      mascot: entry.region, crestColor: entry.badgeColor, crestSymbol: entry.firstSyllable,
    };
  });
}

/**
 * National ranking. `mySchoolId` is the player's school (M4: set by the mock email verification); its score comes
 * from the game state, every other school keeps its shared mock score.
 */
export function getSchoolRankings(schoolScore = INITIAL_SCHOOL_SCORE, mySchoolId = MY_SCHOOL_ID): RankingRow<SchoolId>[] {
  const entries = REAL_UNIVERSITIES.map((school) => ({
    id: school.id, name: school.name, score: school.id === mySchoolId ? schoolScore : school.score,
    region: `${school.region} · ${school.city}`, firstSyllable: school.firstSyllable,
    badgeColor: school.badgeColor, isMySchool: school.id === mySchoolId,
  }));
  return present(entries, entries.map((entry) => entry.isMySchool ? { ...entry, score: initialSchoolScore(mySchoolId) } : entry));
}

export function getSchoolName(schoolId: SchoolId): string {
  const school = REAL_UNIVERSITIES.find((entry) => entry.id === schoolId);
  if (!school) throw new Error(`Unknown school: ${schoolId}`);
  return school.name;
}

// Sample schoolmates for the in-school personal ranking. They belong to whichever school the player is in.
// At the default school they keep their original departments; elsewhere they cycle through that school's list.
const SAMPLE_STUDENTS = [
  { id: 'night', name: '도서관밤샘러', department: '컴퓨터소프트웨어학부', score: 48920 },
  { id: 'star', name: '은하수별빛', department: '전자공학과', score: 42150 },
  { id: 'tiger', name: '청람호랑이99', department: '경영학과', score: 39800 },
  { id: 'lunch', name: '학식돈까스러버', department: '생명과학과', score: 34120 },
  { id: 'clock', name: '시계탑파수꾼', department: '디자인학과', score: 31200 },
  { id: 'deer', name: '온새미사슴', department: '인공지능학과', score: 28900 },
  { id: 'dog', name: '다온백구발자국', department: '건축학과', score: 7950 },
  { id: 'bird', name: '누리뱁새날개', department: '미디어학부', score: 7950 },
] as const;
/** A student of another school. It must never show up in the in-school ranking. */
const OTHER_SCHOOL_STUDENT = { id: 'visitor', schoolId: SCHOOL_ID.snu, name: '외부학교샘플', department: '자유전공학부', score: 999999 };

export function getMockRankings(state: MockGameState, player = DEFAULT_PLAYER) {
  const mySchoolId = player.schoolId || MY_SCHOOL_ID;
  const schoolName = getSchoolName(mySchoolId);
  const myDeptId = player.departmentId || 'my-department';
  const schoolDepartments = getSchoolDepartments(mySchoolId);
  const departmentSeeds = [
    { id: myDeptId, name: player.department, score: INITIAL_DEPARTMENT_SCORE, isMySchool: true },
    ...schoolDepartments.filter((entry) => entry.id !== myDeptId)
      .map((entry) => ({ id: entry.id, name: entry.name, score: entry.seedScore, isMySchool: false })),
  ].map((entry) => ({ ...entry, region: schoolName, firstSyllable: entry.name[0], badgeColor: '#2563eb' }));

  // 학교/학과 표시 점수는 클릭으로 증가하되 구매로 차감하지 않는다. 소비 대상은 User뿐이다.
  const departments = present(departmentSeeds.map((entry) => entry.isMySchool ? { ...entry, score: state.departmentScore } : entry), departmentSeeds);
  const classmates = SAMPLE_STUDENTS.map((entry, index) => ({
    ...entry,
    schoolId: mySchoolId,
    department: mySchoolId === MY_SCHOOL_ID || schoolDepartments.length === 0
      ? entry.department
      : schoolDepartments[index % schoolDepartments.length].name,
  }));
  const students = [
    ...classmates,
    { id: player.userId || 'me', schoolId: mySchoolId, name: player.nickname, department: player.department, score: INITIAL_CONTRIBUTION, isMySchool: true },
    OTHER_SCHOOL_STUDENT,
  ].filter((entry) => entry.schoolId === mySchoolId)
    .map((entry) => ({ ...entry, region: `${schoolName} · ${entry.department}`, firstSyllable: entry.name[0], badgeColor: '#0284c7' }));
  const personal = present(students.map((entry) => 'isMySchool' in entry && entry.isMySchool ? { ...entry, score: state.contribution } : entry), students);
  return { school: getSchoolRankings(state.schoolScore, mySchoolId), dept: departments, personal };
}
