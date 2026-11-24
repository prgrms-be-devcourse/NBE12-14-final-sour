// Backend university IDs (SCRUM-149). The university table is filled by V2__seed_universities.sql, and its
// IDENTITY column numbers rows in insertion order, so the Nth name there has ID N (checked against the API on
// 2026-11-18 and by tests/schoolIds.test.ts against the seed file). Keys are the mock's old readable slugs, so mock
// data and tests write SCHOOL_ID.hanyang instead of a bare number.

export const SCHOOL_ID = {
  kaist: 1, // KAIST
  dgist: 2, // DGIST
  pusan: 3, // 부산대학교
  chonnam: 4, // 전남대학교
  snu: 5, // 서울대학교
  yonsei: 6, // 연세대학교
  korea: 7, // 고려대학교
  hanyang: 8, // 한양대학교
  skku: 9, // 성균관대학교
  sogang: 10, // 서강대학교
  cau: 11, // 중앙대학교
  khu: 12, // 경희대학교
  ewha: 13, // 이화여자대학교
  hufs: 14, // 한국외국어대학교
  kookmin: 15, // 국민대학교
  inha: 16, // 인하대학교
  inu: 17, // 인천대학교
  ginue: 18, // 경인교육대학교
  icatholic: 19, // 인천가톨릭대학교
  ajou: 20, // 아주대학교
  gachon: 21, // 가천대학교
  kau: 22, // 한국항공대학교
  kyonggi: 23, // 경기대학교
  daejin: 24, // 대진대학교
  yongin: 25, // 용인대학교
  kangnam: 26, // 강남대학교
  suwon: 27, // 수원대학교
  hanshin: 28, // 한신대학교
  hknu: 29, // 한경국립대학교
  kangwon: 30, // 강원대학교
  hallym: 31, // 한림대학교
  sangji: 32, // 상지대학교
  cku: 33, // 가톨릭관동대학교
  cbu: 34, // 충북대학교
  knue: 35, // 한국교원대학교
  ut: 36, // 국립한국교통대학교
  cheongju: 37, // 청주대학교
  seowon: 38, // 서원대학교
  semyung: 39, // 세명대학교
  cje: 40, // 청주교육대학교
  jungwon: 41, // 중원대학교
  kdu: 42, // 극동대학교
  u1: 43, // 유원대학교
  cnu: 44, // 충남대학교
  hanbat: 45, // 국립한밭대학교
  hannam: 46, // 한남대학교
  kut: 47, // 한국기술교육대학교
  sch: 48, // 순천향대학교
  gongju: 49, // 국립공주대학교
  hoseo: 50, // 호서대학교
  sunmoon: 51, // 선문대학교
  baekseok: 52, // 백석대학교
  nsu: 53, // 남서울대학교
  konyang: 54, // 건양대학교
  hanseo: 55, // 한서대학교
  chungwoon: 56, // 청운대학교
  jbnu: 57, // 전북대학교
  chosun: 58, // 조선대학교
  gist: 59, // GIST
  wku: 60, // 원광대학교
  jeonju: 61, // 전주대학교
  kunsan: 62, // 국립군산대학교
  scnu: 63, // 국립순천대학교
  mokpo: 64, // 국립목포대학교
  woosuk: 65, // 우석대학교
  knu: 66, // 경북대학교
  postech: 67, // POSTECH
  unist: 68, // UNIST
  kmu: 69, // 계명대학교
  yu: 70, // 영남대학교
  pknu: 71, // 국립부경대학교
  donga: 72, // 동아대학교
  cwnu: 73, // 국립창원대학교
  gnu: 74, // 경상국립대학교
  jejunu: 75, // 제주대학교
  jeju_intl: 76, // 제주국제대학교
} as const;

/** A backend university ID (university.id). */
export type SchoolId = number;

/**
 * 푸른바다대학교, a fictional school kept by the mock shared-image members and alliance checks. It is not in the
 * backend seed, so its ID is negative and can never match a university.id.
 */
export const FICTIONAL_SCHOOL_ID: SchoolId = -1;
