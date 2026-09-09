package org.cathori.backend.notice.infra;

import lombok.RequiredArgsConstructor;
import org.cathori.backend.notice.application.query.BookmarkedNoticeQuery;
import org.cathori.backend.notice.application.query.NoticeFeedPort;
import org.cathori.backend.notice.application.query.NoticeFeedQuery;
import org.cathori.backend.notice.application.query.NoticeRow;
import org.cathori.backend.notice.application.query.NoticeSearchQuery;
import org.cathori.backend.notice.application.query.NoticeSearchRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

@Repository
@RequiredArgsConstructor
public class NoticeFeedAdapter implements NoticeFeedPort {

    private final JdbcTemplate jdbcTemplate;

    // DB컬럼 -> 자바객체로 전환
    private static final RowMapper<NoticeRow> ROW_MAPPER = (rs, rowNum) -> new NoticeRow(
            rs.getLong("id"),
            rs.getString("source_type"),
            rs.getString("category"),
            rs.getString("title"),
            rs.getString("department"),
            rs.getObject("posted_at", LocalDate.class),
            rs.getObject("deadline_at", LocalDate.class),
            rs.getString("ai_summary"),
            rs.getString("ai_summary_status"),
            rs.getString("url"),
            rs.getLong("view_count"),
            rs.getBoolean("is_bookmarked")
    );

    @Override
    public List<NoticeRow> findFeed(NoticeFeedQuery q) {
        boolean hasCategory = q.category() != null && !q.category().isBlank();
        boolean hasTags = !q.tags().isEmpty();

        QueryAndParams qp;
        if (!hasCategory && !hasTags) {
            qp = buildCase1(q);
        } else if (hasCategory && !hasTags) {
            qp = buildCase2(q);
        } else if (!hasCategory) {
            qp = buildCase3(q);
        } else {
            qp = buildCase4(q);
        }

        return jdbcTemplate.query(qp.sql(), ROW_MAPPER, qp.params().toArray());

    }

    @Override
    public List<NoticeRow> findBookmarked(BookmarkedNoticeQuery q) {
        List<Object> params = new ArrayList<>();
        params.add(q.userId());
        params.add(q.size() + 1);
        params.add((long) q.page() * q.size());

        String sql =
                "SELECT n.*, true AS is_bookmarked " +
                "FROM bookmark b " +
                "JOIN notices n ON n.id = b.notice_id " +
                "WHERE b.user_id = ? " +
                "ORDER BY n.posted_at DESC, n.title ASC LIMIT ? OFFSET ?";

        return jdbcTemplate.query(sql, ROW_MAPPER, params.toArray());
    }

    // Case1: category도 없고 tags도 없음 — MAIN + 제1전공 + 제2전공(전공심화 제외)
    private QueryAndParams buildCase1(NoticeFeedQuery q) {
        List<Object> params = new ArrayList<>();
        params.add(q.userId());

        StringBuilder sql = new StringBuilder(
                "SELECT n.*, CASE WHEN b.id IS NOT NULL THEN true ELSE false END AS is_bookmarked " +
                "FROM notices n " +
                "LEFT JOIN bookmark b ON b.notice_id = n.id AND b.user_id = ? " +
                "WHERE n.source_type = 'MAIN'"
        );

        sql.append(" OR (n.source_type = 'DEPARTMENT' AND n.source_id = ?)");
        params.add(q.major());

        if (q.secondMajor() != null && !"전공심화".equals(q.secondMajor())) {
            sql.append(" OR (n.source_type = 'DEPARTMENT' AND n.source_id = ?)");
            params.add(q.secondMajor());
        }

        appendOrderAndPage(sql, params, q);
        return new QueryAndParams(sql.toString(), params);
    }

    // Case2: category만 있음 — MAIN에서 category 필터
    private QueryAndParams buildCase2(NoticeFeedQuery q) {
        List<Object> params = new ArrayList<>();
        params.add(q.userId());

        StringBuilder sql = new StringBuilder(
                "SELECT n.*, CASE WHEN b.id IS NOT NULL THEN true ELSE false END AS is_bookmarked " +
                "FROM notices n " +
                "LEFT JOIN bookmark b ON b.notice_id = n.id AND b.user_id = ? " +
                "WHERE n.source_type = 'MAIN' AND n.category = ?"
        );
        params.add(q.category());

        appendOrderAndPage(sql, params, q);
        return new QueryAndParams(sql.toString(), params);
    }

    // Case3: tags만 있음 — MAIN + 학과 전체에서 제목 LIKE 필터
    private QueryAndParams buildCase3(NoticeFeedQuery q) {
        List<Object> params = new ArrayList<>();
        params.add(q.userId());

        StringBuilder sql = new StringBuilder(
                "SELECT n.*, CASE WHEN b.id IS NOT NULL THEN true ELSE false END AS is_bookmarked " +
                "FROM notices n " +
                "LEFT JOIN bookmark b ON b.notice_id = n.id AND b.user_id = ? " +
                "WHERE ("
        );
        appendTagLikes(sql, params, q.tags(), "n.title");
        sql.append(") AND (n.source_type = 'MAIN'");

        sql.append(" OR (n.source_type = 'DEPARTMENT' AND n.source_id = ?)");
        params.add(q.major());

        if (q.secondMajor() != null && !"전공심화".equals(q.secondMajor())) {
            sql.append(" OR (n.source_type = 'DEPARTMENT' AND n.source_id = ?)");
            params.add(q.secondMajor());
        }
        sql.append(")");

        appendOrderAndPage(sql, params, q);
        return new QueryAndParams(sql.toString(), params);
    }

