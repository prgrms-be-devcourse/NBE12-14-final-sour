-- 문의 답변 받을 이메일을 users.email처럼 AES-256-GCM 암호문으로 저장한다 (SCRUM-109, 개인정보 암호화 방침).
-- 암호문은 Base64(IV 12 + 평문 + 태그 16)이라 254자 이메일이면 약 376자다. users.email과 같은 512자로 늘린다.
-- 이 마이그레이션 전에는 문의를 저장하는 코드가 없었으므로 평문으로 남은 행은 없다고 본다.
ALTER TABLE customer_support ALTER COLUMN recipient_email TYPE VARCHAR(512);

COMMENT ON COLUMN customer_support.recipient_email IS '답변 받을 이메일. AES-256-GCM 암호문 (EncryptedEmailConverter)';
