-- 클리커 레벨별 클릭 세기를 레벨 × 10으로 바꾼다
-- V1(1레벨)과 V16(2~10레벨)이 클릭 세기 = 레벨로 넣은 값을 덮어쓴다. 기존 마이그레이션은 수정하지 않는다
UPDATE clicker SET click_power = 10 WHERE id = 1;
UPDATE clicker SET click_power = 20 WHERE id = 2;
UPDATE clicker SET click_power = 30 WHERE id = 3;
UPDATE clicker SET click_power = 40 WHERE id = 4;
UPDATE clicker SET click_power = 50 WHERE id = 5;
UPDATE clicker SET click_power = 60 WHERE id = 6;
UPDATE clicker SET click_power = 70 WHERE id = 7;
UPDATE clicker SET click_power = 80 WHERE id = 8;
UPDATE clicker SET click_power = 90 WHERE id = 9;
UPDATE clicker SET click_power = 100 WHERE id = 10;