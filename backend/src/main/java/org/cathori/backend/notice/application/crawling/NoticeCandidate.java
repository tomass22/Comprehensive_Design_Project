package org.cathori.backend.notice.application.crawling;

import lombok.Builder;
import lombok.Getter;

/**
 * 목록 페이지 크롤링만으로 얻어지는, 상세 크롤링 이전 단계의 공지 후보.
 *
 * NoticeService가 이 후보의 articleNo를 DB와 대조해 신규 여부를 판별한 뒤,
 * 신규인 것만 CrawlerPort.crawlDetail()로 넘겨 상세 페이지를 크롤링한다.
 */
@Getter
@Builder
public class NoticeCandidate {

    private String articleNo;
    private String category;
    private String title;
    private String department;
    private String postedAt;
    private String detailUrl;
}
