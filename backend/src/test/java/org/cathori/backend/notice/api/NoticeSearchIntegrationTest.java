package org.cathori.backend.notice.api;

import org.cathori.backend.IntegrationTestBase;
import org.cathori.backend.notice.application.crawling.CrawledNotice;
import org.cathori.backend.notice.infra.summarization.AiSummaryResult;
import org.cathori.backend.notice.model.Notice;
import org.cathori.backend.notice.model.NoticeRepository;
import org.cathori.backend.security.JwtUtil;
import org.cathori.backend.user.application.AuthService;
import org.cathori.backend.user.application.NotificationPort;
import org.cathori.backend.user.application.VerifiedEmailStore;
import org.cathori.backend.user.api.dto.RegisterRequest;
import org.cathori.backend.user.infra.UserJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("공지 검색 통합 테스트")
class NoticeSearchIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired VerifiedEmailStore verifiedEmailStore;
    @Autowired UserJpaRepository userJpaRepository;
    @Autowired NoticeRepository noticeRepository;
    @Autowired JwtUtil jwtUtil;
    @MockitoBean NotificationPort notificationPort;

    private static final String EMAIL_A = "searchtest_a@catholic.ac.kr";
    private static final String EMAIL_B = "searchtest_b@catholic.ac.kr";
    private static final String PASSWORD = "password123!";

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        verifiedEmailStore.markVerified(EMAIL_A);
        authService.register(new RegisterRequest(EMAIL_A, PASSWORD, "컴퓨터정보공학부", "인공지능학과", 2, "재학"));
        Long userAId = userJpaRepository.findByEmail(EMAIL_A).orElseThrow().getId();
        tokenA = jwtUtil.generateAccessToken(userAId);

        verifiedEmailStore.markVerified(EMAIL_B);
        authService.register(new RegisterRequest(EMAIL_B, PASSWORD, "컴퓨터정보공학부", "전공심화", 2, "재학"));
        Long userBId = userJpaRepository.findByEmail(EMAIL_B).orElseThrow().getId();
        tokenB = jwtUtil.generateAccessToken(userBId);
    }

    @AfterEach
    void cleanup() {
        noticeRepository.deleteAll();
        userJpaRepository.findByEmail(EMAIL_A).ifPresent(userJpaRepository::delete);
        userJpaRepository.findByEmail(EMAIL_B).ifPresent(userJpaRepository::delete);
        verifiedEmailStore.remove(EMAIL_A);
        verifiedEmailStore.remove(EMAIL_B);
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────

    private Notice saveNotice(String sourceType, String sourceId, String title, LocalDate postedAt) {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo(UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .sourceType(sourceType)
                .sourceId(sourceId)
                .category("장학")
                .title(title)
                .department("테스트학과")
                .postedAt(postedAt.toString())
                .url("https://test.catholic.ac.kr")
                .bodyText("")
                .imageUrls(List.of())
                .build();
        return noticeRepository.save(Notice.from(crawled));
    }

    private Notice saveNoticeWithDeadline(String sourceType, String sourceId,
                                          String title, LocalDate postedAt, LocalDate deadline) {
        CrawledNotice crawled = CrawledNotice.builder()
                .articleNo(UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .sourceType(sourceType)
                .sourceId(sourceId)
                .category("장학")
                .title(title)
                .department("테스트학과")
                .postedAt(postedAt.toString())
                .url("https://test.catholic.ac.kr")
                .bodyText("")
                .imageUrls(List.of())
                .build();
        Notice notice = Notice.from(crawled);
        notice.applySummary(new AiSummaryResult(List.of(), deadline != null ? deadline.toString() : null));
        return noticeRepository.save(notice);
    }

    // ── 테스트 케이스 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("NS-1: 인증 없이 요청하면 401 반환")
    void ns1_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/notices/search").param("q", "장학"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("NS-2: q가 빈 문자열이면 400 INVALID_INPUT")
    void ns2_emptyKeyword() throws Exception {
        mockMvc.perform(get("/api/notices/search").param("q", "")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("검색어를 입력해주세요"));
    }

    @Test
    @DisplayName("NS-3: q가 공백만이면 400 INVALID_INPUT")
    void ns3_blankKeyword() throws Exception {
        mockMvc.perform(get("/api/notices/search").param("q", "   ")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("NS-2b: q 길이 101자 초과하면 400 INVALID_INPUT")
    void ns2b_keywordTooLong() throws Exception {
        String longQ = "a".repeat(101);
        mockMvc.perform(get("/api/notices/search").param("q", longQ)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("검색어는 100자 이하여야 합니다"));
    }

    @Test
    @DisplayName("NS-2c: q에 SQL 특수문자 포함 시 400 INVALID_INPUT")
    void ns2c_sqlSpecialChars() throws Exception {
        mockMvc.perform(get("/api/notices/search").param("q", "장학'; DROP TABLE--")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("NS-4: 매칭 공지 없으면 빈 배열 반환")
    void ns4_noMatch() throws Exception {
        saveNotice("MAIN", null, "전혀 다른 제목", LocalDate.now());

        mockMvc.perform(get("/api/notices/search").param("q", "국가장학")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("NS-5: MAIN 공지 제목 LIKE 매칭이면 결과에 포함")
    void ns5_mainNoticeMatched() throws Exception {
        saveNotice("MAIN", null, "2026학년도 국가장학금 안내", LocalDate.now());

        mockMvc.perform(get("/api/notices/search").param("q", "국가장학")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("2026학년도 국가장학금 안내"));
    }

    @Test
    @DisplayName("NS-6: 제1전공 학과 공지 제목 매칭이면 결과에 포함")
    void ns6_majorNoticeMatched() throws Exception {
        // userA의 major = 컴퓨터정보공학
        saveNotice("DEPARTMENT", "CSIE", "컴퓨터정보공학 장학금 안내", LocalDate.now());

        mockMvc.perform(get("/api/notices/search").param("q", "장학금")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("NS-7: 제2전공 학과 공지 매칭 (전공심화 아님)이면 결과에 포함")
    void ns7_secondMajorNoticeMatched() throws Exception {
        // userA의 secondMajor = 인공지능
        saveNotice("DEPARTMENT", "AI", "인공지능 장학금 안내", LocalDate.now());

        mockMvc.perform(get("/api/notices/search").param("q", "장학금")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("NS-8: secondMajor=전공심화이면 해당 학과 공지 미포함")
    void ns8_secondMajorIsJeonGongSimhwa() throws Exception {
        // userB의 secondMajor = 전공심화 → AI 학과 공지 제외
        saveNotice("DEPARTMENT", "AI", "인공지능 장학금 안내", LocalDate.now());

        mockMvc.perform(get("/api/notices/search").param("q", "장학금")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("NS-9: 다른 학과 공지는 결과에 미포함")
    void ns9_otherDepartmentNoticeExcluded() throws Exception {
        saveNotice("DEPARTMENT", "BUSINESS", "경영학과 장학금 안내", LocalDate.now());

        mockMvc.perform(get("/api/notices/search").param("q", "장학금")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("NS-10: 결과가 size 초과이면 hasNext=true")
    void ns10_hasNextTrue() throws Exception {
        for (int i = 0; i < 3; i++) {
            saveNotice("MAIN", null, "국가장학금 안내 " + i, LocalDate.now().minusDays(i));
        }

        mockMvc.perform(get("/api/notices/search")
                        .param("q", "국가장학금").param("size", "2")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("NS-11: 마지막 페이지이면 hasNext=false")
    void ns11_hasNextFalse() throws Exception {
        saveNotice("MAIN", null, "국가장학금 안내", LocalDate.now());

        mockMvc.perform(get("/api/notices/search")
                        .param("q", "국가장학금").param("size", "10")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("NS-12: deadline 있는 공지는 dDay 없이 deadlineAt 반환")
    void ns12_deadlineAtPresentWithoutDday() throws Exception {
        LocalDate future = LocalDate.now().plusDays(5);
        saveNoticeWithDeadline("MAIN", null, "국가장학금 마감 안내", LocalDate.now(), future);

        mockMvc.perform(get("/api/notices/search").param("q", "국가장학금")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].dDay").doesNotExist())
                .andExpect(jsonPath("$.content[0].deadlineAt").value(future.toString()));
    }

    @Test
    @DisplayName("NS-13: deadline 없는 공지는 dDay 미반환")
    void ns13_dDayNotReturned() throws Exception {
        saveNotice("MAIN", null, "국가장학금 안내", LocalDate.now());

        mockMvc.perform(get("/api/notices/search").param("q", "국가장학금")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].dDay").doesNotExist());
    }

    @Test
    @DisplayName("NS-14: 동일 날짜 공지는 제목 알파벳 오름차순 정렬")
    void ns14_sortOrder() throws Exception {
        LocalDate same = LocalDate.now();
        saveNotice("MAIN", null, "장학금 나 안내", same);
        saveNotice("MAIN", null, "장학금 가 안내", same);

        mockMvc.perform(get("/api/notices/search").param("q", "장학금")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("장학금 가 안내"))
                .andExpect(jsonPath("$.content[1].title").value("장학금 나 안내"));
    }
}
