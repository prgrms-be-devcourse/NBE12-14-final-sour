// SCRUM-159: the "other people's activity" particles. In API mode a school's previous-second clicks, attacks and
// assists (2026-11-28) come from my own click/attack/assist answers or the 1s activity poll (universityActivity.ts).
// Plan: .agents/01-PLAN/261118-2039-scrum159_other_click_particles.md

/**
 * How long one of my batches may still sit in a reported "previous second". The server counts a batch in the second
 * it arrives, and a report covers the second before the one it is made in, so a batch up to 2s old may be in it. The
 * extra 500ms covers latency between send and arrival times: missing an own batch would show my taps as someone
 * else's, while an extra one only lowers the count.
 */
export const OWN_CLICK_OVERLAP_MS = 2500;

/** What other people did at one school in the previous second: clicks by its students, attacks and assists it received. */
export interface SchoolActivity {
  clicks: number;
  attacks: number;
  assists: number;
}
export type ActivityKind = 'click' | 'attack' | 'assist';

/** A count from the server as a whole non-negative number; a missing or broken value (older server) is 0. */
export function activityCount(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
}

export type FeedListener<T> = (value: T) => void;
export interface Feed<T> {
  subscribe(listener: FeedListener<T>): () => void;
}
export function createFeed<T>(): Feed<T> & { publish(value: T): void } {
  const listeners = new Set<FeedListener<T>>();
  return {
    subscribe(listener) {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
    publish(value) {
      for (const listener of [...listeners]) listener(value);
    },
  };
}

export type OtherClicksListener = FeedListener<SchoolActivity>;
/** A push source of one school's activity. Unlike the mock source, the screen does not poll it. */
export type OtherClicksFeed = Feed<SchoolActivity>;
