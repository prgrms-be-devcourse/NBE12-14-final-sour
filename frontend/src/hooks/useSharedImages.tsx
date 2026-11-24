import { FICTIONAL_SCHOOL_ID, SCHOOL_ID } from '../data/schoolIds.ts';
import React, { createContext, useContext, useState, useSyncExternalStore } from 'react';
import { REAL_UNIVERSITIES } from '../data/universitiesData.ts';
import { KNOWN_DEPARTMENTS } from '../data/gameRules.ts';
import { createSharedImageStore, type ImageDirectory, type ImageTarget } from '../utils/sharedImages.ts';

// Explicit mock IDs only; these are not authenticated memberships.
export const MOCK_IMAGE_DIRECTORY: ImageDirectory = Object.freeze({
  schoolIds: Object.freeze([...REAL_UNIVERSITIES.map((school) => school.id), FICTIONAL_SCHOOL_ID]),
  departments: Object.freeze([
    ...KNOWN_DEPARTMENTS.map((d) => Object.freeze({ id: d.id, schoolId: d.schoolId })),
    Object.freeze({ id: 'marine-nav', schoolId: FICTIONAL_SCHOOL_ID }),
    Object.freeze({ id: 'marine-bio', schoolId: FICTIONAL_SCHOOL_ID }),
    Object.freeze({ id: 'marine-it', schoolId: FICTIONAL_SCHOOL_ID }),
  ]),
  users: Object.freeze([
    ...Object.entries({
      me: 'my-department',
      night: 'my-department',
      star: 'electronics',
      tiger: 'business',
      lunch: 'biology',
      clock: 'design',
      deer: 'ai',
      dog: 'architecture',
      bird: 'media',
    }).map(([userId, departmentId]) => Object.freeze({ userId, schoolId: SCHOOL_ID.hanyang, departmentId })),
    Object.freeze({ userId: 'external-viewer', schoolId: SCHOOL_ID.kaist, departmentId: 'kaist-computing' }),
    Object.freeze({ userId: 'visiting-user-1', schoolId: FICTIONAL_SCHOOL_ID, departmentId: 'marine-nav' }),
    Object.freeze({ userId: 'visiting-user-2', schoolId: FICTIONAL_SCHOOL_ID, departmentId: 'marine-bio' }),
    Object.freeze({ userId: 'visiting-user-3', schoolId: FICTIONAL_SCHOOL_ID, departmentId: 'marine-it' }),
    Object.freeze({ userId: 'our-gb-1', schoolId: SCHOOL_ID.pusan, departmentId: 'my-department' }),
    Object.freeze({ userId: 'our-gb-2', schoolId: SCHOOL_ID.kaist, departmentId: 'kaist-computing' }),
    Object.freeze({ userId: 'our-gb-3', schoolId: SCHOOL_ID.chonnam, departmentId: 'my-department' }),
    Object.freeze({ userId: 'our-gb-4', schoolId: SCHOOL_ID.jejunu, departmentId: 'my-department' }),
    Object.freeze({ userId: 'our-gb-5', schoolId: SCHOOL_ID.korea, departmentId: 'my-department' }),
    // Visit guestbook sample authors (src/data/mockGuestbook.ts).
    Object.freeze({ userId: 'gb-1', schoolId: SCHOOL_ID.kaist, departmentId: 'kaist-computing' }),
    Object.freeze({ userId: 'gb-2', schoolId: SCHOOL_ID.pusan, departmentId: 'my-department' }),
    Object.freeze({ userId: 'gb-3', schoolId: SCHOOL_ID.yonsei, departmentId: 'my-department' }),
    Object.freeze({ userId: 'gb-4', schoolId: SCHOOL_ID.korea, departmentId: 'my-department' }),
    Object.freeze({ userId: 'gb-5', schoolId: SCHOOL_ID.hanyang, departmentId: 'my-department' }),
    Object.freeze({ userId: 'gb-6', schoolId: SCHOOL_ID.chonnam, departmentId: 'my-department' }),
    Object.freeze({ userId: 'gb-7', schoolId: SCHOOL_ID.jejunu, departmentId: 'my-department' }),
    Object.freeze({ userId: 'msg-1', schoolId: SCHOOL_ID.hanyang, departmentId: 'my-department' }),
    Object.freeze({ userId: 'msg-2', schoolId: SCHOOL_ID.hanyang, departmentId: 'mechanical' }),
    Object.freeze({ userId: 'msg-3', schoolId: SCHOOL_ID.hanyang, departmentId: 'my-department' }),
    Object.freeze({ userId: 'msg-4', schoolId: SCHOOL_ID.hanyang, departmentId: 'my-department' }),
    Object.freeze({ userId: 'msg-5', schoolId: SCHOOL_ID.hanyang, departmentId: 'electronics' }),
    Object.freeze({ userId: 'union-1', schoolId: SCHOOL_ID.kaist, departmentId: 'kaist-computing' }),
    Object.freeze({ userId: 'union-2', schoolId: SCHOOL_ID.pusan, departmentId: 'my-department' }),
    Object.freeze({ userId: 'union-3', schoolId: SCHOOL_ID.hanyang, departmentId: 'my-department' }),
  ]),
});

export type SharedImageStore = ReturnType<typeof createSharedImageStore>;
const Context = createContext<SharedImageStore | null>(null);

export function SharedImageProvider({
  children,
  store,
  initialDirectory = MOCK_IMAGE_DIRECTORY,
}: {
  children?: React.ReactNode;
  store?: SharedImageStore;
  initialDirectory?: ImageDirectory;
}) {
  const [instance] = useState(() => store ?? createSharedImageStore(initialDirectory));
  return <Context.Provider value={instance}>{children}</Context.Provider>;
}
export function useImageStore() {
  const store = useContext(Context);
  if (!store) throw new Error('SharedImageProvider is required');
  return store;
}
export function useSharedImage(target: ImageTarget) {
  const store = useImageStore();
  return useSyncExternalStore(store.subscribe, () => store.get(target), () => store.get(target));
}
