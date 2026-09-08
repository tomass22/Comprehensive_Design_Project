package org.cathori.backend.notice.infra.crawler;

import lombok.extern.slf4j.Slf4j;
import org.cathori.backend.notice.application.CrawledNotice;
import org.cathori.backend.notice.application.CrawlerPort;
import org.cathori.backend.notice.infra.crawler.format.NoticeDetails;
import org.cathori.backend.notice.infra.crawler.format.NoticeRow;
import org.cathori.backend.notice.infra.crawler.source.DepartmentSource;
import org.cathori.backend.notice.infra.crawler.source.MainSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Slf4j
@Component
public class CatholicNoticeCrawler implements CrawlerPort {


    /**
     * 가톨릭대 공지사항 목록 페이지를 크롤링해 새 공지를 수집한다.
     *
     * @param sourceType    공지 출처 유형 (예: "MAIN")
     * @param sourceId      학과 공지의 경우 학과 코드, 메인 공지는 null
     * @param lastArticleNo DB에 저장된 가장 최신 articleNo. 이 값 이하의 공지는 수집하지 않는다.
     * @return 새로 수집된 공지 목록
     */
    @Override
    public List<CrawledNotice> crawl(String sourceType, String sourceId, int lastArticleNo) {
        String targetUrl = getCrawlTargetUrl(sourceType, sourceId);
        List<CrawledNotice> result = new ArrayList<>();

        Document targetPageDoc;
        try {
            targetPageDoc = Jsoup.connect(targetUrl).get();
            log.info("HTML 수신 성공 - sourceType: {}, sourceId: {}, 길이: {}", sourceType, sourceId, targetPageDoc.html().length());
        } catch (IOException e) {
            log.warn("크롤링 연결 실패: {}", e.getMessage());
            return result;
        }

        Elements rows = targetPageDoc.select("table tbody tr");
        Set<String> seenArticleNo = new HashSet<>();

        for (Element row : rows) {
            try {
                NoticeRow parsed = parseNoticeRow(row, targetUrl);
                if (parsed == null) continue;

                int articleNo = Integer.parseInt(parsed.articleNo());
                if (lastArticleNo > 0 && articleNo <= lastArticleNo) continue;
                if (!seenArticleNo.add(parsed.articleNo())) continue;

                NoticeDetails detail = crawlNoticeDetail(parsed.noticeDetailsUrl());
                result.add(CrawledNotice.builder()
                        .articleNo(parsed.articleNo())
                        .category(parsed.category())
                        .title(parsed.title())
                        .department(parsed.department())
                        .postedAt(parsed.postedAt())
                        .url(parsed.noticeDetailsUrl())
                        .bodyText(detail.bodyText())
                        .imageUrls(detail.imageUrls())
                        .sourceType(sourceType)
                        .sourceId(sourceId)
                        .build());

                log.debug("본문 수집 - articleNo: {}, 본문길이: {}, 이미지수: {}",
                        parsed.articleNo(), detail.bodyText().length(), detail.imageUrls().size());
            } catch (Exception e) {
                log.warn("공지 파싱 실패 (스킵): {}", e.getMessage());
            }
        }

        return result;
    }
    /**
     * tr 한 행을 파싱해 NoticeRow로 변환한다.
     * a.b-title이 없으면 null 반환 (공지 행이 아닌 경우 skip)
     * articleNo 추출 실패 시 null 반환
     *
     * @param row       파싱할 tr 요소
     * @param targetUrl 공지 목록 페이지 URL (상세 URL 조합에 사용)
     * @return 파싱된 NoticeRow. 유효하지 않은 행이면 null
     */
    private NoticeRow parseNoticeRow(Element row, String targetUrl) {
        Element titleLink = row.selectFirst("a.b-title");
        if (titleLink == null) return null;

        String href = titleLink.attr("href");
        String articleNo = extractQueryParam(href, "articleNo");
        if (articleNo == null) return null;

        String category = firstNonEmpty(row, "td.b-cate", "span.b-cate");
        String title = titleLink.text().trim();
        String department = extractText(row, "span.b-writer");
        String postedAt = extractText(row, "span.b-date").replace(".", "-");
        String noticeDetailsUrl = targetUrl + href;

        return new NoticeRow(articleNo, category, title, department, postedAt, noticeDetailsUrl);
    }

