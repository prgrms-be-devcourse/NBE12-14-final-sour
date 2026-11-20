-- NULL marks a match started before scoring was enabled. Never backfill it with zeros.
ALTER TABLE alliance_match ADD COLUMN score_state varchar(8);
ALTER TABLE alliance_match ADD CONSTRAINT alliance_match_score_state_check
    CHECK (score_state IS NULL OR (score_state IN ('PENDING', 'READY')
        AND status IN ('IN_PROGRESS', 'FINISHED') AND a_university_1_id IS NOT NULL));

CREATE INDEX alliance_match_score_pending_idx ON alliance_match (id)
    WHERE score_state = 'PENDING' AND status = 'IN_PROGRESS' AND deleted_at IS NULL;
