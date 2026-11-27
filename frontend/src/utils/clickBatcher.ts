import { CLICK_BATCH_WINDOW_MS, MAX_CLICK_BATCH_SIZE, type ClickCoordinates, type ClickRequest, type ClickResponse } from '../api/clicks';
import { isApiError } from '../api/errors';
import { isClickLimitError, isScoreRestoring, requiresClickVerification } from '../api/abuse';
import { createUuid } from './uuid';

const RESTORE_DELAYS = [2000, 4000, 8000, 16000, 30000] as const;
/** The server keeps the next batch's slots for 30s. Stop trusting them earlier so a batch never lands after expiry. */
export const DAMAGE_SLOT_FRESH_MS = 25_000;

/** What the server already decided for the NEXT batch it receives from this member (ClickActionGuard). */
export interface DamageSlots {
  criticalIndex: readonly number[];
  damageRateIndex: readonly number[];
}

/** `known` is false when the click lands in a batch whose slots the client cannot know yet: it shows the base power. */
export interface TapEstimate {
  points: number;
  isCrit: boolean;
  known: boolean;
}

/**
 * Turns `clickPower × rate(%) × crit(2|1)` (still ×100) into the points shown for one tap. The default is a campus
 * click's base score; attacks and assists pass their own server ratio (ClickActionGuard, AttackService, AssistService).
 */
export type TapPoints = (scaled: number) => number;
export const clickTapPoints: TapPoints = (scaled) => Math.floor(scaled / 100);

/** Per-click display value; the server sums the batch before flooring, so the confirmed total can differ slightly. */
export function estimateTap(power: number, slots: DamageSlots | null, index: number, toPoints: TapPoints = clickTapPoints): TapEstimate {
  if (!slots || index >= MAX_CLICK_BATCH_SIZE) return { points: toPoints(power * 100), isCrit: false, known: false };
  const rate = slots.damageRateIndex[index] ?? 100;
  const isCrit = slots.criticalIndex.includes(index);
  return { points: toPoints(power * rate * (isCrit ? 2 : 1)), isCrit, known: true };
}

export interface ClickBatchSnapshot {
  pendingClicks: number;
  pendingScore: number;
  sending: boolean;
  error: unknown | null;
  recoverable: boolean;
  restoring: boolean;
  locked: boolean;
}

export interface ClickBatcherOptions<TResponse = ClickResponse> {
  send: (request: ClickRequest) => Promise<TResponse>;
  /** `sentAt` is when the attempt that succeeded was sent (`now()`), so callers can relate it to earlier batches. */
  onAccepted: (response: TResponse, pendingScore: number, accepted: { request: ClickRequest; sentAt: number }) => void;
  onChange: (snapshot: ClickBatchSnapshot) => void;
  /** Only a first-attempt HTTP rejection reaches this callback. True cancels the rejected batch AND its queued successors. */
  onRejected?: (error: unknown, pendingClicks: number) => boolean;
  /** Slots the response decided for the next batch. Omit for queues that do not show per-click damage. */
  getSlots?: (response: TResponse) => DamageSlots | null | undefined;
  createId?: () => string;
  /** Sample once for a NEW submission. Every retry must preserve the ID, count, and coordinates. */
  getCoordinates?: () => ClickCoordinates | undefined;
  now?: () => number;
  schedule?: (callback: () => void, delay: number) => () => void;
}

