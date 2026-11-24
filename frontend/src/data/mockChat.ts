// Mock chat samples built from the shared school list, rankings and the player's alliance, so no message names a
// fixed school or rank (T44-D1, T44-D2). Plan: docs/261109-1829-chat_samples_plan.md. Not a server data model:
// the real service loads chat history over WebSocket (planning doc 6장 "채팅").
import type { SchoolId } from './schoolIds.ts';
import { getSchoolDepartments } from './gameRules.ts';
import type { SchoolView } from './schoolDirectory.ts';

export interface ChatMessage {
  id: string;
  schoolInitial: string;
  schoolBadgeBg: string;
  nickname: string;
  department: string;
  text: string;
  time: string;
  isMe?: boolean;
  isFiltered?: boolean;
}

export interface ChatSampleInput {
  /** All schools in national rank order (useSchoolViews). */
  schools: readonly SchoolView[];
  mySchoolId: SchoolId;
  myNickname: string;
  myDepartment: string;
}

/** The player's own messages keep the yellow highlight badge (existing design). */
export const MY_MESSAGE_BADGE = '#eab308';
const FILTERED_BADGE = '#78716c';
/** Used when a school has no mock department list (only hanyang, kaist and pusan have one). */
const GENERIC_DEPARTMENTS = ['기계공학과', '경영학과', '전자공학과', '신소재공학과', '화학공학과'] as const;

function schoolOf(schools: readonly SchoolView[], schoolId: SchoolId): SchoolView {
  const school = schools.find((entry) => entry.id === schoolId);
  if (!school) throw new Error(`Unknown chat school: ${schoolId}`);
  return school;
}

/** Department names for sample writers at a school, leaving out the player's own department. */
export function sampleDepartments(schoolId: SchoolId, exclude: string, count: number): string[] {
  const names = [...getSchoolDepartments(schoolId).map((department) => department.name), ...GENERIC_DEPARTMENTS];
  const unique = [...new Set(names)].filter((name) => name !== exclude);
  return unique.slice(0, count);
}

/** School chat: schoolmates only, and rank talk follows the player's school's current national rank. */
export function getSchoolChatSamples({ schools, mySchoolId, myNickname, myDepartment }: ChatSampleInput): ChatMessage[] {
  const me = schoolOf(schools, mySchoolId);
  const above = schools.find((school) => school.rank === me.rank - 1);
  const below = schools.find((school) => school.rank === me.rank + 1);
  const [dept1, dept2, dept3, dept4] = sampleDepartments(mySchoolId, myDepartment, 4);
  const mate = { schoolInitial: me.firstSyllable, schoolBadgeBg: me.badgeColor, isMe: false };
  return [
    { id: 'msg-1', ...mate, nickname: '새벽코딩러', department: dept1, text: '도서관 앞 클릭 모임 ㄱㄱ', time: '09:32' },
    {
      id: 'msg-2', ...mate, nickname: '기계과공주', department: dept2, time: '09:35',
      text: above ? `${me.rank}위 실화냐 ${above.rank}위 가자!!` : `${me.rank}위 지키자!! 끝까지 간다`,
    },
    { id: 'msg-3', schoolInitial: me.firstSyllable, schoolBadgeBg: MY_MESSAGE_BADGE, nickname: myNickname, department: myDepartment, text: '방금 세기 x2 됐다', time: '09:38', isMe: true },
    { id: 'msg-4', schoolInitial: me.firstSyllable, schoolBadgeBg: FILTERED_BADGE, nickname: '익명학우', department: dept3, text: '⚠ 필터링된 메시지입니다', time: '09:39', isMe: false, isFiltered: true },
    {
      id: 'msg-5', ...mate, nickname: '공대생7', department: dept4, time: '09:41',
      text: above
        ? `${above.shortName} ${above.rank}위 추월 가능?`
        : `${below?.shortName ?? '2위'} 따라온다 긴장해!`,
    },
  ];
}

const UNION_WRITERS = [
  { nickname: '오리연못수호자', time: '09:20', text: (school: SchoolView) => `${school.shortName} ${school.rank}위 유지 중! 연합 화이팅 🔥` },
  { nickname: '자갈치불꽃', time: '09:28', text: (school: SchoolView) => `${school.shortName}도 클릭 모으는 중, 같이 올라가요` },
] as const;

/**
 * Alliance chat: one sample from each other member school, then the player's message.
 * Returns null when the player's school has no alliance; the chat sheet then shows an empty state.
 */
export function getUnionChatSamples(input: ChatSampleInput, allianceSchoolIds: readonly SchoolId[] | null): ChatMessage[] | null {
  if (!allianceSchoolIds || !allianceSchoolIds.includes(input.mySchoolId)) return null;
  const { schools, mySchoolId, myNickname, myDepartment } = input;
  const me = schoolOf(schools, mySchoolId);
  const partners = allianceSchoolIds.filter((id) => id !== mySchoolId).map((id) => schoolOf(schools, id));
  const partnerMessages = partners.map((school, index): ChatMessage => {
    const writer = UNION_WRITERS[index % UNION_WRITERS.length];
    return {
      id: `union-${index + 1}`,
      schoolInitial: school.firstSyllable,
      schoolBadgeBg: school.badgeColor,
      nickname: writer.nickname,
      department: sampleDepartments(school.id, '', 1)[0],
      text: writer.text(school),
      time: writer.time,
      isMe: false,
    };
  });
  return [
    ...partnerMessages,
    {
      id: 'union-me',
      schoolInitial: me.firstSyllable,
      schoolBadgeBg: MY_MESSAGE_BADGE,
      nickname: myNickname,
      department: myDepartment,
      text: me.rank > 5 ? '우리 학교 곧 TOP 5 진입합니다 💪' : `우리 학교 ${me.rank}위 지킵니다 💪`,
      time: '09:36',
      isMe: true,
    },
  ];
}
