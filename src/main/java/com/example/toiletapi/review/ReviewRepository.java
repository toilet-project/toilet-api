package com.example.toiletapi.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {
    private final JdbcTemplate jdbc;
    public ReviewRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    record Author(String status, long authVersion) { }
    record Facility(String name, Double latitude, Double longitude) { }
    record Submission(long reviewId, String hash) { }
    record Guard(LocalDateTime lastCreatedAt, LocalDate date, int count) { }
    record Row(long id, long toiletId, Long authorId, boolean detached, int satisfaction, int cleanliness,
               boolean paper, int waitMinutes, String comment, long version, LocalDateTime createdAt,
               LocalDateTime updatedAt, String toiletName, String authorStatus, String displayName) { }
    private static final String SELECT = """
            SELECT r.*, t.name AS toilet_name, u.status AS author_status, u.display_name
              FROM toilet_review r JOIN toilet t ON t.toilet_id=r.toilet_id
              LEFT JOIN app_user u ON u.user_id=r.author_user_id
            """;
    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getLong("review_id"), rs.getLong("toilet_id"),
            rs.getObject("author_user_id", Long.class), rs.getBoolean("author_detached"), rs.getInt("satisfaction"),
            rs.getInt("cleanliness"), rs.getBoolean("paper_available"), rs.getInt("wait_minutes"), rs.getString("comment"),
            rs.getLong("version"), rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class),
            rs.getString("toilet_name"), rs.getString("author_status"), rs.getString("display_name"));

    Optional<Author> lockAuthor(long id) {
        return jdbc.query("SELECT status,auth_version FROM app_user WHERE user_id=? FOR UPDATE",
                (rs, n) -> new Author(rs.getString(1), rs.getLong(2)), id).stream().findFirst();
    }
    Optional<Facility> facility(long id, boolean lock) {
        return jdbc.query("SELECT name,latitude,longitude FROM toilet WHERE toilet_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Facility(rs.getString(1), nullableDouble(rs,2), nullableDouble(rs,3)), id).stream().findFirst();
    }
    Optional<Row> find(long id) { return jdbc.query(SELECT + " WHERE r.review_id=?", ROW, id).stream().findFirst(); }
    Optional<Submission> submission(long user, String key) {
        return jdbc.query("SELECT review_id,content_hash FROM toilet_review_submission WHERE user_id=? AND request_key=?",
                (rs,n) -> new Submission(rs.getLong(1),rs.getString(2)), user,key).stream().findFirst();
    }
    void remember(long user, String key, String hash, long review) {
        jdbc.update("INSERT INTO toilet_review_submission(user_id,request_key,content_hash,review_id) VALUES(?,?,?,?)",user,key,hash,review);
    }
    Optional<Guard> guard(long user) {
        return jdbc.query("SELECT last_created_at,daily_date,daily_count FROM toilet_review_write_guard WHERE user_id=?",
                (rs,n) -> new Guard(rs.getObject(1,LocalDateTime.class),rs.getObject(2,LocalDate.class),rs.getInt(3)),user).stream().findFirst();
    }
    void recordCreation(long user, LocalDateTime now, int count, boolean exists) {
        if (exists) jdbc.update("UPDATE toilet_review_write_guard SET last_created_at=?,daily_date=?,daily_count=? WHERE user_id=?",now,now.toLocalDate(),count,user);
        else jdbc.update("INSERT INTO toilet_review_write_guard(user_id,last_created_at,daily_date,daily_count) VALUES(?,?,?,?)",user,now,now.toLocalDate(),count);
    }
    Optional<LocalDateTime> nextAllowedAt(long user,long toilet) {
        return jdbc.query("SELECT next_allowed_at FROM toilet_review_toilet_guard WHERE user_id=? AND toilet_id=?",
                (rs,n)->rs.getObject(1,LocalDateTime.class),user,toilet).stream().findFirst();
    }
    Optional<Row> recentOwned(long user,long toilet,LocalDateTime since) {
        return jdbc.query(SELECT+" WHERE r.author_user_id=? AND r.toilet_id=? AND r.author_detached=FALSE AND r.created_at>? ORDER BY r.created_at DESC,r.review_id DESC LIMIT 1",
                ROW,user,toilet,since).stream().findFirst();
    }
    void recordToiletCreation(long user,long toilet,LocalDateTime now) {
        // Called under the author row lock; expire this user's throttle metadata, never review content.
        jdbc.update("DELETE FROM toilet_review_toilet_guard WHERE user_id=? AND next_allowed_at<=?",user,now);
        jdbc.update("INSERT INTO toilet_review_toilet_guard(user_id,toilet_id,next_allowed_at) VALUES(?,?,?)",user,toilet,now.plusHours(24));
    }
    long insert(long user, long toilet, ReviewRules.Content c, LocalDateTime now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO toilet_review(toilet_id,author_user_id,satisfaction,cleanliness,paper_available,
                    wait_minutes,comment,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            Object[] values = {toilet,user,c.satisfaction(),c.cleanliness(),c.paperAvailable(),c.waitMinutes(),c.comment(),now,now};
            for (int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            return statement;
        },keys);
        return java.util.Objects.requireNonNull(keys.getKey()).longValue();
    }
    int edit(long id, long user, long version, ReviewRules.Content c, LocalDateTime now) {
        return jdbc.update("""
                UPDATE toilet_review SET satisfaction=?,cleanliness=?,paper_available=?,wait_minutes=?,comment=?,
                updated_at=?,version=version+1 WHERE review_id=? AND author_user_id=? AND version=? AND author_detached=FALSE
                """,c.satisfaction(),c.cleanliness(),c.paperAvailable(),c.waitMinutes(),c.comment(),now,id,user,version);
    }
    int detach(long id, long user, long version) {
        int changed = jdbc.update("""
                UPDATE toilet_review SET author_user_id=NULL,author_detached=TRUE,version=version+1
                WHERE review_id=? AND author_user_id=? AND version=? AND author_detached=FALSE
                """,id,user,version);
        if (changed == 1) jdbc.update("DELETE FROM toilet_review_submission WHERE review_id=?",id);
        // Content timestamps intentionally remain unchanged: unlink must not make old wait data 'fresh'.
        return changed;
    }
    List<Row> page(Long owner, Long toilet, LocalDate from, LocalDate to, ReviewCursor cursor, int size) {
        var sql = new StringBuilder(SELECT).append(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (owner != null) {sql.append(" AND r.author_user_id=? AND r.author_detached=FALSE");args.add(owner);}
        if (toilet != null) {sql.append(" AND r.toilet_id=?");args.add(toilet);}
        if (from != null) {sql.append(" AND r.created_at>=?");args.add(from.atStartOfDay());}
        if (to != null) {sql.append(" AND r.created_at<?");args.add(to.plusDays(1).atStartOfDay());}
        if (cursor != null) {
            sql.append(" AND (r.created_at<? OR (r.created_at=? AND r.review_id<?))");
            args.add(cursor.createdAt());args.add(cursor.createdAt());args.add(cursor.id());
        }
        sql.append(" ORDER BY r.created_at DESC,r.review_id DESC LIMIT ?");args.add(size+1);
        return jdbc.query(sql.toString(),ROW,args.toArray());
    }
    ReviewModels.Summary summary(long toilet, LocalDateTime now) {
        var sums = jdbc.queryForMap("""
                SELECT COUNT(*) AS n,AVG(satisfaction) AS rating,AVG((satisfaction+cleanliness)/2.0) AS average_rating,
                AVG(CASE WHEN created_at>=? THEN paper_available*100.0 ELSE NULL END) AS paper_percent,
                COUNT(CASE WHEN created_at>=? THEN 1 ELSE NULL END) AS paper_n
                FROM toilet_review WHERE toilet_id=?
                """,now.minusDays(7),now.minusDays(7),toilet);
        var latest = jdbc.query("""
                SELECT wait_minutes,created_at FROM toilet_review WHERE toilet_id=? AND created_at>=?
                ORDER BY created_at DESC,review_id DESC LIMIT 1
                """,(rs,n)->new Object[]{rs.getInt(1),rs.getObject(2,LocalDateTime.class)},toilet,now.minusHours(1));
        return new ReviewModels.Summary(((Number)sums.get("n")).longValue(),rounded(sums.get("rating")),
                rounded(sums.get("average_rating")),rounded(sums.get("paper_percent")),((Number)sums.get("paper_n")).longValue(),
                latest.isEmpty()?null:(Integer)latest.getFirst()[0],latest.isEmpty()?null:((LocalDateTime)latest.getFirst()[1]).atOffset(java.time.ZoneOffset.ofHours(9)));
    }
    private static Double rounded(Object value) { return value == null ? null : Math.round(((Number)value).doubleValue()*10)/10.0; }
    private static Double nullableDouble(ResultSet rs,int column) throws SQLException {
        double value=rs.getDouble(column);return rs.wasNull()?null:value;
    }
}
