package org.cathori.backend.notice.application.crawling;

import lombok.Builder;
import lombok.Getter;

import java.util.List;


/**
 * 크롤러가 수집한 공지 데이터를 담는 DTO
 *
 * JsoupCrawler 학교 사이트에서 파싱한 결과를 담아 반환한다.
 * NoticeService 에서 Notice 엔티티로 변환된다.
 */
@Getter
@Builder
public class CrawledNotice {

    private String articleNo;
    private String category;
    private String title;
    private String department;
    private String postedAt;
    private String url;
    private String bodyText;
    private List<String> imageUrls;
    private String sourceType;
    private String sourceId;
}