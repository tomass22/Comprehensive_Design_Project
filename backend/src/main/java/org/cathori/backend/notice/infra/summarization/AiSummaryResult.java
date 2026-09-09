package org.cathori.backend.notice.infra.summarization;

import java.util.List;

public record AiSummaryResult(List<String> summary, String deadline) {}
