package org.cathori.backend.notice.model;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    @Modifying
    @Query("UPDATE Notice n SET n.viewCount = n.viewCount + 1 WHERE n.id = :id")
    void incrementViewCount(@Param("id") Long id);

    /**
     * 주어진 articleNo 후보들 중 이미 DB에 저장된 것만 골라 반환한다.
     * sourceType/sourceId까지 함께 대조해, 학과별로 별도인 articleNo 체계가
     * 다른 학과 게시판과 우연히 같은 값을 가져도 오탐하지 않도록 한다.
     * sourceId가 null인 소스(MAIN)도 정상 비교되도록 null-safe 조건을 사용한다.
     */
    @Query("SELECT n.articleNo FROM Notice n WHERE n.sourceType = :sourceType " +
            "AND ((:sourceId IS NULL AND n.sourceId IS NULL) OR n.sourceId = :sourceId) " +
            "AND n.articleNo IN :articleNos")
    Set<String> findExistingArticleNos(@Param("sourceType") String sourceType,
                                        @Param("sourceId") String sourceId,
                                        @Param("articleNos") Collection<String> articleNos);

    @Query("SELECT n FROM Notice n WHERE n.aiSummaryStatus IN :statuses ORDER BY n.id ASC")
    List<Notice> findTop15ForSummary(@Param("statuses") List<String> statuses, Pageable pageable);

    List<Notice> findByAlertDispatchedFalse();
}
