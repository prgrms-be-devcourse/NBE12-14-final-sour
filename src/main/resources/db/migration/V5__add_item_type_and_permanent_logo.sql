-- 아이템 종류(type) 추가와 로고 변경권 영구화
-- 물약(MULTIPLIER): 기여도로 사고, 정해진 시간 동안 클릭 배율이 붙는다
-- 로고 변경권(LOGO_*): 크레딧으로 사고, 영구 적용된다 (마지막에 바꾼 로고 기준)
-- 기존 마이그레이션은 수정하지 않는다 (위키: Flyway 마이그레이션 규칙)

-- 1. 아이템 종류
-- item 시드가 아직 없어서 NOT NULL로 바로 추가한다
ALTER TABLE item ADD COLUMN type VARCHAR(20) NOT NULL;

ALTER TABLE item ADD CONSTRAINT ck_item_type
    CHECK (type IN ('MULTIPLIER', 'LOGO_PROFILE', 'LOGO_DEPARTMENT', 'LOGO_UNIVERSITY'));

-- 물약은 기여도로만, 로고 변경권은 크레딧으로만 산다
ALTER TABLE item ADD CONSTRAINT ck_item_type_pay_type
    CHECK ((type = 'MULTIPLIER') = (pay_type = 'CONTRIBUTION'));

-- 로고 변경권은 대상별로 하나씩만 존재한다
CREATE UNIQUE INDEX uk_item_logo_type ON item (type) WHERE type <> 'MULTIPLIER';

COMMENT ON COLUMN item.type IS '아이템 종류 (MULTIPLIER = 클릭 배율 물약, LOGO_PROFILE·LOGO_DEPARTMENT·LOGO_UNIVERSITY = 로고 변경권)';

-- 2. 지속시간은 물약만 있다 (로고 변경권은 영구)
ALTER TABLE item ALTER COLUMN duration DROP NOT NULL;

ALTER TABLE item ADD CONSTRAINT ck_item_type_duration
    CHECK ((type = 'MULTIPLIER') = (duration IS NOT NULL));

COMMENT ON COLUMN item.duration IS '효과 지속시간 (초). 물약만 있고 로고 변경권은 NULL';

-- 3. 구매 이력의 만료 시각도 물약만 있다
ALTER TABLE user_item ALTER COLUMN expired_at DROP NOT NULL;

-- 물약 구매는 만료 시각이 있고 로고 이미지가 없다. 로고 구매는 로고 이미지가 있고 만료 시각이 없다 (영구)
ALTER TABLE user_item ADD CONSTRAINT ck_user_item_logo_or_expired
    CHECK ((logo_image_url IS NULL) = (expired_at IS NOT NULL));

COMMENT ON COLUMN user_item.expired_at IS '효과 종료 시각 (= 구매 시각 + DURATION). 로고 변경권은 영구라 NULL';