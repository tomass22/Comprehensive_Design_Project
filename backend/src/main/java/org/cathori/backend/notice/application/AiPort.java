package org.cathori.backend.notice.application;


import org.cathori.backend.notice.infra.summarization.AiSummaryResult;

import java.util.List;

public interface AiPort {
    AiSummaryResult summarize(String bodyText, List<String> ImgUrl);
}
