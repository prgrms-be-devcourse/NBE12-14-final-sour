// Mock guestbook samples keyed by school ID. Names, initials and badge colors come from the shared school list,
// so no screen copies a school's display values. Messages stay school-neutral: the same samples appear in every
// visited school's guestbook until per-school guestbook state (M3) exists. Not a server data model.
import { SCHOOL_ID, type SchoolId } from './schoolIds';
import { REAL_UNIVERSITIES } from './universitiesData';

export interface GuestbookEntry {
  id: string;
  schoolId: SchoolId;
  schoolInitial: string;
  schoolBadgeBg: string;
  schoolName: string;
  nickname: string;
  message: string;
  likeCount: number;
  relativeTime: string;
  isNew?: boolean;
  likedByMe?: boolean;
}

type Sample = Omit<GuestbookEntry, 'schoolInitial' | 'schoolBadgeBg' | 'schoolName'>;

/** Visitors' notes in a visited school's guestbook. IDs match the mock image directory (src/hooks/useSharedImages.tsx). */
const VISIT_SAMPLES: readonly Sample[] = [
  { id: 'gb-1', schoolId: SCHOOL_ID.kaist, nickname: '넙죽이', message: '캠퍼스 구경 왔어요! 클릭 열심히 하세요 💡', likeCount: 24, relativeTime: '3분 전', likedByMe: true },
  { id: 'gb-2', schoolId: SCHOOL_ID.pusan, nickname: '갈매기', message: '우리 학교도 곧 따라잡습니다 🌊', likeCount: 17, relativeTime: '12분 전' },
  { id: 'gb-3', schoolId: SCHOOL_ID.yonsei, nickname: '독수리날다', message: '캠퍼스 레벨업 축하해요 🦅', likeCount: 15, relativeTime: '25분 전', likedByMe: true },
  { id: 'gb-4', schoolId: SCHOOL_ID.korea, nickname: '호랑이포효', message: '다음 대항전에서 만나요 🐯', likeCount: 11, relativeTime: '40분 전' },
  { id: 'gb-5', schoolId: SCHOOL_ID.hanyang, nickname: '사자후', message: '서울에서 놀러 왔어요 🦁', likeCount: 9, relativeTime: '1시간 전' },
  { id: 'gb-6', schoolId: SCHOOL_ID.chonnam, nickname: '빛고을학생', message: '광주에서 원정 응원 왔어요 파이팅!', likeCount: 8, relativeTime: '2시간 전' },
  { id: 'gb-7', schoolId: SCHOOL_ID.jejunu, nickname: '한라산노루', message: '섬에서 응원 보냅니다 🏝️', likeCount: 6, relativeTime: '3시간 전' },
];

/** Other schools' notes in the player's own school guestbook, newest first. */
const OUR_SCHOOL_SAMPLES: readonly Sample[] = [
  { id: 'our-gb-1', schoolId: SCHOOL_ID.pusan, nickname: '갈매기', message: '순위 오른 거 축하! 근데 다음엔 우리가 앞설 거예요', likeCount: 8, relativeTime: '방금', isNew: true, likedByMe: true },
  { id: 'our-gb-2', schoolId: SCHOOL_ID.kaist, nickname: '랩실탈출', message: '시계탑 이쁘네요', likeCount: 21, relativeTime: '5분 전', isNew: true },
  { id: 'our-gb-3', schoolId: SCHOOL_ID.chonnam, nickname: '무등산다람쥐', message: '벚꽃 캠퍼스 부럽다', likeCount: 15, relativeTime: '1시간 전', likedByMe: true },
  { id: 'our-gb-4', schoolId: SCHOOL_ID.jejunu, nickname: '감귤보이', message: '제주에서 응원합니다 🍊', likeCount: 33, relativeTime: '어제' },
  { id: 'our-gb-5', schoolId: SCHOOL_ID.korea, nickname: '호랑이발톱', message: '다음엔 우리가 위다', likeCount: 4, relativeTime: '어제' },
];

const SCHOOLS = new Map(REAL_UNIVERSITIES.map((school) => [school.id, school]));

function withSchool(sample: Sample): GuestbookEntry {
  const school = SCHOOLS.get(sample.schoolId);
  if (!school) throw new Error(`Unknown guestbook school: ${sample.schoolId}`);
  return { ...sample, schoolName: school.name, schoolInitial: school.firstSyllable, schoolBadgeBg: school.badgeColor };
}

/** A school's own students cannot write in its guestbook, so their samples are left out. */
function exceptSchool(samples: readonly Sample[], schoolId: SchoolId): GuestbookEntry[] {
  return samples.filter((sample) => sample.schoolId !== schoolId).map(withSchool);
}

export const getVisitGuestbookSamples = (visitedSchoolId: SchoolId) => exceptSchool(VISIT_SAMPLES, visitedSchoolId);
export const getOurSchoolGuestbookSamples = (mySchoolId: SchoolId) => exceptSchool(OUR_SCHOOL_SAMPLES, mySchoolId);

/** The visit screen previews the most liked notes from the same samples as the full guestbook. */
export function getVisitGuestbookPreview(visitedSchoolId: SchoolId, count = 2): GuestbookEntry[] {
  return [...getVisitGuestbookSamples(visitedSchoolId)].sort((a, b) => b.likeCount - a.likeCount).slice(0, count);
}
