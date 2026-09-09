package org.cathori.backend.bookmark.api;

import lombok.RequiredArgsConstructor;
import org.cathori.backend.bookmark.api.dto.BookmarkToggleResponse;
import org.cathori.backend.bookmark.application.BookmarkService;
import org.cathori.backend.notice.api.dto.NoticeFeedResponse;
import org.cathori.backend.notice.application.query.NoticeFeedService;
import org.cathori.backend.security.CustomUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;
    private final NoticeFeedService noticeFeedService;

    @GetMapping("/bookmarks")
    public ResponseEntity<NoticeFeedResponse> getBookmarks(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        NoticeFeedResponse response = noticeFeedService.getBookmarked(
                userDetails.getUserId(), page, size
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{noticeId:\\d+}/bookmark")
    public ResponseEntity<BookmarkToggleResponse> toggle(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long noticeId) {
        BookmarkToggleResponse response = bookmarkService.toggle(userDetails.getUserId(), noticeId);
        return ResponseEntity.ok(response);
    }
}
