package org.cathori.backend.notification.application.push;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.cathori.backend.notification.domain.AlertHistory;
import org.cathori.backend.notification.domain.AlertHistoryRepository;
import org.cathori.backend.notice.infra.crawling.source.DepartmentSource;
import org.cathori.backend.notice.model.Notice;
import org.cathori.backend.notice.model.NoticeRepository;
import org.cathori.backend.user.domain.User;
import org.cathori.backend.user.domain.UserRepository;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private final AlertHistoryRepository alertHistoryRepository;
    private final NotificationResultWriter notificationResultWriter;
    private final NoticeRepository noticeRepository;
    private final UserRepository userRepository;
    private final PushNotificationPort pushNotificationPort;

    public void sendNoticeNotifications() {
        List<Notice> unsentNotices = noticeRepository.findByAlertDispatchedFalse();
        if (unsentNotices.isEmpty()) {
            log.info("미발송 공지 없음");
            return;
        }
        log.info("미발송 공지 {}건 발송 시작", unsentNotices.size());
        for (Notice notice : unsentNotices) {
            log.info("공지id {} 제목 {} 발송 시작", notice.getId(), notice.getTitle());
            sendNoticeNotification(notice);
        }
    }

    private void sendNoticeNotification(Notice notice) {
        boolean hasRecipients = registerRecipients(notice);
        if (!hasRecipients) return;
        sendToRecipients(notice);
    }

    // 발송 대상을 계산해 AlertHistory PENDING row로 원자적으로 저장한다.
    // 이후 sendToRecipients는 이 결과를 신뢰의 원천으로 삼아 DB에서 다시 읽어 발송한다.
    private boolean registerRecipients(Notice notice) {
        List<UserToken> recipients = selectEligibleRecipients(notice);
        log.info("noticeId={} 발송 대상 {}명", notice.getId(), recipients.size());

        if (recipients.isEmpty()) {
            notice.markAlertDispatched();
            noticeRepository.save(notice);
            return false;
        }

        // 발송 절차가 중단된 후 재실행되더라도, 이미 커밋된 (회원, 공지) 이력을 중복 INSERT하지 않도록 기존 이력이 있는 사용자를 제외한다.
        Set<Long> userIdsWithHistory = new HashSet<>(alertHistoryRepository.findUserIdsByNoticeId(notice.getId()));

        // AlertHistory에 저장된 user_id를 제외한 나머지 user_id에 대해서만 AlertHistory를 생성
        Map<Long, String> matchedTagsByUserId = userRepository.findFirstMatchedTagsByTitle(notice.getTitle());
        List<AlertHistory> newPendingHistories = recipients.stream()
                .filter(recipient -> !userIdsWithHistory.contains(recipient.userId()))
                .map(recipient -> AlertHistory.create(
                        recipient.userId(), notice.getId(), matchedTagsByUserId.get(recipient.userId())))
                .toList();

        // 기존 AlertHistory에 없을 경우 PENDING 로그를 추가
        if (!newPendingHistories.isEmpty()) {
            alertHistoryRepository.saveAll(newPendingHistories);
        }
        return true;
    }

    // registerRecipients가 저장한 PENDING 대상을 DB에서 다시 읽어와 발송한다.
    private void sendToRecipients(Notice notice) {
        List<UserToken> recipients = loadPendingRecipients(notice.getId());
        if (recipients.isEmpty()) return;

        // @Transactional 범위 밖에서 FCM HTTP 호출
        List<UserPushResult> pushResults;
        try {
            pushResults = pushNotificationPort.send(recipients, "Cathori 새 공지", notice.getTitle(), notice.getId());
        } catch (Exception e) {
            log.error("FCM 발송 실패. noticeId={}", notice.getId(), e);
            List<Long> recipientIds = recipients.stream().map(UserToken::userId).toList();

            // 여기서 발송 실패는 UPDATE 라서 괜찮음
            notificationResultWriter.persistDispatchFailure(notice, recipientIds);
            return;
        }

        // FCM예외와 무관하게 성공 실패는 한 번에 기록
        List<Long> successfulUserIds = pushResults.stream().filter(UserPushResult::success).map(UserPushResult::userId).toList();
        List<Long> failedUserIds = pushResults.stream().filter(r -> !r.success()).map(UserPushResult::userId).toList();
        notificationResultWriter.persistDispatchResult(notice, successfulUserIds, failedUserIds);

        log.info("FCM 발송 완료. noticeId={}, success={}, fail={}", notice.getId(), successfulUserIds.size(), failedUserIds.size());
    }

    private List<UserToken> loadPendingRecipients(Long noticeId) {
        List<Long> recipientIds = alertHistoryRepository.findPendingUserIdsByNoticeId(noticeId);
        if (recipientIds.isEmpty()) return List.of();

        Map<Long, String> deviceTokensByUserId = userRepository.findDeviceTokensByIds(recipientIds);
        return recipientIds.stream()
                .filter(deviceTokensByUserId::containsKey)
                .map(userId -> new UserToken(userId, deviceTokensByUserId.get(userId)))
                .toList();
    }

    private List<UserToken> selectEligibleRecipients(Notice notice) {
        return userRepository.findUsersWithTagMatchingTitle(notice.getTitle())
                .stream()
                .filter(user -> matchesNoticeScope(user, notice))
                .filter(user -> user.getDeviceToken() != null)
                .map(user -> new UserToken(user.getId(), user.getDeviceToken()))
                .toList();
    }

    private boolean matchesNoticeScope(User user, Notice notice) {
        if ("MAIN".equals(notice.getSourceType())) return true;
        String sourceId = notice.getSourceId();
        String majorCode = DepartmentSource.findEnumNameByDisplayName(user.getMajor());
        if (sourceId.equals(majorCode)) return true;
        String secondMajor = user.getSecondMajor();
        if (secondMajor == null || "전공심화".equals(secondMajor)) return false;
        String secondMajorCode = DepartmentSource.findEnumNameByDisplayName(secondMajor);
        return sourceId.equals(secondMajorCode);
    }

    public void retryFailedNotifications() {
        List<AlertHistory> failedHistories = alertHistoryRepository.findFailedForRetry();
        if (failedHistories.isEmpty()) return;

        log.info("FCM 재시도 {}건", failedHistories.size());

        Map<Long, List<AlertHistory>> failedHistoriesByNoticeId = failedHistories.stream()
                .collect(Collectors.groupingBy(AlertHistory::getNoticeId));

        for (Map.Entry<Long, List<AlertHistory>> entry : failedHistoriesByNoticeId.entrySet()) {
            retryNotificationsForNotice(entry.getKey(), entry.getValue());
        }
    }

    private void retryNotificationsForNotice(Long noticeId, List<AlertHistory> failedHistories) {
        Notice notice = noticeRepository.findById(noticeId).orElse(null);
        if (notice == null) return;

        List<UserToken> recipients = failedHistories.stream()
                .map(history -> {
                    User user = userRepository.findById(history.getUserId()).orElse(null);
                    if (user == null || user.getDeviceToken() == null) return null;
                    return new UserToken(history.getUserId(), user.getDeviceToken());
                })
                .filter(Objects::nonNull)
                .toList();

        if (recipients.isEmpty()) return;

        List<UserPushResult> pushResults;
        try {
            pushResults = pushNotificationPort.send(recipients, "Cathori 새 공지", notice.getTitle(), noticeId);
        } catch (Exception e) {
            log.error("FCM 재시도 실패. noticeId={}", noticeId, e);
            List<Long> recipientIds = recipients.stream().map(UserToken::userId).toList();
            notificationResultWriter.persistRetryFailure(noticeId, recipientIds);
            return;
        }

        List<Long> successfulUserIds = pushResults.stream().filter(UserPushResult::success).map(UserPushResult::userId).toList();
        List<Long> failedUserIds = pushResults.stream().filter(r -> !r.success()).map(UserPushResult::userId).toList();
        notificationResultWriter.persistRetryResult(noticeId, successfulUserIds, failedUserIds);
    }
}
