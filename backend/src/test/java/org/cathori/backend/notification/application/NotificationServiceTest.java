package org.cathori.backend.notification.application;

import org.cathori.backend.IntegrationTestBase;
import org.cathori.backend.notification.AlertErrorCode;
import org.cathori.backend.notification.api.dto.NotificationListResponse;
import org.cathori.backend.notification.application.inbox.NotificationService;
import org.cathori.backend.notification.infra.AlertHistoryJpaRepository;
import org.cathori.backend.common.exception.BusinessException;
import org.cathori.backend.notice.application.crawling.CrawledNotice;
import org.cathori.backend.notice.model.Notice;
import org.cathori.backend.notice.model.NoticeRepository;
import org.cathori.backend.notification.application.push.PushNotificationPort;
import org.cathori.backend.user.application.NotificationPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NotificationService 통합 테스트")
class NotificationServiceTest extends IntegrationTestBase {

    @Autowired
    NotificationService notificationService;
    @Autowired AlertHistoryJpaRepository alertHistoryJpaRepository;
    @Autowired NoticeRepository noticeRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean
    PushNotificationPort pushNotificationPort;
    @MockitoBean NotificationPort notificationPort;

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;

    @AfterEach
    void cleanup() {
        alertHistoryJpaRepository.deleteAll();
        noticeRepository.deleteAll();
    }

    // --- listNotifications ---

