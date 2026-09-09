package org.cathori.backend.notice.infra.crawling.model;

import java.util.List;

public record NoticeDetails(String bodyText, List<String> imageUrls) {
}
