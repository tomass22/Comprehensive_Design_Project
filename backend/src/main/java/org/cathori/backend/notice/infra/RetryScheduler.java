package org.cathori.backend.notice.infra;

import lombok.RequiredArgsConstructor;
import org.cathori.backend.notice.application.crawling.NoticeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RetryScheduler {

    private final NoticeService noticeService;

    @Scheduled(cron = "0 30 * * * *")
    public void retryFailedSummaries() {
        noticeService.retrySummary();
    }
}
