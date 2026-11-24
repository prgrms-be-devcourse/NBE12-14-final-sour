-- 대결 수락 후 정해진 시작 시각에 시작하는 예약 상태.
ALTER TABLE alliance_match ADD COLUMN scheduled_start_at TIMESTAMPTZ;
ALTER TABLE alliance_match DROP CONSTRAINT ck_alliance_match_status;
ALTER TABLE alliance_match ADD CONSTRAINT ck_alliance_match_status
    CHECK (status IN ('REQUESTED', 'SCHEDULED', 'IN_PROGRESS', 'FINISHED', 'CANCELED'));
-- 기존 ck_alliance_match_period는 SCHEDULED 상태를 허용하지 않으므로 갱신.
ALTER TABLE alliance_match DROP CONSTRAINT ck_alliance_match_period;
ALTER TABLE alliance_match ADD CONSTRAINT ck_alliance_match_period
    CHECK (status IN ('REQUESTED', 'SCHEDULED', 'CANCELED')
        OR (started_at IS NOT NULL AND ended_at IS NOT NULL));
ALTER TABLE alliance_match ADD CONSTRAINT ck_alliance_match_scheduled_start
    CHECK (status <> 'SCHEDULED' OR (scheduled_start_at IS NOT NULL AND started_at IS NULL AND ended_at IS NULL));
CREATE INDEX idx_alliance_match_scheduled_start ON alliance_match (scheduled_start_at)
    WHERE status = 'SCHEDULED' AND deleted_at IS NULL;
