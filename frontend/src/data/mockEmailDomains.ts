// Mock sample of school email domains (M4 decision D1, docs/261108-2020-m4_onboarding_auth_plan.md 6).
// Plan basis: UniversityEmailDomain allows verified domains only and holds back a domain shared by several
// schools (docs/4차-프로젝트-기획서(261107).html:870). This list is a sample, not the service's support policy,
// and the real university domains were not verified.

import { SCHOOL_ID, type SchoolId } from './schoolIds';

export interface MockEmailDomain {
  domain: string;
  schoolIds: readonly SchoolId[];
}

export const MOCK_EMAIL_DOMAINS: readonly MockEmailDomain[] = [
  { domain: 'hanyang.ac.kr', schoolIds: [SCHOOL_ID.hanyang] },
  { domain: 'kaist.ac.kr', schoolIds: [SCHOOL_ID.kaist] },
  { domain: 'pusan.ac.kr', schoolIds: [SCHOOL_ID.pusan] },
  // Fictional domain for showing the "shared domain" rejection.
  { domain: 'shared-demo.ac.kr', schoolIds: [SCHOOL_ID.hanyang, SCHOOL_ID.kaist] },
];

/** Quick-fill samples on the email step: three supported, one shared and one unsupported domain. */
export const MOCK_SAMPLE_EMAILS = [
  'student@hanyang.ac.kr',
  'student@kaist.ac.kr',
  'student@pusan.ac.kr',
  'student@shared-demo.ac.kr',
  'student@gmail.com',
] as const;

export type DomainResolution =
  | { kind: 'school'; schoolId: SchoolId }
  | { kind: 'ambiguous'; schoolIds: readonly SchoolId[] }
  | { kind: 'unsupported' };

/** Exact match on the part after '@'. Subdomains are not matched in the mockup. */
export function resolveEmailDomain(normalizedEmail: string, domains: readonly MockEmailDomain[] = MOCK_EMAIL_DOMAINS): DomainResolution {
  const domain = normalizedEmail.slice(normalizedEmail.lastIndexOf('@') + 1);
  const entry = domains.find((item) => item.domain === domain);
  if (!entry || entry.schoolIds.length === 0) return { kind: 'unsupported' };
  if (entry.schoolIds.length > 1) return { kind: 'ambiguous', schoolIds: entry.schoolIds };
  return { kind: 'school', schoolId: entry.schoolIds[0] };
}
