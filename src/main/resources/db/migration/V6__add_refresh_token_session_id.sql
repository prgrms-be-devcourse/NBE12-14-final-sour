-- 동시 로그인 제한 (계정당 1기기, SCRUM-83·#49)
-- 로그인마다 세션 ID를 만들고, 그 세션에서 발급한 refresh 토큰 행에 남긴다.
-- 재발급은 행의 세션 ID가 Redis auth:session:{userId}의 현재 세션과 같을 때만 허용한다.
-- 배포 전에 만든 행은 NULL이다 (재발급하면 새 세션을 시작한다)
-- 기존 마이그레이션은 수정하지 않는다 (위키: Flyway 마이그레이션 규칙)

ALTER TABLE refresh_token ADD COLUMN session_id UUID;

COMMENT ON COLUMN refresh_token.session_id IS '로그인 세션 ID (access 토큰 sid claim과 같다). 배포 전 행은 NULL';
