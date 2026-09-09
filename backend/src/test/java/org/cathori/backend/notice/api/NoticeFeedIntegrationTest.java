package org.cathori.backend.notice.api;

import org.cathori.backend.IntegrationTestBase;
import org.cathori.backend.bookmark.domain.Bookmark;
import org.cathori.backend.bookmark.infra.BookmarkJpaRepository;
import org.cathori.backend.notice.application.crawling.CrawledNotice;
import org.cathori.backend.notice.infra.summarization.AiSummaryResult;
import org.cathori.backend.notice.model.Notice;
import org.cathori.backend.notice.model.NoticeRepository;
import org.cathori.backend.security.JwtUtil;
import org.cathori.backend.user.application.AuthService;
import org.cathori.backend.user.application.NotificationPort;
import org.cathori.backend.user.application.VerifiedEmailStore;
import org.cathori.backend.user.api.dto.RegisterRequest;
import org.cathori.backend.user.infra.UserJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("공지피드 통합 테스트")
class NoticeFeedIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService authService;
    @Autowired VerifiedEmailStore verifiedEmailStore;
    @Autowired UserJpaRepository userJpaRepository;
    @Autowired NoticeRepository noticeRepository;
    @Autowired BookmarkJpaRepository bookmarkJpaRepository;
    @Autowired JwtUtil jwtUtil;
    @MockitoBean NotificationPort notificationPort;

    private static final String EMAIL_A = "feedtest_a@catholic.ac.kr";
    private static final String EMAIL_B = "feedtest_b@catholic.ac.kr";
    private static final String PASSWORD = "password123!";

    private Long userAId;
    private Long userBId;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        verifiedEmailStore.markVerified(EMAIL_A);
        authService.register(new RegisterRequest(EMAIL_A, PASSWORD, "컴퓨터정보공학부", "인공지능학과", 2, "재학"));
        userAId = userJpaRepository.findByEmail(EMAIL_A).orElseThrow().getId();
        tokenA = jwtUtil.generateAccessToken(userAId);

        verifiedEmailStore.markVerified(EMAIL_B);
        authService.register(new RegisterRequest(EMAIL_B, PASSWORD, "컴퓨터정보공학부", "전공심화", 2, "재학"));
        userBId = userJpaRepository.findByEmail(EMAIL_B).orElseThrow().getId();
        tokenB = jwtUtil.generateAccessToken(userBId);
    }

    @AfterEach
    void cleanup() {
        bookmarkJpaRepository.deleteAll();
        noticeRepository.deleteAll();
        userJpaRepository.findByEmail(EMAIL_A).ifPresent(userJpaRepository::delete);
        userJpaRepository.findByEmail(EMAIL_B).ifPresent(userJpaRepository::delete);
        verifiedEmailStore.remove(EMAIL_A);
        verifiedEmailStore.remove(EMAIL_B);
    }

    // ── 픽스처 헬퍼 ─────────────────────────────────────────────────────────

    private Notice saveNotice(String sourceType, String sourceId,
                               String category, String title, LocalDate postedAt) {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo(UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .sourceType(sourceType)
                .sourceId(sourceId)
                .category(category)
                .title(title)
                .department("테스트학과")
                .postedAt(postedAt.toString())
                .url("https://test.catholic.ac.kr")
                .bodyText("")
                .imageUrls(List.of())
                .build();
        return noticeRepository.save(Notice.from(crawled));
    }

    private Notice saveNoticeWithSummary(String sourceType, String sourceId,
                                          String category, String title,
                                          LocalDate postedAt, LocalDate deadline) {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo(UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .sourceType(sourceType)
                .sourceId(sourceId)
                .category(category)
                .title(title)
                .department("테스트학과")
                .postedAt(postedAt.toString())
                .url("https://test.catholic.ac.kr")
                .bodyText("")
                .imageUrls(List.of())
                .build();
        Notice notice = Notice.from(crawled);
        String deadlineStr = deadline != null ? deadline.toString() : null;
        notice.applySummary(new AiSummaryResult(List.of("요약 문장"), deadlineStr));
        return noticeRepository.save(notice);
    }

    private Notice saveNoticeWithFailedSummary(String sourceType, String sourceId,
                                                String category, String title, LocalDate postedAt) {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo(UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .sourceType(sourceType)
                .sourceId(sourceId)
                .category(category)
                .title(title)
                .department("테스트학과")
                .postedAt(postedAt.toString())
                .url("https://test.catholic.ac.kr")
                .bodyText("")
                .imageUrls(List.of())
                .build();
        Notice notice = Notice.from(crawled);
        notice.markSummaryFailed();
        return noticeRepository.save(notice);
    }

    private List<String> extractTitles(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> titles = new ArrayList<>();
        root.get("content").forEach(item -> titles.add(item.get("title").asText()));
        return titles;
    }

    private static final LocalDate TODAY = LocalDate.now();

    // ── 테스트 케이스 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("NF-1: 인증 없이 요청 시 401 반환")
    void getFeed_unauthorized() throws Exception {
        mockMvc.perform(get("/api/notices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("NF-2: 기본 피드 — MAIN + 전공(CSIE) 공지 포함, 무관 학과 제외")
    void getFeed_case1_defaultFeed() throws Exception {
        saveNotice("MAIN", null, "장학", "MAIN 공지", TODAY);
        saveNotice("DEPARTMENT", "CSIE", "학과공지", "csie 학과 공지", TODAY);
        saveNotice("DEPARTMENT", "BUSINESS", "학과공지", "business 학과 공지", TODAY);

        MvcResult result = mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        List<String> titles = extractTitles(result);
        assertThat(titles).contains("MAIN 공지", "csie 학과 공지");
        assertThat(titles).doesNotContain("business 학과 공지");
    }

    @Test
    @DisplayName("NF-3: secondMajor=전공심화인 유저 — AI 학과 공지 제외")
    void getFeed_case1_excludeJeonGongSimhwa() throws Exception {
        saveNotice("MAIN", null, "장학", "MAIN 공지", TODAY);
        saveNotice("DEPARTMENT", "CSIE", "학과공지", "csie 학과 공지", TODAY);
        saveNotice("DEPARTMENT", "AI", "학과공지", "ai 학과 공지", TODAY);

        MvcResult result = mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn();

        List<String> titles = extractTitles(result);
        assertThat(titles).contains("MAIN 공지", "csie 학과 공지");
        assertThat(titles).doesNotContain("ai 학과 공지");
    }

    @Test
    @DisplayName("NF-4: 카테고리 필터 — 해당 카테고리 MAIN 공지만 반환")
    void getFeed_case2_categoryFilter() throws Exception {
        saveNotice("MAIN", null, "장학", "장학 공지", TODAY);
        saveNotice("MAIN", null, "취업", "취업 공지", TODAY);
        saveNotice("DEPARTMENT", "CSIE", "장학", "csie 장학 공지", TODAY);

        MvcResult result = mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("category", "장학"))
                .andExpect(status().isOk())
                .andReturn();

        List<String> titles = extractTitles(result);
        assertThat(titles).contains("장학 공지");
        assertThat(titles).doesNotContain("취업 공지", "csie 장학 공지");
    }

    @Test
    @DisplayName("NF-5: 매칭 공지 없는 카테고리 → 빈 배열")
    void getFeed_case2_noMatch_emptyContent() throws Exception {
        saveNotice("MAIN", null, "장학", "장학 공지", TODAY);

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("category", "없는카테고리"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("NF-6: 태그 필터 — 제목 LIKE 매칭")
    void getFeed_case3_tagFilter() throws Exception {
        saveNotice("MAIN", null, "장학", "장학금 신청 안내", TODAY);
        saveNotice("MAIN", null, "취업", "일반 공지", TODAY);
        saveNotice("DEPARTMENT", "CSIE", "학과공지", "장학금 혜택 안내", TODAY);

        MvcResult result = mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("tags", "장학금"))
                .andExpect(status().isOk())
                .andReturn();

        List<String> titles = extractTitles(result);
        assertThat(titles).contains("장학금 신청 안내", "장학금 혜택 안내");
        assertThat(titles).doesNotContain("일반 공지");
    }

    @Test
    @DisplayName("NF-7: 매칭 공지 없는 태그 → 빈 배열")
    void getFeed_case3_noMatch_emptyContent() throws Exception {
        saveNotice("MAIN", null, "장학", "일반 공지", TODAY);

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("tags", "없는키워드"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("NF-8: 카테고리 + 태그 동시 — 태그 매칭 공지가 카테고리 매칭 공지보다 앞에 위치")
    void getFeed_case4_unionPriority() throws Exception {
        saveNotice("MAIN", null, "장학", "장학금 공모", TODAY);           // priority 1
        saveNotice("MAIN", null, "장학", "일반 행사 안내", TODAY.minusDays(1)); // priority 2
        saveNotice("DEPARTMENT", "CSIE", "학과공지", "장학 혜택 안내", TODAY); // priority 1

        MvcResult result = mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("category", "장학")
                        .param("tags", "장학"))
                .andExpect(status().isOk())
                .andReturn();

        List<String> titles = extractTitles(result);
        assertThat(titles).contains("장학금 공모", "일반 행사 안내", "장학 혜택 안내");
        assertThat(titles.indexOf("일반 행사 안내"))
                .isGreaterThan(titles.indexOf("장학금 공모"));
    }

    @Test
    @DisplayName("NF-9: 결과가 size 초과 시 hasNext=true")
    void getFeed_pagination_hasNextTrue() throws Exception {
        saveNotice("MAIN", null, "장학", "공지1", TODAY);
        saveNotice("MAIN", null, "장학", "공지2", TODAY.minusDays(1));
        saveNotice("MAIN", null, "장학", "공지3", TODAY.minusDays(2));

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("size", "2")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    @DisplayName("NF-10: 마지막 페이지 시 hasNext=false")
    void getFeed_pagination_hasNextFalse() throws Exception {
        saveNotice("MAIN", null, "장학", "공지1", TODAY);
        saveNotice("MAIN", null, "장학", "공지2", TODAY.minusDays(1));

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("size", "5")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("NF-11: 북마크한 공지 → isBookmarked=true")
    void getFeed_bookmark_isBookmarkedTrue() throws Exception {
        Notice notice = saveNotice("MAIN", null, "장학", "공지", TODAY);
        bookmarkJpaRepository.save(Bookmark.create(userAId, notice.getId()));

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].isBookmarked").value(true));
    }

    @Test
    @DisplayName("NF-12: 북마크 없는 공지 → isBookmarked=false")
    void getFeed_bookmark_isBookmarkedFalse() throws Exception {
        saveNotice("MAIN", null, "장학", "공지", TODAY);

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].isBookmarked").value(false));
    }

    @Test
    @DisplayName("NF-13: deadlineAt 없는 공지 -> dDay 미반환")
    void getFeed_dDay_notReturned() throws Exception {
        saveNotice("MAIN", null, "장학", "공지", TODAY);

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].dDay").doesNotExist());
    }

    @Test
    @DisplayName("NF-14: deadlineAt 미래 -> dDay 없이 deadlineAt 반환")
    void getFeed_deadlineAt_future_withoutDday() throws Exception {
        LocalDate deadline = TODAY.plusDays(30);
        saveNoticeWithSummary("MAIN", null, "장학", "공지", TODAY, deadline);

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].dDay").doesNotExist())
                .andExpect(jsonPath("$.content[0].deadlineAt").value(deadline.toString()));
    }

    @Test
    @DisplayName("NF-15: deadlineAt 과거 -> dDay 없이 deadlineAt 반환")
    void getFeed_deadlineAt_past_withoutDday() throws Exception {
        LocalDate deadline = TODAY.minusDays(5);
        saveNoticeWithSummary("MAIN", null, "장학", "공지", TODAY, deadline);

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].dDay").doesNotExist())
                .andExpect(jsonPath("$.content[0].deadlineAt").value(deadline.toString()));
    }

    @Test
    @DisplayName("NF-16: aiSummaryStatus=SUCCESS → aiSummary 내용 반환")
    void getFeed_aiSummary_success() throws Exception {
        saveNoticeWithSummary("MAIN", null, "장학", "공지", TODAY, null);

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].aiSummary").isNotEmpty());
    }

    @Test
    @DisplayName("NF-17: aiSummaryStatus=PENDING/FAILED → aiSummary=null")
    void getFeed_aiSummary_nullWhenNotSuccess() throws Exception {
        // PENDING
        saveNotice("MAIN", null, "장학", "PENDING 공지", TODAY);

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].aiSummary").value(nullValue()));

        noticeRepository.deleteAll();

        // FAILED
        saveNoticeWithFailedSummary("MAIN", null, "장학", "FAILED 공지", TODAY);

        mockMvc.perform(get("/api/notices")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].aiSummary").value(nullValue()));
    }
}
