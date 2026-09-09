package org.cathori.backend.notification.application;

import org.cathori.backend.IntegrationTestBase;
import org.cathori.backend.notification.application.push.*;
import org.cathori.backend.notification.domain.AlertHistory;
import org.cathori.backend.notification.infra.AlertHistoryJpaRepository;
import org.cathori.backend.notice.application.crawling.CrawledNotice;
import org.cathori.backend.notice.infra.crawling.source.DepartmentSource;
import org.cathori.backend.notice.model.Notice;
import org.cathori.backend.notice.model.NoticeRepository;
import org.cathori.backend.tag.domain.Tag;
import org.cathori.backend.tag.infra.TagJpaRepository;
import org.cathori.backend.user.application.NotificationPort;
import org.cathori.backend.user.domain.User;
import org.cathori.backend.user.infra.UserJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("AlertService 통합 테스트")
class AlertServiceTest extends IntegrationTestBase {

    @Autowired
    PushNotificationService pushNotificationService;
    @Autowired AlertHistoryJpaRepository alertHistoryJpaRepository;
    @Autowired NoticeRepository noticeRepository;
    @Autowired UserJpaRepository userJpaRepository;
    @Autowired TagJpaRepository tagJpaRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean
    PushNotificationPort pushNotificationPort;
    @MockitoBean NotificationPort notificationPort;

    @AfterEach
    void cleanup() {
        alertHistoryJpaRepository.deleteAll();
        tagJpaRepository.deleteAll();
        userJpaRepository.deleteAll();
        noticeRepository.deleteAll();
    }

    // --- sendNoticeNotifications() ---

