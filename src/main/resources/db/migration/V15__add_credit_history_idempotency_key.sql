ALTER TABLE credit_history ADD COLUMN idempotency_key UUID;
ALTER TABLE credit_history
    ADD CONSTRAINT uk_credit_history_user_idempotency_key UNIQUE (user_id, idempotency_key);
COMMENT ON COLUMN credit_history.idempotency_key IS '구매 요청의 Idempotency-Key. 크레딧 구매(USE)만 채운다. 같은 회원의 같은 키는 한 번만 허용. 충전(CHARGE)은 NULL';
