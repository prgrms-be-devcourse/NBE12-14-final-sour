import type { SchoolId } from '../data/schoolIds';

/** A school target carries the backend university ID; department and profile IDs are still mock strings. */
export type ImageTarget = { type: 'school'; id: SchoolId } | { type: 'department' | 'profile'; id: string };
/** Only the target's own fields, so an extra property on the caller's object is never stored. */
export function copyImageTarget(target: ImageTarget): ImageTarget {
  return target.type === 'school' ? { type: target.type, id: target.id } : { type: target.type, id: target.id };
}

export interface ImageMember { userId: string; schoolId: SchoolId; departmentId: string }
export interface ImageDirectory {
  schoolIds: readonly SchoolId[];
  departments: readonly { id: string; schoolId: SchoolId }[];
  users: readonly ImageMember[];
}
export interface ImageEvent { readonly target: Readonly<ImageTarget>; readonly url: string; readonly revision: number }
export interface SharedImageStoreOptions {
  /** Receives subscriber failures; defaults to the host error reporter. */
  onListenerError?: (error: unknown) => void;
}

const reportListenerError = (error: unknown) => {
  try {
    if (typeof globalThis.reportError === 'function') {
      globalThis.reportError(error);
    } else {
      throw error;
    }
  } catch (err) {
    console.error(err);
  }
};

// Only HTTP(S) URLs are accepted; local bundled samples can be served as absolute URLs.
export function validImageUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return (url.protocol === 'https:' || url.protocol === 'http:') && !url.username && !url.password;
  } catch { return false; }
}

export function canChangeImage(directory: ImageDirectory, userId: string, target: ImageTarget): boolean {
  const member = directory.users.find((user) => user.userId === userId);
  if (!member) return false;
  if (target.type === 'school') return directory.schoolIds.includes(target.id) && member.schoolId === target.id;
  if (target.type === 'department') {
    return directory.departments.some((department) => department.id === target.id && department.schoolId === member.schoolId)
      && member.departmentId === target.id;
  }
  return target.id === member.userId;
}

// In-memory mock only. A real service must authenticate membership, issue authoritative
// revisions, distribute events, invalidate caches and resync after reconnecting.
export function createSharedImageStore(initialDirectory: ImageDirectory, options: SharedImageStoreOptions = {}) {
  const onListenerError = options.onListenerError ?? reportListenerError;
  let currentDirectory: ImageDirectory = initialDirectory;
  // Stored events are deeply frozen immutable snapshots, so callers of get() cannot bypass
  // permission/URL checks, revision increments or notifications by mutating the result.
  const images = new Map<string, ImageEvent>();
  const listeners = new Set<() => void>();
  const completed = new Set<string>();
  const key = (target: ImageTarget) => JSON.stringify([target.type, target.id]);
  const get = (target: ImageTarget): ImageEvent | undefined => images.get(key(target));
  const getDirectory = (): ImageDirectory => currentDirectory;
  const updateMember = (member: ImageMember) => {
    const existingIndex = currentDirectory.users.findIndex((u) => u.userId === member.userId);
    let updatedUsers: readonly ImageMember[];
    if (existingIndex >= 0) {
      updatedUsers = currentDirectory.users.map((u, i) => (i === existingIndex ? Object.freeze({ ...member }) : u));
    } else {
      updatedUsers = [...currentDirectory.users, Object.freeze({ ...member })];
    }
    currentDirectory = Object.freeze({
      ...currentDirectory,
      users: Object.freeze(updatedUsers),
    });
  };
  const notify = () => {
    // The change is already committed; one failing subscriber must not hide it from the others.
    for (const listener of [...listeners]) {
      try { listener(); } catch (error) { onListenerError(error); }
    }
  };
  const apply = (userId: string, target: ImageTarget, url: string, requestId: string): boolean => {
    const normalized = url.trim();
    if (!requestId || completed.has(requestId) || !canChangeImage(currentDirectory, userId, target) || !validImageUrl(normalized)) return false;
    const revision = (get(target)?.revision ?? 0) + 1;
    completed.add(requestId);
    images.set(key(target), Object.freeze({ target: Object.freeze(copyImageTarget(target)), url: normalized, revision }));
    notify();
    return true;
  };
  const subscribe = (listener: () => void) => {
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  };
  return { get, apply, subscribe, getDirectory, updateMember };
}