    /**
     * 공지 상세 페이지에 접근해 본문 텍스트와 이미지 URL을 수집한다.
     * 접근 실패 시 빈 본문과 빈 이미지 목록을 담은 NoticeDetails 반환.
     *
     * @param url 공지 상세 페이지 URL
     * @return 본문 텍스트와 이미지 URL 목록을 담은 NoticeDetails
     */
    private NoticeDetails crawlNoticeDetail(String url) {
        try {
            Document doc = Jsoup.connect(url).get();

            String bodyText = doc.select("div.fr-view p").stream()
                    .map(Element::text)
                    .filter(t -> !t.isBlank())
                    .collect(Collectors.joining("\n"));

            List<String> imageUrls = doc.select("div.fr-view img").stream()
                    .map(img -> img.attr("src"))
                    .filter(src -> !src.isBlank())
                    .map(src -> src.startsWith("/") ? "https://www.catholic.ac.kr" + src : src)
                    .collect(Collectors.toList());

            return new NoticeDetails(bodyText, imageUrls);

        } catch (IOException e) {
            log.warn("상세 페이지 크롤링 실패 (url={}): {}", url, e.getMessage());
            return new NoticeDetails("", List.of());
        }
    }

    /**
     * sourceType에 따라 크롤링 대상 URL을 반환한다.
     * DEPARTMENT면 DepartmentSource에서 해당 학과 URL을 조회하고,
     * MAIN이면 메인 공지사항 목록 URL을 반환한다.
     *
     * @param sourceType 공지 출처 유형 ("MAIN" / "DEPARTMENT")
     * @param sourceId   학과 공지의 경우 DepartmentSource enum 상수명, 메인 공지는 null
     * @return 크롤링 대상 URL
     */
    private String getCrawlTargetUrl(String sourceType, String sourceId) {
        if ("DEPARTMENT".equals(sourceType)) {
            return DepartmentSource.valueOf(sourceId).getUrl();
        }
        return MainSource.MAIN.getUrl();
    }


    /**
     * 공지 href에서 articleNo 값을 추출할 때 사용한다.
     * 예: "?articleNo=269669&mode=view&articleLimit=10" → "269669"
     *
     * 범용 구현이라 articleNo 외 다른 파라미터도 추출 가능하다.
     *
     * @param url  쿼리 파라미터가 포함된 URL (공지 href)
     * @param name 추출할 파라미터 이름 (현재는 "articleNo"만 사용)
     * @return 파라미터 값. 없으면 null
     */
    private String extractQueryParam(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String kv : url.substring(q + 1).split("&")) {
            String[] parts = kv.split("=", 2);
            if (parts.length == 2 && name.equals(parts[0])) return parts[1];
        }
        return null;
    }

    /**
     * 주어진 selector 목록 중 텍스트가 있는 첫 번째 요소의 텍스트를 반환한다.
     * 모두 비어있으면 빈 문자열 반환.
     *
     * @param parent    탐색 기준 요소
     * @param selectors 순서대로 시도할 CSS selector 목록
     * @return 찾은 텍스트 또는 빈 문자열
     */
    private String firstNonEmpty(Element parent, String... selectors) {
        for (String sel : selectors) {
            Element el = parent.selectFirst(sel);
            if (el != null && !el.text().isBlank()) return el.text().trim();
        }
        return "";
    }

    /**
     * 들어온 parent 태그 안에서, selector 조건에 맞는 첫 번째 태그를 찾아서, 그 태그의 텍스트를 반환하는 함수
     * 요소가 없으면 빈 문자열 반환.
     *<br>
     * 예시:
     *   parent = <tr>...<span class="b-writer">교수학습개발원</span>...</tr>
     *   selector = "span.b-writer"
     *   반환값 = "교수학습개발원"
     **<br>
     * @param parent   탐색 기준 요소
     * @param selector CSS selector
     * @return 찾은 텍스트 또는 빈 문자열
     */
    private String extractText(Element parent, String selector) {
        Element el = parent.selectFirst(selector);
        return el != null ? el.text().trim() : "";
    }
    

}
