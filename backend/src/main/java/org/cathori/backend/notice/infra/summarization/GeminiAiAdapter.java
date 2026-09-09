package org.cathori.backend.notice.infra.summarization;

import org.cathori.backend.notice.application.AiPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class GeminiAiAdapter implements AiPort {

    private static final String GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=";

    private static final String PROMPT_TEMPLATE = """
            다음은 대학교 공지사항 본문입니다. 분석하여 아래 JSON 형식으로 응답하세요.
            {
              "summary": ["핵심 내용 1", "핵심 내용 2", "핵심 내용 3"],
              "deadline": "YYYY-MM-DD 또는 null"
            }
            규칙:
            - summary: 공지의 핵심 내용을 3개 이하 bullet point로 요약 (한국어)
            - deadline: 마감일/신청마감/접수기간 종료일이 있으면 YYYY-MM-DD, 없으면 null

            본문:
            """;

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final HttpClient httpClient;

    public GeminiAiAdapter(
            RestClient.Builder builder,
            ObjectMapper mapper,
            @Value("${gemini.api.key}") String apiKey
    ) {
        this.restClient = builder.build();
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public AiSummaryResult summarize(String bodyText, List<String> imageUrls) {
        Map<String, Object> requestBody = buildRequestBody(bodyText, imageUrls);

        String responseJson = restClient.post()
                .uri(GEMINI_BASE_URL + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return parseResponse(responseJson);
    }

    private Map<String, Object> buildRequestBody(String bodyText, List<String> imageUrls) {
        List<Map<String, Object>> parts = new ArrayList<>();

        Map<String, Object> textPart = new HashMap<>();
        textPart.put("text", PROMPT_TEMPLATE + bodyText);
        parts.add(textPart);

        for (String url : imageUrls) {
            byte[] imageBytes = downloadImage(url); // 실패 시 RuntimeException throw
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            Map<String, String> inlineData = new HashMap<>();
            inlineData.put("mime_type", "image/jpeg");
            inlineData.put("data", base64);

            Map<String, Object> imagePart = new HashMap<>();
            imagePart.put("inline_data", inlineData);
            parts.add(imagePart);
        }

        Map<String, Object> content = new HashMap<>();
        content.put("parts", parts);

        Map<String, String> generationConfig = new HashMap<>();
        generationConfig.put("responseMimeType", "application/json");

        Map<String, Object> body = new HashMap<>();
        body.put("contents", List.of(content));
        body.put("generationConfig", generationConfig);
        return body;
    }

    private AiSummaryResult parseResponse(String responseJson) {
        try {
            JsonNode root = mapper.readTree(responseJson);
            String innerJson = root.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();

            JsonNode summaryJson = mapper.readTree(innerJson);

            List<String> summary = mapper.convertValue(
                    summaryJson.path("summary"), new TypeReference<List<String>>() {});

            JsonNode deadlineNode = summaryJson.path("deadline");
            String deadline = (deadlineNode.isNull() || deadlineNode.asText().equals("null"))
                    ? null : deadlineNode.asText();

            return new AiSummaryResult(summary, deadline);
        } catch (Exception e) {
            throw new RuntimeException("Gemini 응답 파싱 실패", e);
        }
    }

    private byte[] downloadImage(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new RuntimeException("이미지 다운로드 실패: status=" + response.statusCode() + ", url=" + url);
            }
            return response.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("이미지 다운로드 실패: url=" + url, e);
        }
    }

}