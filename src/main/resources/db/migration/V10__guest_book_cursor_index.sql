-- 방명록 ID 커서 조회: WHERE university_id = ? AND id < ? ORDER BY id DESC
-- V1 마이그레이션을 수정하지 않고 새로운 인덱스만 추가한다.
CREATE INDEX idx_guest_book_university_id_desc ON guest_book (university_id, id DESC);
