package org.cathori.backend.notice.application.query;

/**
 * 로그인 사용자의 북마크 공지 목록 조회 조건.
 */
public record BookmarkedNoticeQuery(
        Long userId,
        int page,
        int size
) {}