/** One serial queue per signed-in member; network retries never make a new submission ID. */
export function createClickBatcher<TResponse = ClickResponse>(options: ClickBatcherOptions<TResponse>) {
  const now = options.now ?? Date.now;
  const createId = options.createId ?? (() => createUuid());
  const schedule = options.schedule ?? ((callback, delay) => {
    const timer = setTimeout(callback, delay);
    return () => clearTimeout(timer);
  });
  // `power` is set for taps enqueued via enqueueTap so their points can be recomputed once the slots arrive.
  const queue: Array<{ points: number; power: number | null; toPoints?: TapPoints }> = [];
  type HeldSlots = { value: DamageSlots; receivedAt: number };
  let batch: { request: ClickRequest; points: number[]; slots: HeldSlots | null; startedAt: number; retried: boolean } | null = null;
  // Slots for the next batch to be cut from the queue. A batch takes them; only its acceptance brings new ones.
  let slots: HeldSlots | null = null;
  let cancelTimer: (() => void) | null = null;
  let disposed = false;
  let sending = false;
  let error: unknown | null = null;
  let nextSendAt = 0;
  let locked = false;
  let restoring = false;
  let restoreAttempts = 0;

  const getSnapshot = (): ClickBatchSnapshot => ({
    pendingClicks: queue.length + (batch?.points.length ?? 0),
    pendingScore: queue.reduce((sum, item) => sum + item.points, 0) + (batch?.points ?? []).reduce((sum, points) => sum + points, 0),
    sending,
    restoring,
    locked,
    error,
    recoverable: batch !== null && !batch.retried && (isClickLimitError(error) || requiresClickVerification(error)),
  });
  const publish = () => { if (!disposed) options.onChange(getSnapshot()); };
  const freshSlots = () => (slots && now() - slots.receivedAt < DAMAGE_SLOT_FRESH_MS ? slots.value : null);
  // Only the next batch has known slots, and only while no batch is ahead of it.
  const estimateAt = (power: number, index: number, toPoints?: TapPoints) => estimateTap(power, batch ? null : freshSlots(), index, toPoints);
  function reestimateQueue() {
    queue.forEach((item, index) => { if (item.power !== null) item.points = estimateAt(item.power, index, item.toPoints).points; });
  }
  /** A batch the server rejected before reading its slots gives them back to the next batch. */
  function returnSlots(rejected: { slots: HeldSlots | null }) {
    slots = rejected.slots;
    reestimateQueue();
  }

  function arm() {
    if (disposed || sending || cancelTimer || error || (!batch && queue.length === 0)) return;
    cancelTimer = schedule(() => {
      cancelTimer = null;
      void flush();
    }, Math.max(CLICK_BATCH_WINDOW_MS, nextSendAt - now()));
  }

  async function flush() {
    if (disposed || sending || error) return;
    if (!batch) {
      if (!queue.length) return;
      const points = queue.splice(0, MAX_CLICK_BATCH_SIZE).map((item) => item.points);
      batch = { request: { submissionId: createId(), clickCount: points.length, ...options.getCoordinates?.() }, points, slots, startedAt: now(), retried: false };
      slots = null;
    }
    const submitted = batch;
    // The backend's dedupe key expires after 10s and does not replay the original response. Never retry a late
    // ambiguous submission: a new application after that TTL could award the score twice.
    if (submitted.retried && now() - submitted.startedAt >= 8000) {
      restoring = false;
      error = new Error('제출 확인 시간이 지나 재전송을 중단했습니다.');
      publish();
      return;
    }
    if (restoring && !submitted.retried) submitted.startedAt = now();
    sending = true;
    const sentAt = now();
    nextSendAt = sentAt + CLICK_BATCH_WINDOW_MS;
    publish();
    try {
      const response = await options.send(submitted.request);
      if (disposed) return;
      batch = null;
      sending = false;
      restoring = false;
      restoreAttempts = 0;
      const next = options.getSlots?.(response);
      slots = next ? { value: next, receivedAt: now() } : null;
      // Clicks queued during the request were shown at base power; the new slots now decide their real value.
      reestimateQueue();
      options.onAccepted(response, getSnapshot().pendingScore, { request: submitted.request, sentAt });
      publish();
      arm();
    } catch (caught) {
      if (disposed) return;
      sending = false;
      if (isScoreRestoring(caught)) {
        restoring = true;
        const delay = RESTORE_DELAYS[Math.min(restoreAttempts++, RESTORE_DELAYS.length - 1)];
        nextSendAt = now() + delay;
        publish();
        arm();
        return;
      }
      restoring = false;
      restoreAttempts = 0;
      const retryable = isApiError(caught) && (caught.kind === 'network' || caught.kind === 'injected');
      if (retryable && !submitted.retried && now() - submitted.startedAt < 7000) {
        submitted.retried = true;
        publish();
        arm();
        return;
      }
      // A domain may cancel an explicitly rejected action, but never after response loss: an earlier attempt
      // could have committed. Never convert or replay the queued successors under a different action kind.
      if (!submitted.retried && isApiError(caught) && caught.kind === 'http'
        && options.onRejected?.(caught, submitted.points.length + queue.length)) {
        batch = null;
        queue.length = 0;
        error = null;
        returnSlots(submitted);
        publish();
        return;
      }
      // 429/403/409 and ambiguous failures pause the queue. Keeping the exact batch and its successors makes the
      // unconfirmed count visible; do not silently discard it or invent a new ID to evade server protection.
      error = caught;
      publish();
    }
  }

  return {
    getSnapshot,
    acquireIdleLock(): (() => void) | null {
      if (disposed || locked || sending || batch || queue.length || error) return null;
      locked = true;
      publish();
      let released = false;
      return () => {
        if (released) return;
        released = true;
        locked = false;
        publish();
      };
    },
    resumeAfterVerification(): boolean {
      if (disposed || !batch || batch.retried || !requiresClickVerification(error)) return false;
      // A first-attempt 403 is checked before dedupe, so the server has never consumed this ID or applied it.
      // An earlier network failure would make that unknowable; those batches must remain paused.
      batch.startedAt = now();
      error = null;
      publish();
      arm();
      return true;
    },
    discardRateLimitedBatch(): { clicks: number; score: number } | null {
      if (disposed || !batch || batch.retried || !isClickLimitError(error)) return null;
      // A first-attempt 429 consumed the ID but applied no score. Explicitly discard just that rejected batch;
      // keep its successors. Never replay it under a new ID as a way around server protection.
      const rejected = { clicks: batch.points.length, score: batch.points.reduce((sum, points) => sum + points, 0) };
      // The guard rejects 429 before it reads the slots, so they still belong to the next batch.
      const returned = batch;
      batch = null;
      error = null;
      returnSlots(returned);
      publish();
      arm();
      return rejected;
    },
    enqueue(points: number): boolean {
      if (disposed || error || locked || restoring) return false;
      queue.push({ points, power: null });
      publish();
      arm();
      return true;
    },
    /** Forget the held slots, e.g. when the next batch goes to a different server action with its own slots. */
    clearSlots() {
      slots = null;
      reestimateQueue();
    },
    /** Enqueue one click of `power` and return what to show for it, using the slots the server already decided. */
    enqueueTap(power: number, toPoints?: TapPoints): TapEstimate | null {
      if (disposed || error || locked || restoring) return null;
      const estimate = estimateAt(power, queue.length, toPoints);
      queue.push({ points: estimate.points, power, toPoints });
      publish();
      arm();
      return estimate;
    },
    dispose() {
      disposed = true;
      cancelTimer?.();
      cancelTimer = null;
      // An in-flight request may have committed already. Ignore its reply; never send this member's queue with
      // the next member's token. The UI warns before leaving with unconfirmed clicks.
    },
  };
}
