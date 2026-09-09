package org.cathori.backend.notice.application.query;

import java.util.List;


/**
 * 공지 피드 조회 조건을 담는 쿼리 객체. 사용자의 전공·복수전공·관심 태그를 기반으로
 * 개인화된 공지를 필터링하며, category가 null이면 전체 카테고리를 대상으로 조회한다.
 */
public record NoticeFeedQuery(
        Long userId,
        String major,
        String secondMajor,
        String category,
        List<String> tags,
        int page,
        int size
) {}
