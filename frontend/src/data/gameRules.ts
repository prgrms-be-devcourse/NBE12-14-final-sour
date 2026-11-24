import { SCHOOL_ID, type SchoolId } from './schoolIds.ts';
import { MOCK_SHOP_CONFIG, type MockShopConfig } from './mockShopConfig.ts';
import { REAL_UNIVERSITIES } from './universitiesData.ts';

// 목업용 임시 수치. 실제 게임 밸런스나 서버 계약이 아니다.
export const CLICKERS = [
  { id: 1, level: 1, power: 10, upgradeCost: 300 },
  { id: 2, level: 2, power: 20, upgradeCost: 900 },
  { id: 3, level: 3, power: 30, upgradeCost: 1500 },
  { id: 4, level: 4, power: 40, upgradeCost: 3000 },
  { id: 5, level: 5, power: 50, upgradeCost: 6000 },
  { id: 6, level: 6, power: 60, upgradeCost: 12000 },
  { id: 7, level: 7, power: 70, upgradeCost: 24000 },
  { id: 8, level: 8, power: 80, upgradeCost: 48000 },
  { id: 9, level: 9, power: 90, upgradeCost: 96000 },
  { id: 10, level: 10, power: 100, upgradeCost: null },
] as const;

export interface MockStoreItem {
  id: string;
  name: string;
  description: string;
  cost: number;
  iconType: 'coffee' | 'flag' | 'shard' | 'pass';
  multiplier: number;
  bonus: number;
  durationMs: number;
  singleUse?: boolean;
  previewOnly?: boolean;
}

export function makeStoreItems(config: MockShopConfig): readonly MockStoreItem[] {
  return [{ id: 'coffee', name: '커피 한 잔', description: `${config.coffee.durationMs / 1000}초간 클릭 세기 ${config.coffee.multiplier}배`, cost: config.coffee.price, iconType: 'coffee', multiplier: config.coffee.multiplier, bonus: 0, durationMs: config.coffee.durationMs }];
}
export const STORE_ITEMS = makeStoreItems(MOCK_SHOP_CONFIG);

export const CAMPUS_STAGES = [
  { level: 1, requiredScore: 0, name: '풀밭 부지와 텐트', description: '작은 나무 푯말과 천막에서 시작하는 대학의 첫걸음.', features: ['빈 풀밭 부지', '나무 푯말', '임시 본부 텐트'] },
  { level: 2, requiredScore: 50000, name: '작은 벽돌 교사와 묘목', description: '붉은 벽돌 교사와 어린 묘목으로 기반을 다집니다.', features: ['단층 벽돌 건물', '흙길', '어린 묘목'] },
  { level: 3, requiredScore: 250000, name: '확장된 벽돌 교사와 숲길', description: '강의동이 확장되고 캠퍼스 숲길이 자랍니다.', features: ['확장된 교사', '다져진 흙길', '자라나는 나무'] },
  { level: 4, requiredScore: 1000000, name: '시계탑 본관과 작은 연못', description: '시계탑 본관과 돌길, 자연 연못이 조성됩니다.', features: ['본관 시계탑', '돌 보도블록', '자연 연못'] },
  { level: 5, requiredScore: 2000000, name: '도서관과 벚꽃 정원', description: '중앙도서관과 벚꽃 정원이 학생들을 맞이합니다.', features: ['중앙도서관', '벚꽃 정원', '벤치'] },
  { level: 6, requiredScore: 3500000, name: '제2강의동과 가로등', description: '새 강의동과 가로등으로 캠퍼스가 활기를 띱니다.', features: ['제2강의동', '빈티지 가로등', '화단'] },
  { level: 7, requiredScore: 5500000, name: '경기장과 가로수길', description: '종합 경기장과 벚꽃 가로수길이 들어섭니다.', features: ['종합 경기장', '가로수길', '캠퍼스 광장'] },
  { level: 8, requiredScore: 8000000, name: '분수광장과 스포츠 필드', description: '분수광장과 야외 스포츠 코트가 완성됩니다.', features: ['분수광장', '스포츠 코트', '학교 배너'] },
  { level: 9, requiredScore: 12000000, name: '기숙사 타운과 숲', description: '기숙사 타운과 울창한 숲으로 캠퍼스가 확장됩니다.', features: ['학생 기숙사', '녹음 숲', '학생 인파'] },
  { level: 10, requiredScore: 20000000, name: '트윈 시계탑과 대축제', description: '트윈 시계탑과 황금빛 축제로 성장을 기념합니다.', features: ['트윈 시계탑', '축제 광장', '불꽃놀이'] },
] as const;

