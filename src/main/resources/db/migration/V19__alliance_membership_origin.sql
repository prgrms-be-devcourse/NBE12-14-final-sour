-- 기존 가입 목록에서 사용하던 창립 판별 기준을 그대로 이관한다.
ALTER TABLE alliance_university ADD COLUMN origin VARCHAR(20);

UPDATE alliance_university
SET origin = CASE
    WHEN approver_user_id = applicant_user_id THEN 'FOUNDER'
    ELSE 'APPLICATION'
END;

ALTER TABLE alliance_university ALTER COLUMN origin SET DEFAULT 'APPLICATION';
ALTER TABLE alliance_university ALTER COLUMN origin SET NOT NULL;
ALTER TABLE alliance_university ADD CONSTRAINT ck_alliance_university_origin
    CHECK (origin IN ('FOUNDER', 'APPLICATION', 'INVITATION'));
