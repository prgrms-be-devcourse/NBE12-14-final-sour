-- 운영팀용 숨김 학교 "와플대학교"와 학과 "운영팀" (SCRUM-235, GitHub #142)
-- 개발자 공용 계정(SCRUM-237)이 소속될 학교다. 숨김 학교는 순위·지도·검색에 보이지 않게 한다 (SCRUM-236).
-- uni_domain은 넣지 않는다: 일반 회원은 이 학교로 가입할 수 없고, 개발자 계정은 앱이 시작될 때 만든다.
-- 학교 캐시(UniversityCache)가 서버 시작 시 한 번 읽으므로, 앱 코드가 아니라 마이그레이션으로 넣는다.
-- 주의: 이미 적용한 Flyway 파일은 수정 금지. V15를 쓰는 작업보다 먼저 병합한다.

ALTER TABLE university ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN university.hidden IS '운영용 숨김 학교 여부. true면 순위·지도·검색에 보이지 않는다';

INSERT INTO university (name, hidden) VALUES ('와플대학교', true);

INSERT INTO department (university_id, name)
SELECT id, '운영팀' FROM university WHERE name = '와플대학교';
