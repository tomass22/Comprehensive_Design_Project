package org.cathori.backend.notice.application.query;

/**
 * 공지 키워드 검색 유스케이스의 입력 파라미터.
 *
 * 사용자가 입력한 keyword로 공지 제목을 검색하되,
 * major·secondMajor를 기준으로 전체공지(MAIN)와 해당 학과 공지(DEPARTMENT)를 함께 탐색해
 * 본인과 관련 있는 공지만 결과에 포함시킨다.
 */
public record NoticeSearchQuery(
        Long userId,
        String keyword,
        String major,
        String secondMajor,
        int page,
        int size
) {}