    @Test
    @DisplayName("NS-1: cursor null → 첫 페이지 최신순 반환")
    void listNotifications_noCursor_returnsFirstPage() {
        Notice notice = saveNotice("장학금 안내");
        insertAlertHistory(USER_ID, notice.getId(), "SUCCESS", "장학");

        NotificationListResponse response = notificationService.listNotifications(USER_ID, null, 20);

        assertThat(response.alerts()).hasSize(1);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("NS-2: cursor 있음 → cursor 미만 결과만 포함")
    void listNotifications_withCursor_returnsOnlyBelowCursor() {
        Notice n1 = saveNotice("공지1");
        Notice n2 = saveNotice("공지2");
        Long id1 = insertAlertHistory(USER_ID, n1.getId(), "SUCCESS", null);
        Long id2 = insertAlertHistory(USER_ID, n2.getId(), "SUCCESS", null);

        NotificationListResponse response = notificationService.listNotifications(USER_ID, id2, 20);

        assertThat(response.alerts()).hasSize(1);
        assertThat(response.alerts().getFirst().alertHistoryId()).isEqualTo(id1);
    }

    @Test
    @DisplayName("NS-3: size+1번째 존재 → hasNext=true, nextCursor 설정")
    void listNotifications_hasMoreData_returnsHasNextTrue() {
        Notice n1 = saveNotice("공지1");
        Notice n2 = saveNotice("공지2");
        Long id1 = insertAlertHistory(USER_ID, n1.getId(), "SUCCESS", null);
        Long id2 = insertAlertHistory(USER_ID, n2.getId(), "SUCCESS", null);

        NotificationListResponse response = notificationService.listNotifications(USER_ID, null, 1);

        assertThat(response.hasNext()).isTrue();
        assertThat(response.alerts()).hasSize(1);
        assertThat(response.alerts().getFirst().alertHistoryId()).isEqualTo(id2);
        assertThat(response.nextCursor()).isEqualTo(id2);
    }

    @Test
    @DisplayName("NS-4: 전체 데이터 < size → hasNext=false, nextCursor=null")
    void listNotifications_lessThanSize_returnsHasNextFalse() {
        Notice notice = saveNotice("공지");
        insertAlertHistory(USER_ID, notice.getId(), "SUCCESS", null);

        NotificationListResponse response = notificationService.listNotifications(USER_ID, null, 20);

        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("NS-5: 결과 없음 → 빈 alerts, hasNext=false")
    void listNotifications_noData_returnsEmpty() {
        NotificationListResponse response = notificationService.listNotifications(USER_ID, null, 20);

        assertThat(response.alerts()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("NS-6: createdAt이 OffsetDateTime +09:00 형식")
    void listNotifications_createdAtHasKstOffset() {
        Notice notice = saveNotice("공지");
        insertAlertHistory(USER_ID, notice.getId(), "SUCCESS", null);

        NotificationListResponse response = notificationService.listNotifications(USER_ID, null, 20);

        assertThat(response.alerts().getFirst().createdAt().getOffset())
                .isEqualTo(ZoneOffset.ofHours(9));
    }

    // --- markRead ---

    @Test
    @DisplayName("NS-7: 내 알림 → isRead=true 저장")
    void markRead_myAlert_setsIsReadTrue() {
        Notice notice = saveNotice("공지");
        Long historyId = insertAlertHistory(USER_ID, notice.getId(), "SUCCESS", null);

        notificationService.markRead(USER_ID, historyId);

        boolean isRead = alertHistoryJpaRepository.findById(historyId).orElseThrow().isRead();
        assertThat(isRead).isTrue();
    }

    @Test
    @DisplayName("NS-8: 이미 읽은 알림 → 예외 없이 정상 처리 (멱등성)")
    void markRead_alreadyRead_doesNotThrow() {
        Notice notice = saveNotice("공지");
        Long historyId = insertAlertHistory(USER_ID, notice.getId(), "SUCCESS", null);

        notificationService.markRead(USER_ID, historyId);
        notificationService.markRead(USER_ID, historyId);

        boolean isRead = alertHistoryJpaRepository.findById(historyId).orElseThrow().isRead();
        assertThat(isRead).isTrue();
    }

    @Test
    @DisplayName("NS-9: 존재하지 않는 alertHistoryId → ALERT_NOT_FOUND 예외")
    void markRead_notExistingId_throwsAlertNotFound() {
        assertThatThrownBy(() -> notificationService.markRead(USER_ID, 99999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(AlertErrorCode.ALERT_NOT_FOUND));
    }

    @Test
    @DisplayName("NS-10: 타인 알림 → ALERT_NOT_FOUND 예외")
    void markRead_othersAlert_throwsAlertNotFound() {
        Notice notice = saveNotice("공지");
        Long historyId = insertAlertHistory(OTHER_USER_ID, notice.getId(), "SUCCESS", null);

        assertThatThrownBy(() -> notificationService.markRead(USER_ID, historyId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(AlertErrorCode.ALERT_NOT_FOUND));
    }

    // --- deleteNotification ---

    @Test
    @DisplayName("NS-11: 내 알림 삭제 → 이력 삭제")
    void deleteNotification_myAlert_deletesAlert() {
        Notice notice = saveNotice("공지");
        Long historyId = insertAlertHistory(USER_ID, notice.getId(), "SUCCESS", null);

        notificationService.deleteNotification(USER_ID, historyId);

        assertThat(alertHistoryJpaRepository.findById(historyId)).isEmpty();
    }

    @Test
    @DisplayName("NS-12: 존재하지 않는 alertHistoryId 삭제 → ALERT_NOT_FOUND 예외")
    void deleteNotification_notExistingId_throwsAlertNotFound() {
        assertThatThrownBy(() -> notificationService.deleteNotification(USER_ID, 99999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(AlertErrorCode.ALERT_NOT_FOUND));
    }

    @Test
    @DisplayName("NS-13: 타인 알림 삭제 → ALERT_NOT_FOUND 예외")
    void deleteNotification_othersAlert_throwsAlertNotFound() {
        Notice notice = saveNotice("공지");
        Long historyId = insertAlertHistory(OTHER_USER_ID, notice.getId(), "SUCCESS", null);

        assertThatThrownBy(() -> notificationService.deleteNotification(USER_ID, historyId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(AlertErrorCode.ALERT_NOT_FOUND));
    }

    private Notice saveNotice(String title) {
        return noticeRepository.save(Notice.from(CrawledNotice.builder()
                .articleNo("NS-" + title.hashCode())
                .sourceType("MAIN").sourceId(null).category("일반")
                .title(title).department("공지사항")
                .postedAt(LocalDate.now().toString())
                .url("https://example.com/" + title.hashCode())
                .bodyText("").imageUrls(List.of()).build()));
    }

    private Long insertAlertHistory(Long userId, Long noticeId, String status, String matchedTag) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO alert_history (user_id, notice_id, alarm_status, is_read, retry_count, created_at, matched_tag) " +
                "VALUES (?, ?, ?, false, 0, now(), ?) RETURNING id",
                Long.class, userId, noticeId, status, matchedTag);
    }
}
