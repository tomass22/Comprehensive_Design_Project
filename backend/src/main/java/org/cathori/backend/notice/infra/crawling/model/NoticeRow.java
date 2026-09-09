package org.cathori.backend.notice.infra.crawling.model;

/***
 * 공지 목록 페이지에서
 *
 * @param articleNo
 * @param category
 * @param title
 * @param department
 * @param postedAt
 * @param noticeDetailsUrl
 */

public record NoticeRow(
        String articleNo,
        String category,
        String title,
        String department,
        String postedAt,
        String noticeDetailsUrl
) {}
