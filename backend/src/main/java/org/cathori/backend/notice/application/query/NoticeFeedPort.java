package org.cathori.backend.notice.application.query;

import java.util.List;

public interface NoticeFeedPort {
    List<NoticeRow> findFeed(NoticeFeedQuery query);
    List<NoticeRow> findBookmarked(BookmarkedNoticeQuery query);
    List<NoticeSearchRow> findSearch(NoticeSearchQuery query);
}
