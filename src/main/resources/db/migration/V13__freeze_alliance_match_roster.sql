-- Freeze the four schools in the same transaction as the start of a new match.
-- Existing matches are left without a roster: their original members cannot be reconstructed reliably.
ALTER TABLE alliance_match
    ADD COLUMN a_university_1_id BIGINT REFERENCES university (id),
    ADD COLUMN a_university_2_id BIGINT REFERENCES university (id),
    ADD COLUMN b_university_1_id BIGINT REFERENCES university (id),
    ADD COLUMN b_university_2_id BIGINT REFERENCES university (id),
    ADD CONSTRAINT ck_alliance_match_roster CHECK (
        num_nonnulls(a_university_1_id, a_university_2_id, b_university_1_id, b_university_2_id) = 0
        OR (
            num_nonnulls(a_university_1_id, a_university_2_id, b_university_1_id, b_university_2_id) = 4
            AND status IN ('IN_PROGRESS', 'FINISHED')
            AND a_university_1_id <> a_university_2_id
            AND a_university_1_id <> b_university_1_id
            AND a_university_1_id <> b_university_2_id
            AND a_university_2_id <> b_university_1_id
            AND a_university_2_id <> b_university_2_id
            AND b_university_1_id <> b_university_2_id
        )
    );
