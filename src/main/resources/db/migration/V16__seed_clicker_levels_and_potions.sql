-- 클리커 레벨표와 물약 시드
-- 밸런스 기준: 1분 200클릭, 클릭 세기 = 레벨 (1레벨은 V1 시드 (1, 1, 0) 그대로)
-- 기존 마이그레이션은 수정하지 않는다 (위키: Flyway 마이그레이션 규칙)

-- 1. 클리커 레벨표
-- id = 레벨 (IDENTITY 아님), required_score = 이 레벨로 올릴 때 차감하는 기여도
INSERT INTO clicker (id, click_power, required_score) VALUES
                                                          (2, 2, 600),
                                                          (3, 3, 2400),
                                                          (4, 4, 6000),
                                                          (5, 5, 12000),
                                                          (6, 6, 25000),
                                                          (7, 7, 42000),
                                                          (8, 8, 70000),
                                                          (9, 9, 96000),
                                                          (10, 10, 162000);

-- 2. 물약 (기여도로 사고, duration초 동안 클릭 점수에 multiplier배)
-- id는 IDENTITY에 맡긴다. 직접 넣으면 시퀀스가 안 올라가 id 없이 넣는 이후 INSERT가 PK 충돌한다
INSERT INTO item (name, content, type, pay_type, price, duration, multiplier) VALUES
                                                                                  ('아메리카노', '1분 동안 클릭 점수 1.5배', 'MULTIPLIER', 'CONTRIBUTION', 250, 60, 1.5),
                                                                                  ('에너지 드링크', '3분 동안 클릭 점수 2배', 'MULTIPLIER', 'CONTRIBUTION', 1500, 180, 2.0),
                                                                                  ('밤샘 각성제', '5분 동안 클릭 점수 3배', 'MULTIPLIER', 'CONTRIBUTION', 5000, 300, 3.0);