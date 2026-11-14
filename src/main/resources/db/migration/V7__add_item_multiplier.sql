-- 물약 배율. 결정 3(배율은 Redis에 정의)을 바꿔 가격·지속 시간처럼 아이템 속성으로 저장
ALTER TABLE item ADD COLUMN multiplier DOUBLE PRECISION;

-- 이미 들어 있는 물약 행(개발용 로컬 DB 등)에 기본 배율을 채워, 아래 제약 추가가 실패하지 않게 함
UPDATE item SET multiplier = 2.0 WHERE type = 'MULTIPLIER';

-- 물약(MULTIPLIER)만 배율이 있고, 로고 변경권은 NULL (V5의 duration 규칙과 같은 방식)
ALTER TABLE item ADD CONSTRAINT ck_item_type_multiplier CHECK ((type = 'MULTIPLIER') = (multiplier IS NOT NULL));

-- 배율이 1 이하면 효과가 없거나 오히려 손해
ALTER TABLE item ADD CONSTRAINT ck_item_multiplier CHECK (multiplier > 1);

COMMENT ON COLUMN item.multiplier IS '클릭 점수 배율. 물약(MULTIPLIER)만 있고 로고 변경권은 NULL';