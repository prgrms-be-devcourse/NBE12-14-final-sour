import { CLICK_BATCH_WINDOW_MS, MAX_CLICK_BATCH_SIZE, type ClickCoordinates, type ClickRequest, type ClickResponse } from '../api/clicks';
import { isApiError } from '../api/errors';
import { isClickLimitError, requiresClickVerification } from '../api/abuse';

export interface ClickBatchSnapshot {
  pendingClicks: number;
  pendingScore: number;
  sending: boolean;
  error: unknown | null;
  recoverable: boolean;
  locked: boolean;
}

export interface ClickBatcherOptions<TResponse = ClickResponse> {
  send: (request: ClickRequest) => Promise<TResponse>;
  /** `sentAt` is when the attempt that succeeded was sent (`now()`), so callers can relate it to earlier batches. */
  onAccepted: (response: TResponse, pendingScore: number, accepted: { request: ClickRequest; sentAt: number }) => void;
  onChange: (snapshot: ClickBatchSnapshot) => void;
  /** Only a first-attempt HTTP rejection reaches this callback. True cancels the rejected batch AND its queued successors. */
  onRejected?: (error: unknown, pendingClicks: number) => boolean;
  createId?: () => string;
  /** Sample once for a NEW submission. Every retry must preserve the ID, count, and coordinates. */
  getCoordinates?: () => ClickCoordinates | undefined;
  now?: () => number;
  schedule?: (callback: () => void, delay: number) => () => void;
}

/** One serial queue per signed-in member; network retries never make a new submission ID. */
export function createClickBatcher<TResponse = ClickResponse>(options: ClickBatcherOptions<TResponse>) {
  const now = options.now ?? Date.now;
  const createId = options.createId ?? (() => crypto.randomUUID());
  const schedule = options.schedule ?? ((callback, delay) => {
    const timer = setTimeout(callback, delay);
    return () => clearTimeout(timer);
  });
  const queue: number[] = [];
  let batch: { request: ClickRequest; points: number[]; startedAt: number; retried: boolean } | null = null;
  let cancelTimer: (() => void) | null = null;
  let disposed = false;
  let sending = false;
  let error: unknown | null = null;
  let nextSendAt = 0;
  let locked = false;

  const getSnapshot = (): ClickBatchSnapshot => ({
    pendingClicks: queue.length + (batch?.points.length ?? 0),
    pendingScore: [...queue, ...(batch?.points ?? [])].reduce((sum, points) => sum + points, 0),
    sending,
    locked,
    error,
    recoverable: batch !== null && !batch.retried && (isClickLimitError(error) || requiresClickVerification(error)),
  });
  const publish = () => { if (!disposed) options.onChange(getSnapshot()); };

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
      const points = queue.splice(0, MAX_CLICK_BATCH_SIZE);
      batch = { request: { submissionId: createId(), clickCount: points.length, ...options.getCoordinates?.() }, points, startedAt: now(), retried: false };
    }
    const submitted = batch;
    // The backend's dedupe key expires after 10s and does not replay the original response. Never retry a late
    // ambiguous submission: a new application after that TTL could award the score twice.
    if (submitted.retried && now() - submitted.startedAt >= 8000) {
      error = new Error('제출 확인 시간이 지나 재전송을 중단했습니다.');
      publish();
      return;
    }
    sending = true;
    const sentAt = now();
    nextSendAt = sentAt + CLICK_BATCH_WINDOW_MS;
    publish();
    try {
      const response = await options.send(submitted.request);
      if (disposed) return;
      batch = null;
      sending = false;
      options.onAccepted(response, getSnapshot().pendingScore, { request: submitted.request, sentAt });
      publish();
      arm();
    } catch (caught) {
      if (disposed) return;
      sending = false;
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
      batch = null;
      error = null;
      publish();
      arm();
      return rejected;
    },
    enqueue(points: number): boolean {
      if (disposed || error || locked) return false;
      queue.push(points);
      publish();
      arm();
      return true;
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
