package org.cathori.backend.notice.infra.crawling.source;

public enum MainSource {
    MAIN("https://www.catholic.ac.kr/ko/campuslife/notice.do");

    private final String url;

    MainSource(String url) { this.url = url; }

    public String getUrl() { return url; }
}