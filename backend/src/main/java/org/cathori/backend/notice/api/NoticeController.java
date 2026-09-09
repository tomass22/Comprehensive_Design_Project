package org.cathori.backend.notice.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.cathori.backend.notice.api.dto.NoticeDetailResponse;
import org.cathori.backend.notice.api.dto.NoticeFeedResponse;
import org.cathori.backend.notice.api.dto.NoticeSearchResponse;
import org.cathori.backend.notice.application.query.NoticeFeedService;
import org.cathori.backend.security.CustomUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeFeedService noticeFeedService;

    @GetMapping
    public ResponseEntity<NoticeFeedResponse> getFeed(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tags,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        NoticeFeedResponse response = noticeFeedService.getFeed(
                userDetails.getUserId(), category, tags, page, size
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/search")
    public ResponseEntity<NoticeSearchResponse> search(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @NotBlank(message = "검색어를 입력해주세요")
            @Size(max = 100, message = "검색어는 100자 이하여야 합니다")
            @Pattern(regexp = "^[^;'\"\\\\=<>]*$", message = "검색어에 허용되지 않는 문자가 포함되어 있습니다")
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return ResponseEntity.ok(noticeFeedService.search(userDetails.getUserId(), q, page, size));
    }

    @GetMapping("/{noticeId:\\d+}")
    public ResponseEntity<NoticeDetailResponse> getDetail(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long noticeId) {
        return ResponseEntity.ok(noticeFeedService.getDetail(userDetails.getUserId(), noticeId));
    }
}
