package org.cathori.backend.bookmark.api;

import org.cathori.backend.IntegrationTestBase;
import org.cathori.backend.bookmark.domain.Bookmark;
import org.cathori.backend.bookmark.infra.BookmarkJpaRepository;
import org.cathori.backend.notice.application.crawling.CrawledNotice;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("북마크 통합 테스트")
class BookmarkIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired VerifiedEmailStore verifiedEmailStore;
    @Autowired UserJpaRepository userJpaRepository;
    @Autowired NoticeRepository noticeRepository;
    @Autowired BookmarkJpaRepository bookmarkJpaRepository;
    @Autowired JwtUtil jwtUtil;
    @MockitoBean NotificationPort notificationPort;

    private static final String EMAIL_A = "bookmarktest_a@catholic.ac.kr";
    private static final String EMAIL_B = "bookmarktest_b@catholic.ac.kr";
    private static final String PASSWORD = "password123!";

    private Long userAId;
    private Long userBId;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        verifiedEmailStore.markVerified(EMAIL_A);
        authService.register(new RegisterRequest(EMAIL_A, PASSWORD, "컴퓨터정보공학부", null, 2, "재학"));
        userAId = userJpaRepository.findByEmail(EMAIL_A).orElseThrow().getId();
        tokenA = jwtUtil.generateAccessToken(userAId);

        verifiedEmailStore.markVerified(EMAIL_B);
        authService.register(new RegisterRequest(EMAIL_B, PASSWORD, "컴퓨터정보공학부", null, 2, "재학"));
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

    private Notice saveNotice() {
        return saveNotice("테스트 공지", LocalDate.now());
    }

    private Notice saveNotice(String title, LocalDate postedAt) {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo(UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .sourceType("MAIN")
                .sourceId(null)
                .category("장학")
                .title(title)
                .department("학생처")
                .postedAt(postedAt.toString())
                .url("https://test.catholic.ac.kr")
                .bodyText("")
                .imageUrls(List.of())
                .build();
        return noticeRepository.save(Notice.from(crawled));
    }

    @Test
    @DisplayName("B-1: 인증 없이 요청 시 401 반환")
    void toggle_unauthorized() throws Exception {
        mockMvc.perform(post("/api/notices/1/bookmark"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("B-2: 존재하지 않는 noticeId 토글 시 404 NOTICE_NOT_FOUND 반환")
    void toggle_noticeNotFound() throws Exception {
        mockMvc.perform(post("/api/notices/99999/bookmark")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"));
    }

    @Test
    @DisplayName("B-3: 북마크 없는 공지 토글 시 200, isBookmarked=true 반환 (추가)")
    void toggle_addBookmark() throws Exception {
        Notice notice = saveNotice();

        mockMvc.perform(post("/api/notices/" + notice.getId() + "/bookmark")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeId").value(notice.getId()))
                .andExpect(jsonPath("$.isBookmarked").value(true));
    }

    @Test
    @DisplayName("B-4: 이미 북마크한 공지 토글 시 200, isBookmarked=false 반환 (삭제)")
    void toggle_removeBookmark() throws Exception {
        Notice notice = saveNotice();
        bookmarkJpaRepository.save(Bookmark.create(userAId, notice.getId()));

        mockMvc.perform(post("/api/notices/" + notice.getId() + "/bookmark")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeId").value(notice.getId()))
                .andExpect(jsonPath("$.isBookmarked").value(false));
    }

    @Test
    @DisplayName("B-5: 유저A가 북마크한 공지를 유저B가 토글 시 200, isBookmarked=true 반환 (유저 간 독립)")
    void toggle_independentPerUser() throws Exception {
        Notice notice = saveNotice();
        bookmarkJpaRepository.save(Bookmark.create(userAId, notice.getId()));

        mockMvc.perform(post("/api/notices/" + notice.getId() + "/bookmark")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isBookmarked").value(true));
    }

    @Test
    @DisplayName("B-6: 북마크 목록 조회 시 로그인 사용자가 북마크한 공지만 반환")
    void listBookmarks_onlyCurrentUserBookmarks() throws Exception {
        Notice userANotice = saveNotice("유저A 북마크 공지", LocalDate.now());
        Notice userBNotice = saveNotice("유저B 북마크 공지", LocalDate.now().minusDays(1));
        bookmarkJpaRepository.save(Bookmark.create(userAId, userANotice.getId()));
        bookmarkJpaRepository.save(Bookmark.create(userBId, userBNotice.getId()));

        mockMvc.perform(get("/api/notices/bookmarks")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].noticeId").value(String.valueOf(userANotice.getId())))
                .andExpect(jsonPath("$.content[0].isBookmarked").value(true));
    }

    @Test
    @DisplayName("B-7: 북마크 목록이 없으면 빈 content 반환")
    void listBookmarks_empty() throws Exception {
        mockMvc.perform(get("/api/notices/bookmarks")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("B-8: 북마크 목록은 공지 피드 정렬 기준으로 반환")
    void listBookmarks_sortedByNoticeFeedOrder() throws Exception {
        Notice oldNotice = saveNotice("오래된 공지", LocalDate.now().minusDays(3));
        Notice recentNotice = saveNotice("최신 공지", LocalDate.now());
        bookmarkJpaRepository.save(Bookmark.create(userAId, oldNotice.getId()));
        bookmarkJpaRepository.save(Bookmark.create(userAId, recentNotice.getId()));

        mockMvc.perform(get("/api/notices/bookmarks")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].noticeId").value(String.valueOf(recentNotice.getId())))
                .andExpect(jsonPath("$.content[1].noticeId").value(String.valueOf(oldNotice.getId())));
    }
}