    // Case4: category + tags 둘 다 있음 — 태그 우선(priority 1) UNION category(priority 2)
    private QueryAndParams buildCase4(NoticeFeedQuery q) {
        List<Object> params = new ArrayList<>();

        // Part1: 태그 매칭 — MAIN + 제1전공 + 제2전공(전공심화 제외) 범위 내에서 제목 LIKE 필터
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM (" +
                "SELECT n.id, n.source_type, n.category, n.title, n.department, n.posted_at, n.deadline_at, " +
                "       n.ai_summary, n.ai_summary_status, n.url, n.view_count, " +
                "       CASE WHEN b.id IS NOT NULL THEN true ELSE false END AS is_bookmarked, " +
                "       1 AS priority " +
                "FROM notices n " +
                "LEFT JOIN bookmark b ON b.notice_id = n.id AND b.user_id = ? " +
                "WHERE ("
        );
        params.add(q.userId());
        appendTagLikes(sql, params, q.tags(), "n.title");
        sql.append(") AND (n.source_type = 'MAIN'");
        sql.append(" OR (n.source_type = 'DEPARTMENT' AND n.source_id = ?)");
        params.add(q.major());
        if (q.secondMajor() != null && !"전공심화".equals(q.secondMajor())) {
            sql.append(" OR (n.source_type = 'DEPARTMENT' AND n.source_id = ?)");
            params.add(q.secondMajor());
        }
        sql.append(")");

        // Part2: category 매칭 MAIN, 태그 미매칭
        sql.append(
                " UNION " +
                "SELECT n.id, n.source_type, n.category, n.title, n.department, n.posted_at, n.deadline_at, " +
                "       n.ai_summary, n.ai_summary_status, n.url, n.view_count, " +
                "       CASE WHEN b.id IS NOT NULL THEN true ELSE false END AS is_bookmarked, " +
                "       2 AS priority " +
                "FROM notices n " +
                "LEFT JOIN bookmark b ON b.notice_id = n.id AND b.user_id = ? " +
                "WHERE n.source_type = 'MAIN' AND n.category = ? AND NOT ("
        );
        params.add(q.userId());
        params.add(q.category());
        appendTagLikes(sql, params, q.tags(), "n.title");
        sql.append(")");

        sql.append(") sub ORDER BY sub.priority ASC, sub.posted_at DESC, sub.title ASC LIMIT ? OFFSET ?");
        params.add(q.size() + 1);
        params.add((long) q.page() * q.size());

        return new QueryAndParams(sql.toString(), params);
    }

    private void appendTagLikes(StringBuilder sql, List<Object> params, List<String> tags, String column) {
        StringJoiner joiner = new StringJoiner(" OR ");
        for (String tag : tags) {
            joiner.add(column + " LIKE '%' || ? || '%'");
            params.add(tag);
        }
        sql.append(joiner);
    }

    private void appendOrderAndPage(StringBuilder sql, List<Object> params, NoticeFeedQuery q) {
        sql.append(" ORDER BY n.posted_at DESC, n.title ASC LIMIT ? OFFSET ?");
        params.add(q.size() + 1);
        params.add((long) q.page() * q.size());
    }

    @Override
    public List<NoticeSearchRow> findSearch(NoticeSearchQuery q) {
        List<Object> params = new ArrayList<>();

        StringBuilder sql = new StringBuilder(
                "SELECT n.id, n.category, n.title, n.department, n.posted_at, n.deadline_at, " +
                "       CASE WHEN b.id IS NOT NULL THEN true ELSE false END AS is_bookmarked " +
                "FROM notices n " +
                "LEFT JOIN bookmark b ON b.notice_id = n.id AND b.user_id = ? " +
                "WHERE n.title LIKE '%' || ? || '%' " +
                "AND (n.source_type = 'MAIN'"
        );
        params.add(q.userId());
        params.add(q.keyword());

        sql.append(" OR (n.source_type = 'DEPARTMENT' AND n.source_id = ?)");
        params.add(q.major());

        if (q.secondMajor() != null && !"전공심화".equals(q.secondMajor())) {
            sql.append(" OR (n.source_type = 'DEPARTMENT' AND n.source_id = ?)");
            params.add(q.secondMajor());
        }

        sql.append(") ORDER BY n.posted_at DESC, n.title ASC LIMIT ? OFFSET ?");
        params.add(q.size() + 1);
        params.add((long) q.page() * q.size());

        RowMapper<NoticeSearchRow> mapper = (rs, rowNum) -> new NoticeSearchRow(
                rs.getLong("id"),
                rs.getString("category"),
                rs.getString("title"),
                rs.getString("department"),
                rs.getObject("posted_at", LocalDate.class),
                rs.getObject("deadline_at", LocalDate.class),
                rs.getBoolean("is_bookmarked")
        );

        return jdbcTemplate.query(sql.toString(), mapper, params.toArray());
    }

    private record QueryAndParams(String sql, List<Object> params) {}
}
