import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { kstDateTimeInput, validateMatchSchedule } from '../src/api/matchSchedule';

const NOW = Date.parse('2026-11-19T12:00:00+09:00');

test('KST 입력을 offset 포함 ISO로 보내며 브라우저 시간대에 의존하지 않는다', () => {
  const original = process.env.TZ;
  try {
    for (const zone of ['UTC', 'America/Los_Angeles', 'Asia/Seoul']) {
      process.env.TZ = zone;
      assert.deepEqual(validateMatchSchedule('2026-11-19T13:30', NOW, 3), { scheduledStartAt: '2026-11-19T13:30:00+09:00' });
      assert.equal(kstDateTimeInput(NOW), '2026-11-19T12:00');
    }
  } finally {
    if (original === undefined) delete process.env.TZ;
    else process.env.TZ = original;
  }
});

test('빈 값, 잘못된 달력·시간·offset 입력을 거절한다', () => {
  for (const input of ['', '2026-02-29T12:00', '2028-02-30T12:00', '2026-04-31T12:00', '2026-13-01T12:00',
    '2026-11-19T24:00', '2026-11-19T13:60', '2026-11-19', '2026-11-19T13:00Z', '2026-11-19T13:00+09:00', '0000-01-01T12:00']) {
    assert.ok('problem' in validateMatchSchedule(input, NOW, 1), input);
  }
  assert.deepEqual(validateMatchSchedule('2028-02-29T12:00', NOW, 1), { scheduledStartAt: '2028-02-29T12:00:00+09:00' });
});

test('과거와 현재 시각은 거절하고 미래 시각은 허용한다', () => {
  for (const input of ['2026-11-19T11:59', '2026-11-19T12:00']) {
    const result = validateMatchSchedule(input, NOW, 3);
    assert.ok('problem' in result);
    assert.match(result.problem, /현재보다 이후/);
  }
  assert.ok('scheduledStartAt' in validateMatchSchedule('2026-11-19T12:01', NOW, 3));
});

test('시작+기간이 우리 연합 만료와 같으면 허용하고, 넘으면 거절한다', () => {
  const expiry = '2026-11-19T18:00:00+09:00';
  assert.ok('scheduledStartAt' in validateMatchSchedule('2026-11-19T15:00', NOW, 3, expiry));
  const result = validateMatchSchedule('2026-11-19T15:01', NOW, 3, expiry);
  assert.ok('problem' in result);
  assert.match(result.problem, /우리 연합 만료/);
  assert.equal(kstDateTimeInput(Date.parse('2026-11-19T23:00:00Z')), '2026-11-20T08:00');
});

test('예약 서버 계약 센서: 요청 필드·검증·수락 전환이 바뀌면 프론트를 재검토한다', () => {
  const root = new URL('../../src/main/java/com/back/nbe1214finalsour/', import.meta.url);
  const read = (path: string) => readFileSync(new URL(path, root), 'utf8');
  const fix = '예약 시간 입력·검증과 상태 갱신을 서버 계약에 맞추고 tests/matchSchedule.test.ts를 갱신하세요.';
  assert.match(read('domain/alliance/dto/AllianceMatchCreateRequest.java'), /OffsetDateTime scheduledStartAt/, fix);
  const service = read('domain/alliance/service/AllianceMatchService.java');
  assert.match(service, /!scheduledStartAt\.isAfter\(now\)/, fix);
  assert.match(service, /scheduledStartAt\.plusHours\(durationHours\)\.isAfter\(earliestExpiry\)/, fix);
  assert.match(service, /match\.schedule\(now\)/, fix);
  assert.match(read('global/exception/ErrorCode.java'), /MATCH_INVALID_SCHEDULE\(HttpStatus\.BAD_REQUEST/, fix);
});
