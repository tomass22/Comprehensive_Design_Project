package org.cathori.backend.user.api;

import org.cathori.backend.IntegrationTestBase;
import org.cathori.backend.notification.domain.AlertHistory;
import org.cathori.backend.notification.infra.AlertHistoryJpaRepository;
import org.cathori.backend.bookmark.domain.Bookmark;
import org.cathori.backend.bookmark.infra.BookmarkJpaRepository;
import org.cathori.backend.notice.application.crawling.CrawledNotice;
import org.cathori.backend.notice.model.Notice;
import org.cathori.backend.notice.model.NoticeRepository;
import org.cathori.backend.security.JwtUtil;
import org.cathori.backend.tag.domain.Tag;
import org.cathori.backend.tag.infra.TagJpaRepository;
import org.cathori.backend.user.api.dto.RegisterRequest;
import org.cathori.backend.user.application.AuthService;
import org.cathori.backend.user.application.NotificationPort;
import org.cathori.backend.user.application.VerifiedEmailStore;
import org.cathori.backend.user.domain.RefreshToken;
import org.cathori.backend.user.infra.RefreshTokenJpaRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("User integration test")
class UserIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired VerifiedEmailStore verifiedEmailStore;
    @Autowired UserJpaRepository userJpaRepository;
    @Autowired TagJpaRepository tagJpaRepository;
    @Autowired BookmarkJpaRepository bookmarkJpaRepository;
    @Autowired AlertHistoryJpaRepository alertHistoryJpaRepository;
    @Autowired RefreshTokenJpaRepository refreshTokenJpaRepository;
    @Autowired NoticeRepository noticeRepository;
    @Autowired JwtUtil jwtUtil;
    @MockitoBean NotificationPort notificationPort;

    private static final String EMAIL = "withdraw@catholic.ac.kr";
    private static final String PASSWORD = "password123!";

    private Long userId;
    private String accessToken;

    @BeforeEach
    void setUp() {
        verifiedEmailStore.markVerified(EMAIL);
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "ComputerScience", null, 2, "ENROLLED"));
        userId = userJpaRepository.findByEmail(EMAIL).orElseThrow().getId();
        accessToken = jwtUtil.generateAccessToken(userId);
    }

    @AfterEach
    void cleanup() {
        alertHistoryJpaRepository.deleteAll();
        bookmarkJpaRepository.deleteAll();
        tagJpaRepository.deleteAll();
        refreshTokenJpaRepository.deleteAll();
        noticeRepository.deleteAll();
        userJpaRepository.findByEmail(EMAIL).ifPresent(userJpaRepository::delete);
        verifiedEmailStore.remove(EMAIL);
    }

    @Test
    @DisplayName("U-1: withdraw deletes user-owned rows before deleting user")
    void withdraw_deletesUserOwnedRows() throws Exception {
        Notice notice = saveNotice();
        tagJpaRepository.save(Tag.create(userId, "backend"));
        bookmarkJpaRepository.save(Bookmark.create(userId, notice.getId()));
        alertHistoryJpaRepository.save(AlertHistory.create(userId, notice.getId(), "backend"));
        refreshTokenJpaRepository.save(RefreshToken.create(userId, "refresh-" + UUID.randomUUID(), 86_400_000));

        mockMvc.perform(delete("/api/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        assertThat(userJpaRepository.findById(userId)).isEmpty();
        assertThat(tagJpaRepository.findAllByUserId(userId)).isEmpty();
        assertThat(bookmarkJpaRepository.findAll())
                .noneMatch(bookmark -> bookmark.getUserId().equals(userId));
        assertThat(alertHistoryJpaRepository.findAll())
                .noneMatch(history -> history.getUserId().equals(userId));
        assertThat(refreshTokenJpaRepository.findAll())
                .noneMatch(refreshToken -> refreshToken.getUserId().equals(userId));
        assertThat(noticeRepository.findById(notice.getId())).isPresent();
    }

    private Notice saveNotice() {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo(UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .sourceType("MAIN")
                .sourceId(null)
                .category("GENERAL")
                .title("Test notice")
                .department("NoticeTeam")
                .postedAt(LocalDate.now().toString())
                .url("https://example.com/" + UUID.randomUUID())
                .bodyText("")
                .imageUrls(List.of())
                .build();
        return noticeRepository.save(Notice.from(crawled));
    }
}
