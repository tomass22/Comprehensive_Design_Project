package org.cathori.backend.notice.application.query;

import java.time.LocalDate;

/**
 * Raw notice search row from the database.
 */
public record NoticeSearchRow(
        Long id,
        String category,
        String title,
        String department,
        LocalDate postedAt,
        LocalDate deadlineAt,
        boolean isBookmarked
) {}
