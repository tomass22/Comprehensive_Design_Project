package org.cathori.backend.notice.infra.crawling.source;

public enum DepartmentSource {

    // 기본 패턴: https://{code}.catholic.ac.kr/{code}/community/notice.do
    BUSINESS("business", "경영학과"),
    ACCOUNTING("accounting", "회계학과"),
    GBS("gbs", "국제경영학과"),
    BIOTECH("biotech", "생명공학과"),
    BMCE("bmce", "바이오메디컬화학공학과"),
    AIBME("aibme", "AI의공학과"),
    MBS("mbs", "의생명과학과"),
    BMSW("bmsw", "바이오메디컬소프트웨어학과"),
    CSIE("csie", "컴퓨터정보공학부"),
    MTC("mtc", "미디어기술컨텐츠학과"),
    ICE("ice", "정보통신전자공학부"),
    AI("ai", "인공지능학과"),
    DATASCIENCE("datascience", "데이터사이언스학과"),
    PHARMACY("pharmacy", "약학과"),
    KOREAN("korean", "국어국문학과"),
    PHILOSOPHY("philosophy", "철학과"),
    KOREANHISTORY("koreanhistory", "국사학과"),
    ENGLISH("english", "영어영문학부"),
    CN("cn", "중국언어문화학과"),
    FRENCH("french", "프랑스어문화학과"),
    SOCIALWELFARE("socialwelfare", "사회복지학과"),
    PSYCHOLOGY("psychology", "심리학과"),
    SOCIOLOGY("sociology", "사회학과"),
    CHILDREN("children", "아동학과"),
    SPED("sped", "특수교육과"),
    IS("is", "국제학부"),
    LAW("law", "법학과"),
    ECONOMICS("economics", "경제학과"),
    PA("pa", "행정학과"),
    GLOBALBIZ("globalbiz", "글로벌경영학과"),
    KLC("klc", "한국어문화학과"),
    CHEMISTRY("chemistry", "화학과"),
    MATH("math", "수학과"),
    PHYSICS("physics", "물리학과"),
    ENVI("envi", "에너지환경공학과"),
    DESIGN("design", "공간디자인소비자학과"),
    CLOTHING("clothing", "의류학과"),
    FN("fn", "식품영양학과"),
    MUSIC("music", "음악과"),
    VOICE("voice", "성악과"),
    GAMC("gamc", "예술미디어융합학과"),
    TEACHING("teaching", "교직과"),
    LIBERAL("liberal", "자유전공학부"),

    // 예외 패턴: URL 직접 지정
    JAPANESE("japanese", "일어일본문화",
            "https://japanese.catholic.ac.kr/japanese/major/notice.do"),
    CUK_COLLEGE("catholic-college", "CUK 특화 학부대학",
            "https://catholic-college.catholic.ac.kr/catholic_college/notification/notice.do"),
    MAJOR_CONVERGENCE("major-convergence", "융합전공",
            "https://major-convergence.catholic.ac.kr/major_convergence/notice/notice.do");


    private static final String BASE_URL_TEMPLATE =
            "https://%s.catholic.ac.kr/%s/community/notice.do";

    private final String code;
    private final String displayName;
    private final String customUrl;

    DepartmentSource(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
        this.customUrl = null;
    }

    DepartmentSource(String code, String displayName, String customUrl) {
        this.code = code;
        this.displayName = displayName;
        this.customUrl = customUrl;
    }

    public String getUrl() {
        if (customUrl != null) {
            return customUrl;
        }
        return String.format(BASE_URL_TEMPLATE, code, code);
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }

    public static String findEnumNameByDisplayName(String displayName) {
        return java.util.Arrays.stream(values())
                .filter(d -> d.displayName.equals(displayName))
                .map(Enum::name)
                .findFirst()
                .orElse(null);
    }
}