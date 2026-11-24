// Header school names for checking how the campus header fits long names. Display values only, not school data.
// The mock demo tools and the admin test tool (SCRUM-175) choose one; 'default' shows the player's school.

export const DEMO_SCHOOL_NAMES = [
  { id: 'default', label: '기본', name: null },
  { id: 'medium', label: '9자', name: '서울과학기술대학교' },
  { id: 'long', label: '긴 이름', name: '한국방송통신대학교 프라임칼리지 서울캠퍼스' },
] as const;

export type DemoSchoolNameId = (typeof DEMO_SCHOOL_NAMES)[number]['id'];
