
CREATE TABLE alliance_match_settlement (
                                           match_id BIGINT NOT NULL,

                                           a_score BIGINT NOT NULL,
                                           b_score BIGINT NOT NULL,

                                           a_university_1_id BIGINT NOT NULL,
                                           a_university_2_id BIGINT NOT NULL,
                                           b_university_1_id BIGINT NOT NULL,
                                           b_university_2_id BIGINT NOT NULL,

                                           a_university_1_contribution BIGINT NOT NULL,
                                           a_university_2_contribution BIGINT NOT NULL,
                                           b_university_1_contribution BIGINT NOT NULL,
                                           b_university_2_contribution BIGINT NOT NULL,

                                           status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

                                           created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                           updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

                                           CONSTRAINT pk_alliance_match_settlement
                                               PRIMARY KEY (match_id),

                                           CONSTRAINT fk_alliance_match_settlement_match
                                               FOREIGN KEY (match_id)
                                                   REFERENCES alliance_match (id),

                                           CONSTRAINT fk_settlement_a_university_1
                                               FOREIGN KEY (a_university_1_id)
                                                   REFERENCES university (id),

                                           CONSTRAINT fk_settlement_a_university_2
                                               FOREIGN KEY (a_university_2_id)
                                                   REFERENCES university (id),

                                           CONSTRAINT fk_settlement_b_university_1
                                               FOREIGN KEY (b_university_1_id)
                                                   REFERENCES university (id),

                                           CONSTRAINT fk_settlement_b_university_2
                                               FOREIGN KEY (b_university_2_id)
                                                   REFERENCES university (id),

                                           CONSTRAINT ck_settlement_scores
                                               CHECK (a_score >= 0 AND b_score >= 0),

                                           CONSTRAINT ck_settlement_contributions
                                               CHECK (
                                                   a_university_1_contribution >= 0
                                                       AND a_university_2_contribution >= 0
                                                       AND b_university_1_contribution >= 0
                                                       AND b_university_2_contribution >= 0
                                                   ),

                                           CONSTRAINT ck_settlement_status
                                               CHECK (status IN ('PENDING', 'APPLIED', 'COMPLETED'))
);

CREATE INDEX idx_alliance_match_settlement_status
    ON alliance_match_settlement (status, created_at);

CREATE TRIGGER trg_alliance_match_settlement_updated_at
    BEFORE UPDATE ON alliance_match_settlement
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
