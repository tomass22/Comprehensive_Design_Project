package org.cathori.backend.notice.api;

import org.cathori.backend.IntegrationTestBase;
import org.cathori.backend.bookmark.domain.Bookmark;
import org.cathori.backend.bookmark.infra.BookmarkJpaRepository;
import org.cathori.backend.notice.application.crawling.CrawledNotice;
import org.cathori.backend.notice.infra.ai.AiSummaryResult;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("공지 상세 조회 통합 테스트")
class NoticeDetailIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired VerifiedEmailStore verifiedEmailStore;
    @Autowired UserJpaRepository userJpaRepository;
    @Autowired NoticeRepository noticeRepository;
    @Autowired BookmarkJpaRepository bookmarkJpaRepository;
    @Autowired JwtUtil jwtUtil;
    @MockitoBean NotificationPort notificationPort;

    private static final String EMAIL = "detailtest@catholic.ac.kr";
    private static final String PASSWORD = "password123!";

    private Long userId;
    private String token;

    @BeforeEach
    void setUp() {
        verifiedEmailStore.markVerified(EMAIL);
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "컴퓨터정보공학부", null, 2, "재학"));
        userId = userJpaRepository.findByEmail(EMAIL).orElseThrow().getId();
        token = jwtUtil.generateAccessToken(userId);
    }

    @AfterEach
    void cleanup() {
        bookmarkJpaRepository.deleteAll();
        noticeRepository.deleteAll();
        userJpaRepository.findByEmail(EMAIL).ifPresent(userJpaRepository::delete);
        verifiedEmailStore.remove(EMAIL);
    }

    private Notice saveNotice(String category, String title, LocalDate postedAt) {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo(UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .sourceType("MAIN")
                .sourceId(null)
                .category(category)
                .title(title)
                .department("학생처")
                .postedAt(postedAt.toString())
                .url("https://test.catholic.ac.kr")
                .bodyText("")
                .imageUrls(List.of())
                .build();
        return noticeRepository.save(Notice.from(crawled));
    }

    private Notice saveNoticeWithSummary(LocalDate deadline) {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo(UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .sourceType("MAIN")
                .sourceId(null)
                .category("장학")
                .title("요약 있는 공지")
                .department("학생처")
                .postedAt(LocalDate.now().toString())
                .url("https://test.catholic.ac.kr")
                .bodyText("")
                .imageUrls(List.of())
                .build();
        Notice notice = Notice.from(crawled);
        String deadlineStr = deadline != null ? deadline.toString() : null;
        notice.applySummary(new AiSummaryResult(List.of("요약 문장"), deadlineStr));
        return noticeRepository.save(notice);
    }

    @Test
    @DisplayName("ND-1: 인증 없이 요청 시 401 반환")
    void getDetail_unauthorized() throws Exception {
        mockMvc.perform(get("/api/notices/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ND-2: 존재하지 않는 noticeId 조회 시 404 NOTICE_NOT_FOUND 반환")
    void getDetail_notFound() throws Exception {
        mockMvc.perform(get("/api/notices/99999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"));
    }

    @Test
    @DisplayName("ND-3: 성공 조회 시 200과 기본 필드 반환")
    void getDetail_success() throws Exception {
        Notice notice = saveNotice("장학", "테스트 공지", LocalDate.now());

        mockMvc.perform(get("/api/notices/" + notice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeId").value(notice.getId()))
                .andExpect(jsonPath("$.category").value("장학"))
                .andExpect(jsonPath("$.title").value("테스트 공지"))
                .andExpect(jsonPath("$.department").value("학생처"))
                .andExpect(jsonPath("$.publishedAt").isString())
                .andExpect(jsonPath("$.url").value("https://test.catholic.ac.kr"))
                .andExpect(jsonPath("$.aiSummaryStatus").value("PENDING"));
    }

    @Test
    @DisplayName("ND-4: 북마크한 공지 조회 시 isBookmarked=true 반환")
    void getDetail_isBookmarkedTrue() throws Exception {
        Notice notice = saveNotice("장학", "테스트 공지", LocalDate.now());
        bookmarkJpaRepository.save(Bookmark.create(userId, notice.getId()));

        mockMvc.perform(get("/api/notices/" + notice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isBookmarked").value(true));
    }

    @Test
    @DisplayName("ND-5: 북마크 안한 공지 조회 시 isBookmarked=false 반환")
    void getDetail_isBookmarkedFalse() throws Exception {
        Notice notice = saveNotice("장학", "테스트 공지", LocalDate.now());

        mockMvc.perform(get("/api/notices/" + notice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isBookmarked").value(false));
    }

    @Test
    @DisplayName("ND-6: aiSummaryStatus=SUCCESS 공지 조회 시 aiSummary 내용 반환")
    void getDetail_aiSummary_success() throws Exception {
        Notice notice = saveNoticeWithSummary(null);

        mockMvc.perform(get("/api/notices/" + notice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiSummaryStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.aiSummary").isNotEmpty());
    }

    @Test
    @DisplayName("ND-7: aiSummaryStatus=PENDING 공지 조회 시 aiSummary=null 반환")
    void getDetail_aiSummary_pending() throws Exception {
        Notice notice = saveNotice("장학", "테스트 공지", LocalDate.now());

        mockMvc.perform(get("/api/notices/" + notice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiSummaryStatus").value("PENDING"))
                .andExpect(jsonPath("$.aiSummary").value(nullValue()));
    }

    @Test
    @DisplayName("ND-8: deadlineAt 있는 공지 조회 시 dDay 없이 deadlineAt 반환")
    void getDetail_deadlineAt_returned_withoutDday() throws Exception {
        Notice futureNotice = saveNoticeWithSummary(LocalDate.now().plusDays(10));
        Notice pastNotice   = saveNoticeWithSummary(LocalDate.now().minusDays(3));

        mockMvc.perform(get("/api/notices/" + futureNotice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dDay").doesNotExist())
                .andExpect(jsonPath("$.deadlineAt").value(futureNotice.getDeadlineAt().toString()));

        mockMvc.perform(get("/api/notices/" + pastNotice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dDay").doesNotExist())
                .andExpect(jsonPath("$.deadlineAt").value(pastNotice.getDeadlineAt().toString()));
    }

    @Test
    @DisplayName("ND-9: deadlineAt 없는 공지 조회 시 dDay 미반환")
    void getDetail_dDay_notReturned() throws Exception {
        Notice notice = saveNotice("장학", "테스트 공지", LocalDate.now());

        mockMvc.perform(get("/api/notices/" + notice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dDay").doesNotExist());
    }

    @Test
    @DisplayName("ND-10: 공지 조회 시 viewCount 1 증가")
    void getDetail_incrementsViewCount() throws Exception {
        Notice notice = saveNotice("장학", "테스트 공지", LocalDate.now());

        mockMvc.perform(get("/api/notices/" + notice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        long viewCount = noticeRepository.findById(notice.getId())
                .orElseThrow()
                .getViewCount();
        assertThat(viewCount).isEqualTo(1L);
    }
}
