package org.cathori.backend.notification.api;

import org.cathori.backend.IntegrationTestBase;
import org.cathori.backend.notification.infra.AlertHistoryJpaRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("알림 이력 API 통합 테스트")
class NotificationIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired VerifiedEmailStore verifiedEmailStore;
    @Autowired UserJpaRepository userJpaRepository;
    @Autowired AlertHistoryJpaRepository alertHistoryJpaRepository;
    @Autowired NoticeRepository noticeRepository;
    @Autowired JwtUtil jwtUtil;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean NotificationPort notificationPort;

    private static final String EMAIL_A = "notif_a@catholic.ac.kr";
    private static final String EMAIL_B = "notif_b@catholic.ac.kr";
    private static final String PASSWORD = "password123!";

    private Long userAId;
    private Long userBId;
    private String tokenA;

    @BeforeEach
    void setUp() {
        verifiedEmailStore.markVerified(EMAIL_A);
        authService.register(new RegisterRequest(EMAIL_A, PASSWORD, "컴퓨터정보공학부", null, 2, "재학"));
        userAId = userJpaRepository.findByEmail(EMAIL_A).orElseThrow().getId();
        tokenA = jwtUtil.generateAccessToken(userAId);

        verifiedEmailStore.markVerified(EMAIL_B);
        authService.register(new RegisterRequest(EMAIL_B, PASSWORD, "컴퓨터정보공학부", null, 2, "재학"));
        userBId = userJpaRepository.findByEmail(EMAIL_B).orElseThrow().getId();
    }

    @AfterEach
    void cleanup() {
        alertHistoryJpaRepository.deleteAll();
        noticeRepository.deleteAll();
        userJpaRepository.findByEmail(EMAIL_A).ifPresent(userJpaRepository::delete);
        userJpaRepository.findByEmail(EMAIL_B).ifPresent(userJpaRepository::delete);
        verifiedEmailStore.remove(EMAIL_A);
        verifiedEmailStore.remove(EMAIL_B);
    }

    @Test
    @DisplayName("NI-1: GET /api/notifications → 200 + 응답 구조 확인")
    void listNotifications_returns200WithStructure() throws Exception {
        Notice notice = saveNotice();
        insertAlertHistory(userAId, notice.getId(), "SUCCESS", "장학");

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts").isArray())
                .andExpect(jsonPath("$.alerts[0].alertHistoryId").isNumber())
                .andExpect(jsonPath("$.alerts[0].noticeId").isNumber())
                .andExpect(jsonPath("$.alerts[0].title").isString())
                .andExpect(jsonPath("$.alerts[0].matchedTag").value("장학"))
                .andExpect(jsonPath("$.alerts[0].isRead").value(false))
                .andExpect(jsonPath("$.alerts[0].createdAt").value(containsString("+09:00")))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("NI-2: GET /api/notifications?size=1 → 데이터 2개일 때 hasNext=true")
    void listNotifications_withSmallSize_returnsHasNextTrue() throws Exception {
        Notice n1 = saveNotice();
        Notice n2 = saveNotice();
        insertAlertHistory(userAId, n1.getId(), "SUCCESS", null);
        insertAlertHistory(userAId, n2.getId(), "SUCCESS", null);

        mockMvc.perform(get("/api/notifications?size=1")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").isNumber());
    }

    @Test
    @DisplayName("NI-3: GET /api/notifications JWT 없음 → 401")
    void listNotifications_withoutJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("NI-4: PATCH /{id}/read → 204 No Content")
    void markRead_validAlert_returns204() throws Exception {
        Notice notice = saveNotice();
        Long historyId = insertAlertHistory(userAId, notice.getId(), "SUCCESS", null);

        mockMvc.perform(patch("/api/notifications/" + historyId + "/read")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("NI-5: PATCH /{id}/read 재요청 → 204 (멱등성)")
    void markRead_alreadyRead_returns204Again() throws Exception {
        Notice notice = saveNotice();
        Long historyId = insertAlertHistory(userAId, notice.getId(), "SUCCESS", null);

        mockMvc.perform(patch("/api/notifications/" + historyId + "/read")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/notifications/" + historyId + "/read")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("NI-6: PATCH /{없는id}/read → 404 + ALERT_NOT_FOUND")
    void markRead_notExistingId_returns404() throws Exception {
        mockMvc.perform(patch("/api/notifications/99999/read")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ALERT_NOT_FOUND"));
    }

    @Test
    @DisplayName("NI-7: PATCH /{타인id}/read → 404")
    void markRead_othersAlert_returns404() throws Exception {
        Notice notice = saveNotice();
        Long historyId = insertAlertHistory(userBId, notice.getId(), "SUCCESS", null);

        mockMvc.perform(patch("/api/notifications/" + historyId + "/read")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("NI-8: PATCH JWT 없음 → 401")
    void markRead_withoutJwt_returns401() throws Exception {
        mockMvc.perform(patch("/api/notifications/1/read"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("NI-9: DELETE /{id} → 204 No Content")
    void deleteNotification_validAlert_returns204() throws Exception {
        Notice notice = saveNotice();
        Long historyId = insertAlertHistory(userAId, notice.getId(), "SUCCESS", null);

        mockMvc.perform(delete("/api/notifications/" + historyId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("NI-10: DELETE /{id} → 내 알림 삭제")
    void deleteNotification_validAlert_deletesAlert() throws Exception {
        Notice notice = saveNotice();
        Long historyId = insertAlertHistory(userAId, notice.getId(), "SUCCESS", null);

        mockMvc.perform(delete("/api/notifications/" + historyId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts").isEmpty());
    }

    @Test
    @DisplayName("NI-11: DELETE /{없는id} → 404 + ALERT_NOT_FOUND")
    void deleteNotification_notExistingId_returns404() throws Exception {
        mockMvc.perform(delete("/api/notifications/99999")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ALERT_NOT_FOUND"));
    }

    @Test
    @DisplayName("NI-12: DELETE /{타인id} → 404")
    void deleteNotification_othersAlert_returns404() throws Exception {
        Notice notice = saveNotice();
        Long historyId = insertAlertHistory(userBId, notice.getId(), "SUCCESS", null);

        mockMvc.perform(delete("/api/notifications/" + historyId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("NI-13: DELETE JWT 없음 → 401")
    void deleteNotification_withoutJwt_returns401() throws Exception {
        mockMvc.perform(delete("/api/notifications/1"))
                .andExpect(status().isUnauthorized());
    }

    private Notice saveNotice() {
        return noticeRepository.save(Notice.from(CrawledNotice.builder()
                .articleNo(UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .sourceType("MAIN").sourceId(null).category("일반")
                .title("테스트 공지").department("공지사항")
                .postedAt(LocalDate.now().toString())
                .url("https://example.com/" + UUID.randomUUID())
                .bodyText("").imageUrls(List.of()).build()));
    }

    private Long insertAlertHistory(Long userId, Long noticeId, String status, String matchedTag) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO alert_history (user_id, notice_id, alarm_status, is_read, retry_count, created_at, matched_tag) " +
                "VALUES (?, ?, ?, false, 0, now(), ?) RETURNING id",
                Long.class, userId, noticeId, status, matchedTag);
    }
}
