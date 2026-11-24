// SCRUM-183: the existing alliance UI displays KST. Treat datetime-local as Korean wall time, never the device's zone.
const KST_OFFSET_MS = 9 * 60 * 60_000;

/** A datetime-local value in KST; the input itself carries no offset. */
export function kstDateTimeInput(instant: number): string {
  return Number.isFinite(instant) ? new Date(instant + KST_OFFSET_MS).toISOString().slice(0, 16) : '';
}

export type ScheduleValidation = { scheduledStartAt: string } | { problem: string };

/** UX check only. The server rechecks both alliances' expiry, membership and its own clock on request/accept. */
export function validateMatchSchedule(input: string, now: number, durationHours: number, ownExpiredAt?: string): ScheduleValidation {
  if (!input) return { problem: '예약 시작 시각을 입력해 주세요.' };
  if (!/^(?!0000)\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(input)) {
    return { problem: '예약 시작 시각을 올바른 날짜와 시간으로 입력해 주세요.' };
  }
  const scheduledStartAt = `${input}:00+09:00`;
  const start = Date.parse(scheduledStartAt);
  // Date.parse normalizes some invalid calendar dates (e.g. February 30), so round-trip the fields in the chosen zone.
  if (!Number.isFinite(start) || kstDateTimeInput(start) !== input) {
    return { problem: '예약 시작 시각을 올바른 날짜와 시간으로 입력해 주세요.' };
  }
  if (start <= now) return { problem: '예약 시작은 현재보다 이후의 한국 시각(KST)으로 선택해 주세요.' };
  if (ownExpiredAt && start + durationHours * 60 * 60_000 > Date.parse(ownExpiredAt)) {
    return { problem: '예약 시작과 대결 시간을 합하면 우리 연합 만료를 넘어요. 시작 시각을 앞당기거나 대결 시간을 줄여 주세요.' };
  }
  return { scheduledStartAt };
}