export const INITIAL_SCHOOL_SCORE = 1333872;
export const INITIAL_DEPARTMENT_SCORE = 582400;
export const INITIAL_CONTRIBUTION = 18560;
/**
 * School of the default player. Since M4 the live "my school" is `player.schoolId`, set by the mock email
 * verification (docs/261108-2020-m4_onboarding_auth_plan.md 4-3); this is only the starting value.
 */
export const MY_SCHOOL_ID: SchoolId = SCHOOL_ID.hanyang;

/**
 * Starting school score when a player starts over at a school (M4 D2). The default school keeps the existing
 * mock value; other schools start from their shared mock score, so the ranking does not jump on enrolment.
 * A school outside the static list (the hidden operator school, #144) starts from 0 until the server score arrives.
 */
export function initialSchoolScore(schoolId: SchoolId): number {
  if (schoolId === MY_SCHOOL_ID) return INITIAL_SCHOOL_SCORE;
  const school = REAL_UNIVERSITIES.find((entry) => entry.id === schoolId);
  return school ? school.score : 0;
}

export interface MockPlayerInfo {
  userId: string;
  schoolId: SchoolId;
  departmentId: string;
  nickname: string;
  department: string;
  email: string;
  /** School name from the server (API mode). Used when the school is outside the static list (#144). */
  schoolName?: string;
}

export interface MockDepartment {
  id: string;
  name: string;
  schoolId: SchoolId;
  /** Mock department score shown in the in-school ranking when this is not the player's department. */
  seedScore: number;
}

// Mock sample departments, one ID per name (M4 4-3). The KAIST and Pusan lists are samples for the three
// mock email domains, not verified course catalogues.
export const KNOWN_DEPARTMENTS: readonly MockDepartment[] = [
  { id: 'my-department', name: '컴퓨터소프트웨어학부', schoolId: SCHOOL_ID.hanyang, seedScore: 540000 },
  { id: 'business', name: '경영학과', schoolId: SCHOOL_ID.hanyang, seedScore: 512900 },
  { id: 'design', name: '디자인학과', schoolId: SCHOOL_ID.hanyang, seedScore: 489200 },
  { id: 'electronics', name: '전자공학과', schoolId: SCHOOL_ID.hanyang, seedScore: 421000 },
  { id: 'mechanical', name: '기계공학과', schoolId: SCHOOL_ID.hanyang, seedScore: 389400 },
  { id: 'biology', name: '생명과학과', schoolId: SCHOOL_ID.hanyang, seedScore: 361200 },
  { id: 'ai', name: '인공지능학과', schoolId: SCHOOL_ID.hanyang, seedScore: 342100 },
  { id: 'media', name: '미디어학부', schoolId: SCHOOL_ID.hanyang, seedScore: 298000 },
  { id: 'architecture', name: '건축학과', schoolId: SCHOOL_ID.hanyang, seedScore: 298000 },
  { id: 'kaist-computing', name: '전산학부', schoolId: SCHOOL_ID.kaist, seedScore: 498000 },
  { id: 'kaist-ee', name: '전기및전자공학부', schoolId: SCHOOL_ID.kaist, seedScore: 452000 },
  { id: 'kaist-me', name: '기계공학과', schoolId: SCHOOL_ID.kaist, seedScore: 401000 },
  { id: 'kaist-id', name: '산업디자인학과', schoolId: SCHOOL_ID.kaist, seedScore: 356000 },
  { id: 'kaist-bio', name: '생명과학과', schoolId: SCHOOL_ID.kaist, seedScore: 300000 },
  { id: 'pusan-cse', name: '정보컴퓨터공학부', schoolId: SCHOOL_ID.pusan, seedScore: 476000 },
  { id: 'pusan-business', name: '경영학과', schoolId: SCHOOL_ID.pusan, seedScore: 433000 },
  { id: 'pusan-me', name: '기계공학부', schoolId: SCHOOL_ID.pusan, seedScore: 398000 },
  { id: 'pusan-ocean', name: '해양학과', schoolId: SCHOOL_ID.pusan, seedScore: 342000 },
  { id: 'pusan-korean', name: '국어국문학과', schoolId: SCHOOL_ID.pusan, seedScore: 287000 },
] as const;

export function getSchoolDepartments(schoolId: SchoolId): MockDepartment[] {
  return KNOWN_DEPARTMENTS.filter((department) => department.schoolId === schoolId);
}

export function resolveDepartmentId(departmentName: string, schoolId = MY_SCHOOL_ID): string {
  const match = KNOWN_DEPARTMENTS.find((d) => d.schoolId === schoolId && (d.name === departmentName || d.id === departmentName));
  return match ? match.id : 'my-department';
}

export const DEFAULT_PLAYER: MockPlayerInfo = {
  userId: 'me',
  schoolId: MY_SCHOOL_ID,
  departmentId: 'my-department',
  email: 'student@hanyang.ac.kr',
  nickname: '열정학번사자',
  department: '컴퓨터소프트웨어학부',
};
