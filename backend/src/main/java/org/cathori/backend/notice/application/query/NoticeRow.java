package org.cathori.backend.notice.application.query;

import java.time.LocalDate;

/**
 * Raw notice feed row from the database.
 */
public record NoticeRow(
        Long id,
        String sourceType,
        String category,
        String title,
        String department,
        LocalDate postedAt,
        LocalDate deadlineAt,
        String aiSummary,
        String aiSummaryStatus,
        String url,
        Long viewCount,
        boolean isBookmarked
) {}
