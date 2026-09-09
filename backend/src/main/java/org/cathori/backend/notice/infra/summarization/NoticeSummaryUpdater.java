package org.cathori.backend.notice.infra.summarization;

import lombok.RequiredArgsConstructor;
import org.cathori.backend.notice.model.Notice;
import org.cathori.backend.notice.model.NoticeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NoticeSummaryUpdater {

    private final NoticeRepository noticeRepository;

    @Transactional
    public void applyResult(Long noticeId, AiSummaryResult result) {
        Notice notice = noticeRepository.findById(noticeId).orElseThrow();
        notice.applySummary(result);
    }

    @Transactional
    public void applyFailed(Long noticeId) {
        Notice notice = noticeRepository.findById(noticeId).orElseThrow();
        notice.markSummaryFailed();
    }
}
