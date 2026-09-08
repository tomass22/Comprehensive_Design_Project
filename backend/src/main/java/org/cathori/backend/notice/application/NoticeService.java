package org.cathori.backend.notice.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cathori.backend.notice.infra.NoticeSummaryUpdater;
import org.cathori.backend.notice.infra.ai.AiSummaryResult;
import org.cathori.backend.notice.model.Notice;
import org.cathori.backend.notice.model.NoticeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final CrawlerPort crawlerPort;
    private final AiPort aiPort;
    private final NoticeRepository noticeRepository;
    private final NoticeSummaryUpdater noticeSummaryUpdater;

    public List<CrawledNotice> crawl(String sourceType, String sourceId) {
        List<NoticeCandidate> candidates = crawlerPort.listCandidates(sourceType, sourceId);
        if (candidates.isEmpty()) return List.of();

        List<String> articleNos = candidates.stream()
                .map(NoticeCandidate::getArticleNo)
                .toList();
        Set<String> existingArticleNos = noticeRepository.findExistingArticleNos(sourceType, sourceId, articleNos);

        return candidates.stream()
                .filter(candidate -> !existingArticleNos.contains(candidate.getArticleNo()))
                .map(candidate -> crawlerPort.crawlDetail(sourceType, sourceId, candidate))
                .toList();
    }

    @Transactional
    public List<Long> save(List<CrawledNotice> crawledList) {
        List<Notice> notices = crawledList.stream()
                .map(Notice::from)
                .toList();

        return noticeRepository.saveAll(notices).stream()
                .map(Notice::getId)
                .toList();
    }

    public void summarize(List<Long> noticeIds) {
        if (noticeIds.isEmpty()) return;

        List<Notice> notices = noticeRepository.findAllById(noticeIds);
        for (Notice notice : notices) {
            if (notice.getBodyText() == null) continue;
            try {
                AiSummaryResult result = aiPort.summarize(notice.getBodyText(), notice.getImageUrls());
                noticeSummaryUpdater.applyResult(notice.getId(), result);
            } catch (Exception e) {
                log.warn("AI 요약 실패 noticeId={}", notice.getId(), e);
                noticeSummaryUpdater.applyFailed(notice.getId());
            }
        }
    }

    public void retrySummary() {
        List<Notice> notices = noticeRepository.findTop15ForSummary(
                List.of("PENDING", "FAILED"),
                PageRequest.of(0, 15)
        );

        for (Notice notice : notices) {
            if (notice.getBodyText() == null) continue;
            try {
                AiSummaryResult result = aiPort.summarize(notice.getBodyText(), notice.getImageUrls());
                noticeSummaryUpdater.applyResult(notice.getId(), result);
            } catch (Exception e) {
                log.warn("AI 요약 실패 noticeId={}", notice.getId(), e);
                noticeSummaryUpdater.applyFailed(notice.getId());
            }
        }
    }
}
