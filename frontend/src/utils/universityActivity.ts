// One place that turns a school's previous-second activity into particles (2026-11-28). Two sources report it:
// my own click/attack/assist answers (no extra request while I am tapping) and the 1s activity poll (the rest of the
// time). Both carry the server's `second`, so a second reported by both is drawn once.
import type { ApiClient } from '../api/client';
import type { UniversityActivity } from '../api/activity';
import { activityCount, createFeed, OWN_CLICK_OVERLAP_MS, type ActivityKind, type OtherClicksFeed, type SchoolActivity } from './otherClicksFeed';

/** A report this recent (client time) makes the poll skip its request for that school. */
export const ACTIVITY_FRESH_MS = 1000;

interface OwnBatch { universityId: number; kind: ActivityKind; count: number; sentAt: number }

export interface ActivityHub {
  /**
   * One previous-second report about a school. A report without a valid `second` (older server) is ignored, so the
   * poll keeps running. Returns whether it was new and was published.
   */
  report(universityId: number, activity: Partial<UniversityActivity> | null | undefined): boolean;
  /** One of my accepted batches. Call it after reporting that batch's own answer: the answer's second is before it. */
  recordOwn(universityId: number, kind: ActivityKind, count: number, sentAt: number): void;
  /** Whether a report about this school arrived within ACTIVITY_FRESH_MS. */
  isFresh(universityId: number): boolean;
  feed(universityId: number): OtherClicksFeed;
  /** Another member signs in: their own batches are not this member's. */
  clearOwn(): void;
}

export function createActivityHub({ now = Date.now, ownWindowMs = OWN_CLICK_OVERLAP_MS }: { now?: () => number; ownWindowMs?: number } = {}): ActivityHub {
  const feeds = new Map<number, ReturnType<typeof createFeed<SchoolActivity>>>();
  const lastSecond = new Map<number, number>();
  const lastAt = new Map<number, number>();
  let own: OwnBatch[] = [];
  const feedOf = (universityId: number) => {
    let feed = feeds.get(universityId);
    if (!feed) { feed = createFeed<SchoolActivity>(); feeds.set(universityId, feed); }
    return feed;
  };
  // My batches that may sit in the reported second. A lower bound: missing one would show my own taps as someone
  // else's, while an extra one only lowers the count.
  const ownCount = (universityId: number, kind: ActivityKind, at: number) => own
    .filter((batch) => batch.universityId === universityId && batch.kind === kind && batch.sentAt <= at && at - batch.sentAt < ownWindowMs)
    .reduce((sum, batch) => sum + batch.count, 0);
  return {
    report(universityId, activity) {
      const second = activity?.second;
      if (typeof second !== 'number' || !Number.isFinite(second)) return false;
      const at = now();
      lastAt.set(universityId, at);
      if ((lastSecond.get(universityId) ?? -Infinity) >= second) return false;
      lastSecond.set(universityId, second);
      own = own.filter((batch) => at - batch.sentAt < ownWindowMs);
      const minus = (value: unknown, kind: ActivityKind) => Math.max(0, activityCount(value) - ownCount(universityId, kind, at));
      feedOf(universityId).publish({
        clicks: minus(activity?.clicks, 'click'),
        attacks: minus(activity?.attacks, 'attack'),
        assists: minus(activity?.assists, 'assist'),
      });
      return true;
    },
    recordOwn(universityId, kind, count, sentAt) {
      const whole = activityCount(count);
      if (whole > 0) own.push({ universityId, kind, count: whole, sentAt });
    },
    isFresh(universityId) {
      return now() - (lastAt.get(universityId) ?? -Infinity) < ACTIVITY_FRESH_MS;
    },
    feed(universityId) {
      const feed = feedOf(universityId);
      return { subscribe: (listener) => feed.subscribe(listener) };
    },
    clearOwn() {
      own = [];
    },
  };
}

const hubs = new WeakMap<ApiClient, ActivityHub>();
/** One hub per API client: the click queue, the attack queue and the screens share it without passing it around. */
export function activityHubFor(client: ApiClient): ActivityHub {
  let hub = hubs.get(client);
  if (!hub) { hub = createActivityHub(); hubs.set(client, hub); }
  return hub;
}
