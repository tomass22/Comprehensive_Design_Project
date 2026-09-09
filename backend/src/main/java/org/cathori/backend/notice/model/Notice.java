package org.cathori.backend.notice.model;


import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.cathori.backend.notice.application.crawling.CrawledNotice;
import org.cathori.backend.notice.infra.ai.AiSummaryResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@Entity
@Table(name = "notices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String articleNo;

    @Column(nullable = false, length = 20)
    private String sourceType;

    @Column(length = 20)
    private String sourceId;

    @Column(nullable = false, length = 20)
    private String category;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String bodyText;

    @Column(columnDefinition = "text")
    private String imageUrlsJson;

    @Column(columnDefinition = "text")
    private String aiSummary;

    @Column(nullable = false, length = 20)
    private String aiSummaryStatus = "PENDING";

    @Column(nullable = false, length = 30)
    private String department;

    @Column(nullable = false)
    private LocalDate postedAt;

    @Column
    private LocalDate deadlineAt;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(nullable = false)
    private Long viewCount = 0L;

    @Column(name = "alert_dispatched", nullable = false)
    private boolean alertDispatched = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public void markAlertDispatched() {
        this.alertDispatched = true;
    }

    public List<String> getImageUrls() {
        if (imageUrlsJson == null || imageUrlsJson.isBlank()) return List.of();
        try {
            return List.of(MAPPER.readValue(imageUrlsJson, String[].class));
        } catch (JacksonException e) {
            return List.of();
        }
    }

    public void applySummary(AiSummaryResult result) {
        try {
            this.aiSummary = MAPPER.writeValueAsString(result.summary());
        } catch (JacksonException e) {
            this.aiSummary = "[]";
        }
        if (result.deadline() != null && !result.deadline().equals("null")) {
            try {
                this.deadlineAt = LocalDate.parse(result.deadline());
            } catch (DateTimeParseException ignored) {}
        }
        this.aiSummaryStatus = "SUCCESS";
    }

    public void markSummaryFailed() {
        this.aiSummaryStatus = "FAILED";
    }

    public static Notice from(CrawledNotice crawled) {
        Notice notice = new Notice();
        notice.articleNo = crawled.getArticleNo();
        notice.sourceType = crawled.getSourceType();
        notice.sourceId = crawled.getSourceId();
        notice.category = crawled.getCategory();
        notice.title = crawled.getTitle();
        notice.department = crawled.getDepartment();
        notice.postedAt = LocalDate.parse(crawled.getPostedAt());
        notice.url = crawled.getUrl();
        notice.bodyText = crawled.getBodyText();
        try {
            notice.imageUrlsJson = MAPPER.writeValueAsString(crawled.getImageUrls());
        } catch (JacksonException e) {
            notice.imageUrlsJson = "[]";
        }
        notice.aiSummaryStatus = "PENDING";
        notice.viewCount = 0L;
        return notice;
    }
}
