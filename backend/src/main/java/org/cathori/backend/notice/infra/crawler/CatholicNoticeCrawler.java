package org.cathori.backend.notice.infra.crawler;

import lombok.extern.slf4j.Slf4j;
import org.cathori.backend.notice.application.CrawledNotice;
import org.cathori.backend.notice.application.CrawlerPort;
import org.cathori.backend.notice.application.NoticeCandidate;
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

    private static final int MAX_LIST_PAGES = 3;
    private static final int ARTICLES_PER_PAGE = 10;

    /**
     * 가톨릭대 공지사항 목록 페이지를 크롤링해 공지 후보를 모두 수집한다.
     * articleNo는 게시 순서/최신성과 무관한 값이라 크기 비교로 신규 여부나
     * 조기 종료 여부를 판단할 수 없으므로, 매번 최대 {@value #MAX_LIST_PAGES}페이지를
     * 전부 순회해 후보를 모은다. 신규 여부 판별은 NoticeService가 DB와 대조해 담당한다.
     *
     * @param sourceType 공지 출처 유형 (예: "MAIN")
     * @param sourceId   학과 공지의 경우 학과 코드, 메인 공지는 null
     * @return 목록 페이지에서 수집한 공지 후보 목록 (중복 articleNo 제거됨)
     */
    @Override
    public List<NoticeCandidate> listCandidates(String sourceType, String sourceId) {
        String targetUrl = getCrawlTargetUrl(sourceType, sourceId);
        List<NoticeCandidate> result = new ArrayList<>();
        Set<String> seenArticleNo = new HashSet<>();

        for (int page = 1; page <= MAX_LIST_PAGES; page++) {
            Elements rows = fetchListRows(targetUrl, sourceType, sourceId, page);
            if (rows == null) break;

            collectCandidatesFromRows(rows, targetUrl, seenArticleNo, result);
        }

        return result;
    }

    /**
     * 신규로 판별된 후보 하나의 상세 페이지를 크롤링해 본문/이미지를 채운다.
     *
     * @param sourceType 공지 출처 유형
     * @param sourceId   학과 코드 또는 null
     * @param candidate  상세 크롤링 대상 후보
     * @return 상세 정보가 채워진 공지
     */
    @Override
    public CrawledNotice crawlDetail(String sourceType, String sourceId, NoticeCandidate candidate) {
        NoticeDetails detail = crawlNoticeDetail(candidate.getDetailUrl());

        log.debug("본문 수집 - articleNo: {}, 본문길이: {}, 이미지수: {}",
                candidate.getArticleNo(), detail.bodyText().length(), detail.imageUrls().size());

        return CrawledNotice.builder()
                .articleNo(candidate.getArticleNo())
                .category(candidate.getCategory())
                .title(candidate.getTitle())
                .department(candidate.getDepartment())
                .postedAt(candidate.getPostedAt())
                .url(candidate.getDetailUrl())
                .bodyText(detail.bodyText())
                .imageUrls(detail.imageUrls())
                .sourceType(sourceType)
                .sourceId(sourceId)
                .build();
    }

    /**
     * 공지 목록 페이지 하나를 요청해 행(tr) 목록을 반환한다.
     * page가 1이면 기존과 동일하게 targetUrl 그대로 요청하고,
     * 그 이상이면 article.offset 파라미터를 붙여 다음 페이지를 요청한다.
     * 요청 실패 시 null 반환.
     *
     * @param targetUrl  공지 목록 페이지 기본 URL
     * @param sourceType 공지 출처 유형
     * @param sourceId   학과 코드 또는 null
     * @param page       조회할 페이지 번호 (1부터 시작)
     * @return tr 요소 목록. 요청 실패 시 null
     */
    private Elements fetchListRows(String targetUrl, String sourceType, String sourceId, int page) {
        String pageUrl = page == 1 ? targetUrl : buildPageUrl(targetUrl, page);

        try {
            Document targetPageDoc = Jsoup.connect(pageUrl).get();
            log.info("HTML 수신 성공 - sourceType: {}, sourceId: {}, page: {}, 길이: {}",
                    sourceType, sourceId, page, targetPageDoc.html().length());
            return targetPageDoc.select("table tbody tr");
        } catch (IOException e) {
            log.warn("크롤링 연결 실패 (page={}): {}", page, e.getMessage());
            return null;
        }
    }

    /**
     * 목록 페이지의 offset 기반 페이지네이션 URL을 만든다.
     * 예: page=2 -> "...notice.do?mode=list&articleLimit=10&article.offset=10"
     *
     * @param targetUrl 공지 목록 페이지 기본 URL
     * @param page      조회할 페이지 번호 (1부터 시작)
     * @return 페이지네이션 쿼리가 포함된 URL
     */
    private String buildPageUrl(String targetUrl, int page) {
        int offset = (page - 1) * ARTICLES_PER_PAGE;
        return targetUrl + "?mode=list&articleLimit=" + ARTICLES_PER_PAGE + "&article.offset=" + offset;
    }

    /**
     * 목록 페이지의 tr 행들을 파싱해 공지 후보를 result에 추가한다.
     * 이미 같은 크롤링 실행 안에서 본 articleNo(예: 상단 고정 공지 중복 노출)는 건너뛴다.
     */
    private void collectCandidatesFromRows(Elements rows, String targetUrl,
                                            Set<String> seenArticleNo, List<NoticeCandidate> result) {
        for (Element row : rows) {
            try {
                NoticeRow parsed = parseNoticeRow(row, targetUrl);
                if (parsed == null) continue;
                if (!seenArticleNo.add(parsed.articleNo())) continue;

                result.add(NoticeCandidate.builder()
                        .articleNo(parsed.articleNo())
                        .category(parsed.category())
                        .title(parsed.title())
                        .department(parsed.department())
                        .postedAt(parsed.postedAt())
                        .detailUrl(parsed.noticeDetailsUrl())
                        .build());
            } catch (Exception e) {
                log.warn("공지 파싱 실패 (스킵): {}", e.getMessage());
            }
        }
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
