import { SCHOOL_ID, type SchoolId } from '../src/data/schoolIds';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canChangeImage, createSharedImageStore, validImageUrl, type ImageDirectory } from '../src/utils/sharedImages';

const directory: ImageDirectory = {
  schoolIds: [SCHOOL_ID.hanyang, SCHOOL_ID.kaist],
  departments: [{ id: 'software', schoolId: SCHOOL_ID.hanyang }, { id: 'business', schoolId: SCHOOL_ID.hanyang }, { id: 'computing', schoolId: SCHOOL_ID.kaist }],
  users: [{ userId: 'me', schoolId: SCHOOL_ID.hanyang, departmentId: 'software' }, { userId: 'other', schoolId: SCHOOL_ID.hanyang, departmentId: 'business' }],
};

test('ownership uses IDs, including department within the school', () => {
  assert.equal(canChangeImage(directory, 'me', { type: 'school', id: SCHOOL_ID.hanyang }), true);
  assert.equal(canChangeImage(directory, 'me', { type: 'school', id: SCHOOL_ID.kaist }), false);
  assert.equal(canChangeImage(directory, 'me', { type: 'department', id: 'software' }), true);
  assert.equal(canChangeImage(directory, 'me', { type: 'department', id: 'business' }), false);
  assert.equal(canChangeImage(directory, 'me', { type: 'profile', id: 'me' }), true);
  assert.equal(canChangeImage(directory, 'me', { type: 'profile', id: 'other' }), false);
});

test('shared store notifies both subscribers only for valid unique changes', () => {
  const store = createSharedImageStore(directory);
  const target = { type: 'school' as const, id: SCHOOL_ID.hanyang };
  const events = [0, 0];
  store.subscribe(() => { events[0]++; });
  store.subscribe(() => { events[1]++; });
  assert.equal(store.apply('me', target, 'https://example.org/image.png', 'one'), true);
  assert.equal(store.get(target)?.url, 'https://example.org/image.png');
  assert.equal(store.get(target)?.revision, 1);
  assert.equal(store.apply('me', target, 'https://example.org/other.png', 'one'), false);
  assert.equal(store.apply('other', { type: 'department', id: 'software' }, 'https://example.org/a.png', 'two'), false);
  assert.equal(store.apply('me', target, 'javascript:alert(1)', 'three'), false);
  assert.deepEqual(events, [1, 1]);
  assert.equal(store.get({ type: 'school', id: SCHOOL_ID.kaist }), undefined);
});

test('rejects invalid or credentialed URL schemes', () => {
  for (const value of ['', 'data:image/png;base64,AA', 'file:///tmp/a', 'javascript:alert(1)', 'https://u:p@example.org/a']) assert.equal(validImageUrl(value), false);
  assert.equal(validImageUrl('https://example.org/a.png'), true);
});

test('returned snapshots cannot mutate stored image state', () => {
  const store = createSharedImageStore(directory);
  const target = { type: 'school' as const, id: SCHOOL_ID.hanyang };
  assert.equal(store.apply('me', target, 'https://example.org/image.png', 'one'), true);
  const image = store.get(target)!;
  assert.throws(() => { (image as { url: string }).url = 'javascript:alert(1)'; }, TypeError);
  assert.throws(() => { (image as { revision: number }).revision = -99; }, TypeError);
  assert.throws(() => { (image.target as { id: SchoolId }).id = SCHOOL_ID.kaist; }, TypeError);
  assert.deepEqual(store.get(target), { target, url: 'https://example.org/image.png', revision: 1 });
  // Mutating the caller's target object after apply must not alter the stored key data either.
  const callerTarget = { type: 'department' as const, id: 'software' };
  assert.equal(store.apply('me', callerTarget, 'https://example.org/dept.png', 'two'), true);
  callerTarget.id = 'business';
  assert.equal(store.get({ type: 'department', id: 'software' })?.target.id, 'software');
});

test('a throwing subscriber does not block other subscribers or fail a committed change', () => {
  const reported: unknown[] = [];
  const store = createSharedImageStore(directory, { onListenerError: (error) => { reported.push(error); } });
  const target = { type: 'school' as const, id: SCHOOL_ID.hanyang };
  const boom = new Error('listener failed');
  let secondCalls = 0;
  store.subscribe(() => { throw boom; });
  store.subscribe(() => { secondCalls++; });
  assert.equal(store.apply('me', target, 'https://example.org/image.png', 'one'), true);
  assert.equal(secondCalls, 1);
  assert.deepEqual(reported, [boom]);
  assert.equal(store.get(target)?.revision, 1);
  // The committed request stays deduplicated; a new request still applies and notifies.
  assert.equal(store.apply('me', target, 'https://example.org/image.png', 'one'), false);
  assert.equal(store.apply('me', target, 'https://example.org/next.png', 'two'), true);
  assert.equal(secondCalls, 2);
  assert.equal(store.get(target)?.revision, 2);
});

test('store instance owns its directory and updates members without mutating initial directory', () => {
  const initial: ImageDirectory = Object.freeze({
    schoolIds: Object.freeze([SCHOOL_ID.hanyang]),
    departments: Object.freeze([{ id: 'software', schoolId: SCHOOL_ID.hanyang }, { id: 'business', schoolId: SCHOOL_ID.hanyang }]),
    users: Object.freeze([{ userId: 'me', schoolId: SCHOOL_ID.hanyang, departmentId: 'software' }]),
  });
  const store = createSharedImageStore(initial);
  assert.equal(canChangeImage(store.getDirectory(), 'me', { type: 'department', id: 'software' }), true);
  assert.equal(canChangeImage(store.getDirectory(), 'me', { type: 'department', id: 'business' }), false);

  store.updateMember({ userId: 'me', schoolId: SCHOOL_ID.hanyang, departmentId: 'business' });
  assert.equal(canChangeImage(store.getDirectory(), 'me', { type: 'department', id: 'business' }), true);
  assert.equal(canChangeImage(store.getDirectory(), 'me', { type: 'department', id: 'software' }), false);

  // initial directory remains untouched
  assert.equal(initial.users[0].departmentId, 'software');
});
