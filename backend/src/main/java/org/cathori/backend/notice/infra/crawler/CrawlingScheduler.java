package org.cathori.backend.notice.infra.crawler;

import java.util.List;

import org.cathori.backend.notice.application.CrawledNotice;
import org.cathori.backend.notice.application.NoticeService;
import org.cathori.backend.notice.infra.crawler.source.DepartmentSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlingScheduler {

    private final NoticeService noticeService;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("컨테이너 기동 감지, 크롤링 1회 실행");
        scheduleCrawling();
    }

    @Scheduled(cron = "${crawler.dispatch.cron}")
    public void scheduleCrawling() {
        log.info("크롤링 시작");
        int total = 0;


        List<CrawledNotice> mainList = noticeService.crawl("MAIN", null);
        List<Long> mainIds = noticeService.save(mainList);
        noticeService.summarize(mainIds);
        total += mainList.size();

        for (DepartmentSource department : DepartmentSource.values()) {
            try {
                List<CrawledNotice> deptList = noticeService.crawl("DEPARTMENT", department.name());
                List<Long> deptIds = noticeService.save(deptList);
                noticeService.summarize(deptIds);
                total += deptList.size();
            } catch (Exception e) {
                log.warn("학과 크롤링 실패, 스킵: {} - {}", department.getDisplayName(), e.getMessage());
            }
        }

        log.info("크롤링 완료. 총 수집 건수: {}", total);
    }
}