package org.cathori.backend.notification.infra;

import org.cathori.backend.IntegrationTestBase;
import org.cathori.backend.notification.application.inbox.NotificationQueryPort;
import org.cathori.backend.notification.application.inbox.NotificationRow;
import org.cathori.backend.notice.application.crawling.CrawledNotice;
import org.cathori.backend.notice.model.Notice;
import org.cathori.backend.notice.model.NoticeRepository;
import org.cathori.backend.user.application.NotificationPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationQueryAdapter 쿼리 테스트")
class NotificationQueryAdapterTest extends IntegrationTestBase {

    @Autowired NotificationQueryPort notificationQueryPort;
    @Autowired AlertHistoryJpaRepository alertHistoryJpaRepository;
    @Autowired NoticeRepository noticeRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean NotificationPort notificationPort;

    private static final Long USER_ID = 1L;

    @AfterEach
    void cleanup() {
        alertHistoryJpaRepository.deleteAll();
        noticeRepository.deleteAll();
    }

    @Test
    @DisplayName("AQ-1: alarm_status=SUCCESS만 반환 — PENDING, FAILED 제외")
    void findByUserIdWithCursor_returnsOnlySuccess() {
        Notice notice = saveNotice("장학금 안내");
        insertAlertHistory(USER_ID, notice.getId(), "PENDING", null);
        insertAlertHistory(USER_ID, notice.getId() + 1000, "FAILED", null);

        Notice notice2 = saveNotice("다른 공지");
        insertAlertHistory(USER_ID, notice2.getId(), "SUCCESS", "장학");

        List<NotificationRow> result = notificationQueryPort.findByUserIdWithCursor(USER_ID, null, 10);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().matchedTag()).isEqualTo("장학");
    }

    @Test
    @DisplayName("AQ-2: cursor null → 전체 최신순 조회 (id DESC)")
    void findByUserIdWithCursor_noCursor_returnsAllInDescOrder() {
        Notice n1 = saveNotice("공지1");
        Notice n2 = saveNotice("공지2");
        insertAlertHistory(USER_ID, n1.getId(), "SUCCESS", null);
        insertAlertHistory(USER_ID, n2.getId(), "SUCCESS", null);

        List<NotificationRow> result = notificationQueryPort.findByUserIdWithCursor(USER_ID, null, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).alertHistoryId()).isGreaterThan(result.get(1).alertHistoryId());
    }

    @Test
    @DisplayName("AQ-3: cursor 있음 → id < cursor 조건 적용")
    void findByUserIdWithCursor_withCursor_returnsOnlyBelowCursor() {
        Notice n1 = saveNotice("공지1");
        Notice n2 = saveNotice("공지2");
        Long id1 = insertAlertHistory(USER_ID, n1.getId(), "SUCCESS", null);
        Long id2 = insertAlertHistory(USER_ID, n2.getId(), "SUCCESS", null);

        Long cursor = id2;
        List<NotificationRow> result = notificationQueryPort.findByUserIdWithCursor(USER_ID, cursor, 10);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().alertHistoryId()).isEqualTo(id1);
    }

    @Test
    @DisplayName("AQ-4: limit 정확히 전달 — limit=2, 데이터 3개 → 2개만 반환")
    void findByUserIdWithCursor_respectsLimit() {
        Notice n1 = saveNotice("공지1");
        Notice n2 = saveNotice("공지2");
        Notice n3 = saveNotice("공지3");
        insertAlertHistory(USER_ID, n1.getId(), "SUCCESS", null);
        insertAlertHistory(USER_ID, n2.getId(), "SUCCESS", null);
        insertAlertHistory(USER_ID, n3.getId(), "SUCCESS", null);

        List<NotificationRow> result = notificationQueryPort.findByUserIdWithCursor(USER_ID, null, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("AQ-5: matched_tag, deadlineAt 정확히 반환")
    void findByUserIdWithCursor_returnsCorrectFields() {
        LocalDate deadline = LocalDate.of(2026, 6, 30);
        Notice notice = saveNoticeWithDeadline("장학금 안내", deadline);
        insertAlertHistory(USER_ID, notice.getId(), "SUCCESS", "국가장학");

        List<NotificationRow> result = notificationQueryPort.findByUserIdWithCursor(USER_ID, null, 10);

        assertThat(result).hasSize(1);
        NotificationRow row = result.getFirst();
        assertThat(row.matchedTag()).isEqualTo("국가장학");
        assertThat(row.deadlineAt()).isEqualTo(deadline);
        assertThat(row.title()).isEqualTo("장학금 안내");
    }

    private Notice saveNotice(String title) {
        return noticeRepository.save(Notice.from(CrawledNotice.builder()
                .articleNo("AQ-" + title.hashCode())
                .sourceType("MAIN").sourceId(null).category("일반")
                .title(title).department("공지사항")
                .postedAt(LocalDate.now().toString())
                .url("https://example.com/" + title.hashCode())
                .bodyText("").imageUrls(List.of()).build()));
    }

    private Notice saveNoticeWithDeadline(String title, LocalDate deadline) {
        Notice notice = Notice.from(CrawledNotice.builder()
                .articleNo("AQ-DL-" + title.hashCode())
                .sourceType("MAIN").sourceId(null).category("일반")
                .title(title).department("공지사항")
                .postedAt(LocalDate.now().toString())
                .url("https://example.com/dl/" + title.hashCode())
                .bodyText("").imageUrls(List.of()).build());
        Notice saved = noticeRepository.save(notice);
        jdbcTemplate.update("UPDATE notices SET deadline_at = ? WHERE id = ?", deadline, saved.getId());
        return saved;
    }

    private Long insertAlertHistory(Long userId, Long noticeId, String status, String matchedTag) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO alert_history (user_id, notice_id, alarm_status, is_read, retry_count, created_at, matched_tag) " +
                "VALUES (?, ?, ?, false, 0, now(), ?) RETURNING id",
                Long.class, userId, noticeId, status, matchedTag);
    }
}
