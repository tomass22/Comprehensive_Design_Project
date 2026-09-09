package org.cathori.backend.notice.application.crawling;

import java.util.List;


/**
 * 가톨릭대 공지사항 크롤러 인터페이스
 *
 * 크롤러 구현체(CatholicNoticeCrawler)와 서비스(NoticeService) 사이의 계약을 정의한다.
 * NoticeService는 이 인터페이스만 바라보기 때문에,
 * 나중에 크롤러 구현체를 교체하거나 추가해도 NoticeService 코드는 건드리지 않아도 된다.
 *
 * 목록 조회(listCandidates)와 상세 조회(crawlDetail)를 분리한 이유:
 * articleNo는 게시 순서/최신성과 무관한 값이라 크기 비교로 "신규 여부"나
 * "더 볼 필요가 있는지"를 판단할 수 없다. 그래서 크롤러는 목록 페이지에서
 * 후보만 모두 모아 반환하고, 신규 여부 판별(DB 존재 여부 대조)은
 * NoticeService가 담당한 뒤 신규 후보에 한해서만 상세 페이지를 크롤링한다.
 */
public interface CrawlerPort {

    /**
     * 공지 목록 페이지(최대 N페이지)를 순회해 후보를 모두 수집한다.
     * DB와의 대조는 하지 않으며, 상세 본문도 크롤링하지 않는다.
     *
     * @param sourceType 출처 유형 (예: "MAIN", "DEPARTMENT")
     * @param sourceId   학과 공지의 경우 학과 코드, 메인 공지는 null
     * @return 목록 페이지에서 수집한 공지 후보 목록
     */
    List<NoticeCandidate> listCandidates(String sourceType, String sourceId);

    /**
     * 신규로 판별된 후보 하나의 상세 페이지를 크롤링해 본문/이미지를 채운다.
     *
     * @param sourceType 출처 유형
     * @param sourceId   학과 코드 또는 null
     * @param candidate  상세 크롤링 대상 후보
     * @return 상세 정보가 채워진 공지
     */
    CrawledNotice crawlDetail(String sourceType, String sourceId, NoticeCandidate candidate);
}