    @Test
    @DisplayName("DA-1: FCM 성공 → 사용자별 SUCCESS 이력 저장")
    void sendNoticeNotifications_fcmSuccess_savesSuccessHistory() {
        Notice notice = saveNotice("장학금 안내");
        User user = saveUserWithToken("user1@test.com", "token-abc");
        saveTag(user.getId(), "장학금");
        given(pushNotificationPort.send(anyList(), anyString(), anyString(), anyLong()))
                .willReturn(List.of(new UserPushResult(user.getId(), true)));

        pushNotificationService.sendNoticeNotifications();

        List<AlertHistory> histories = alertHistoryJpaRepository.findAll();
        assertThat(histories).hasSize(1);
        assertThat(histories.getFirst().getAlarmStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("DA-2: FCM 실패 → 사용자별 FAILED 이력 저장")
    void sendNoticeNotifications_fcmFailure_savesFailedHistory() {
        saveNotice("장학금 안내");
        User user = saveUserWithToken("user1@test.com", "token-abc");
        saveTag(user.getId(), "장학금");
        given(pushNotificationPort.send(anyList(), anyString(), anyString(), anyLong()))
                .willReturn(List.of(new UserPushResult(user.getId(), false)));

        pushNotificationService.sendNoticeNotifications();

        List<AlertHistory> histories = alertHistoryJpaRepository.findAll();
        assertThat(histories).hasSize(1);
        assertThat(histories.getFirst().getAlarmStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("DA-4: 태그 미매칭 → FCM 미호출, 이력 미저장")
    void sendNoticeNotifications_noTagMatch_doesNotSendOrSave() {
        saveNotice("장학금 안내");
        User user = saveUserWithToken("user1@test.com", "token-abc");
        saveTag(user.getId(), "취업");

        pushNotificationService.sendNoticeNotifications();

        verify(pushNotificationPort, never()).send(anyList(), anyString(), anyString(), anyLong());
        assertThat(alertHistoryJpaRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("DA-5: deviceToken=null → FCM 미호출, 이력 미저장")
    void sendNoticeNotifications_nullDeviceToken_doesNotSendOrSave() {
        saveNotice("장학금 안내");
        User user = saveUserWithoutToken("user1@test.com");
        saveTag(user.getId(), "장학금");

        pushNotificationService.sendNoticeNotifications();

        verify(pushNotificationPort, never()).send(anyList(), anyString(), anyString(), anyLong());
        assertThat(alertHistoryJpaRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("DA-7: alertDispatched=true 공지 → FCM 미호출")
    void sendNoticeNotifications_alreadyDispatched_doesNotSend() {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo("TEST001")
                .sourceType("MAIN")
                .sourceId(null)
                .category("일반")
                .title("장학금 안내")
                .department("공지사항")
                .postedAt(LocalDate.now().toString())
                .url("https://example.com/notice/1")
                .bodyText("")
                .imageUrls(List.of())
                .build();
        Notice notice = Notice.from(crawled);
        notice.markAlertDispatched();
        noticeRepository.save(notice);

        pushNotificationService.sendNoticeNotifications();

        verify(pushNotificationPort, never()).send(anyList(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("DA-9: 발송 완료 후 notice.alertDispatched=true 변경")
    void sendNoticeNotifications_afterDispatch_marksNoticeAsDispatched() {
        Notice notice = saveNotice("장학금 안내");
        User user = saveUserWithToken("user1@test.com", "token-abc");
        saveTag(user.getId(), "장학금");
        given(pushNotificationPort.send(anyList(), anyString(), anyString(), anyLong()))
                .willReturn(List.of(new UserPushResult(user.getId(), true)));

        pushNotificationService.sendNoticeNotifications();

        Notice reloaded = noticeRepository.findById(notice.getId()).orElseThrow();
        assertThat(reloaded.isAlertDispatched()).isTrue();
    }

    @Test
    @DisplayName("DA-10: 매칭 사용자 없어도 notice.alertDispatched=true 변경")
    void sendNoticeNotifications_noMatchingUser_stillMarksNoticeAsDispatched() {
        Notice notice = saveNotice("장학금 안내");
        saveUserWithoutToken("user1@test.com");  // 태그 없는 사용자 (매칭 안 됨)

        pushNotificationService.sendNoticeNotifications();

        Notice reloaded = noticeRepository.findById(notice.getId()).orElseThrow();
        assertThat(reloaded.isAlertDispatched()).isTrue();
    }

    @Test
    @DisplayName("DA-11: 학과 공지 — 제1전공 일치 사용자에게만 발송")
    @SuppressWarnings("unchecked")
    void sendNoticeNotifications_departmentNotice_onlySendsToMatchingMajorUser() {
        String csieCode = DepartmentSource.findEnumNameByDisplayName("컴퓨터정보공학");
        saveDepartmentNotice("장학금 안내", csieCode);
        User matching = saveUserWithToken("csie@test.com", "token-csie");
        saveTag(matching.getId(), "장학금");
        User nonMatching = saveUserWithSecondMajor("biz@test.com", "token-biz", "경영학", null);
        saveTag(nonMatching.getId(), "장학금");
        given(pushNotificationPort.send(anyList(), anyString(), anyString(), anyLong()))
                .willReturn(List.of(new UserPushResult(matching.getId(), true)));

        pushNotificationService.sendNoticeNotifications();

        ArgumentCaptor<List<UserToken>> captor = ArgumentCaptor.forClass(List.class);
        verify(pushNotificationPort).send(captor.capture(), anyString(), anyString(), anyLong());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().userId()).isEqualTo(matching.getId());
    }

    @Test
    @DisplayName("DA-14: 학과 공지 — 제2전공=전공심화 → 제외")
    void sendNoticeNotifications_departmentNotice_excludesSecondMajorJeonGongSimhwa() {
        String csieCode = DepartmentSource.findEnumNameByDisplayName("컴퓨터정보공학");
        saveDepartmentNotice("장학금 안내", csieCode);
        User user = saveUserWithSecondMajor("biz@test.com", "token-biz", "경영학", "전공심화");
        saveTag(user.getId(), "장학금");

        pushNotificationService.sendNoticeNotifications();

        verify(pushNotificationPort, never()).send(anyList(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("DA-3: FCM 발송 시 matched_tag 저장 — 가나다 순 첫 번째 태그")
    void sendNoticeNotifications_savesMatchedTagAsMinTagName() {
        Notice notice = saveNotice("국가장학금 신청 안내");
        User user = saveUserWithToken("user1@test.com", "token-abc");
        saveTag(user.getId(), "장학");
        saveTag(user.getId(), "국가장학");
        given(pushNotificationPort.send(anyList(), anyString(), anyString(), anyLong()))
                .willReturn(List.of(new UserPushResult(user.getId(), true)));

        pushNotificationService.sendNoticeNotifications();

        AlertHistory history = alertHistoryJpaRepository.findAll().getFirst();
        assertThat(history.getMatchedTag()).isEqualTo("국가장학");
    }

    // --- retryFailedNotifications() ---

    @Test
    @DisplayName("RA-1: 재시도 FCM 성공 → alarmStatus=SUCCESS 변경")
    void retryFailedNotifications_fcmSuccess_updatesStatusToSuccess() {
        Notice notice = saveNotice("장학금 안내");
        User user = saveUserWithToken("user1@test.com", "token-abc");
        AlertHistory failed = AlertHistory.create(user.getId(), notice.getId(), null);
        failed.markFailed();
        AlertHistory saved = alertHistoryJpaRepository.save(failed);
        given(pushNotificationPort.send(anyList(), anyString(), anyString(), anyLong()))
                .willReturn(List.of(new UserPushResult(user.getId(), true)));

        pushNotificationService.retryFailedNotifications();

        AlertHistory updated = alertHistoryJpaRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getAlarmStatus()).isEqualTo("SUCCESS");
    }

    // --- 헬퍼 메서드 ---

    private Notice saveNotice(String title) {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo("TEST001")
                .sourceType("MAIN")
                .sourceId(null)
                .category("일반")
                .title(title)
                .department("공지사항")
                .postedAt(LocalDate.now().toString())
                .url("https://example.com/notice/1")
                .bodyText("")
                .imageUrls(List.of())
                .build();
        return noticeRepository.save(Notice.from(crawled));
    }

    private Notice saveDepartmentNotice(String title, String sourceId) {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo("DEP001")
                .sourceType("DEPARTMENT")
                .sourceId(sourceId)
                .category("일반")
                .title(title)
                .department("학과공지")
                .postedAt(LocalDate.now().toString())
                .url("https://example.com/dept/1")
                .bodyText("")
                .imageUrls(List.of())
                .build();
        return noticeRepository.save(Notice.from(crawled));
    }

    private User saveUserWithToken(String email, String token) {
        User user = userJpaRepository.save(
                User.create(email, "encodedPwd", "컴퓨터정보공학", null, 2, "재학"));
        jdbcTemplate.update("UPDATE users SET device_token = ? WHERE id = ?", token, user.getId());
        return user;
    }

    private User saveUserWithoutToken(String email) {
        return userJpaRepository.save(
                User.create(email, "encodedPwd", "컴퓨터정보공학", null, 2, "재학"));
    }

    private User saveUserWithSecondMajor(String email, String token, String major, String secondMajor) {
        User user = userJpaRepository.save(
                User.create(email, "encodedPwd", major, secondMajor, 2, "재학"));
        if (token != null) {
            jdbcTemplate.update("UPDATE users SET device_token = ? WHERE id = ?", token, user.getId());
        }
        return user;
    }

    private void saveTag(Long userId, String tagName) {
        tagJpaRepository.save(Tag.create(userId, tagName));
    }
}
