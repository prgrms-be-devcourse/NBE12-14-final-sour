CREATE INDEX alliance_match_score_roster_idx ON alliance_match USING gin (
    (ARRAY[a_university_1_id, a_university_2_id, b_university_1_id, b_university_2_id])
) WHERE status = 'IN_PROGRESS' AND deleted_at IS NULL AND score_state IS NOT NULL;
