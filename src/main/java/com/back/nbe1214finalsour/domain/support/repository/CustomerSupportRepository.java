package com.back.nbe1214finalsour.domain.support.repository;

import com.back.nbe1214finalsour.domain.support.dto.SupportListItem;
import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.entity.SupportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerSupportRepository extends JpaRepository<CustomerSupport, Long> {

    /**
     * 회원의 문의 목록. 최신순, 같은 시각이면 id 역순 (계획 D5). 목록에 필요한 열만 읽어 본문과 암호문 복호화를 피한다.
     * {@code idx_customer_support_user_created (user_id, created_at DESC)}를 쓴다.
     */
    @Query(value = """
            select new com.back.nbe1214finalsour.domain.support.dto.SupportListItem(
                       s.id, s.title, s.status, s.createdAt)
              from CustomerSupport s
             where s.userId = :userId
             order by s.createdAt desc, s.id desc
            """,
            countQuery = "select count(s) from CustomerSupport s where s.userId = :userId")
    Page<SupportListItem> findListByUserId(@Param("userId") Long userId, Pageable pageable);

    /** 관리자 전체 목록. 최신순, 같은 시각이면 id 역순 (계획 D5·D6) */
    Page<CustomerSupport> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /**
     * 관리자 상태별 목록. 최신순, 같은 시각이면 id 역순. {@code RECEIVED}는 부분 인덱스
     * {@code idx_customer_support_received}를 쓸 수 있다
     */
    Page<CustomerSupport> findByStatusOrderByCreatedAtDescIdDesc(SupportStatus status, Pageable pageable);

    /**
     * 접수 상태인 문의에만 답변을 넣고 {@code ANSWERED}로 바꾼다 (계획 Q5). 두 관리자가 동시에 답변해도 한 건만 1을 받는다.
     * {@code updated_at}은 트리거가 채운다.
     *
     * <p>영속성 컨텍스트를 거치지 않는 벌크 UPDATE라 앞뒤로 flush·clear 한다. 같은 트랜잭션에서 미리 읽은 엔티티가 옛 상태로
     * 남지 않게 하기 위함이다.
     *
     * @return 바뀐 행 수. 0이면 문의가 없거나 이미 답변했다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CustomerSupport s
               set s.status = com.back.nbe1214finalsour.domain.support.entity.SupportStatus.ANSWERED,
                   s.answerTitle = :answerTitle,
                   s.answerContent = :answerContent
             where s.id = :id
               and s.status = com.back.nbe1214finalsour.domain.support.entity.SupportStatus.RECEIVED
            """)
    int answer(@Param("id") Long id, @Param("answerTitle") String answerTitle,
               @Param("answerContent") String answerContent);
}
