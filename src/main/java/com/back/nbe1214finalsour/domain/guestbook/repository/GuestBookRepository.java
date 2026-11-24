package com.back.nbe1214finalsour.domain.guestbook.repository;

import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookListRow;
import com.back.nbe1214finalsour.domain.guestbook.entity.GuestBook;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuestBookRepository extends JpaRepository<GuestBook, Long> {

    /** 중복 좋아요 검사, INSERT, 카운트 증가를 같은 글 단위로 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GuestBook g where g.id = :id")
    Optional<GuestBook> findByIdForUpdate(@Param("id") Long id);

    /** 첫 페이지. 작성자의 학교를 표시하며 이메일과 비밀번호는 조회하지 않는다. */
    @Query("""
            select new com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookListRow(
                g.id, g.content, g.likeCount, author.nickname, authorUniversity.name, g.createdAt)
            from GuestBook g
            join User author on author.id = g.userId
            join University authorUniversity on authorUniversity.id = author.universityId
            where g.universityId = :universityId
            order by g.id desc
            """)
    List<GuestBookListRow> findFirstPage(@Param("universityId") Long universityId, Pageable pageable);

    /** cursor ID 이전 글만 가져온다. size+1개로 다음 페이지 존재 여부를 판단한다. */
    @Query("""
            select new com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookListRow(
                g.id, g.content, g.likeCount, author.nickname, authorUniversity.name, g.createdAt)
            from GuestBook g
            join User author on author.id = g.userId
            join University authorUniversity on authorUniversity.id = author.universityId
            where g.universityId = :universityId and g.id < :cursor
            order by g.id desc
            """)
    List<GuestBookListRow> findNextPage(@Param("universityId") Long universityId,
                                        @Param("cursor") Long cursor, Pageable pageable);

    /** 로그인 사용자의 좋아요를 IN 쿼리 1회로 조회한다 (N+1 방지). */
    @Query(value = """
            select guest_book_id from guest_book_like
            where user_id = :userId and guest_book_id in (:guestBookIds)
            """, nativeQuery = true)
    List<Long> findLikedGuestBookIds(@Param("userId") Long userId,
                                     @Param("guestBookIds") List<Long> guestBookIds);
}
