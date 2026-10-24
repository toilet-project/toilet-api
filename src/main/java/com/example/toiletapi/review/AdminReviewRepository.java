package com.example.toiletapi.review;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AdminReviewRepository {
    private final JdbcTemplate jdbc;
    public AdminReviewRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Row(long id,long toiletId,String toiletName,String toiletVisibility,int satisfaction,int cleanliness,
               boolean paper,int waitMinutes,String authorStatus,String displayName,boolean detached,boolean missingAuthor,
               LocalDateTime createdAt,LocalDateTime updatedAt,boolean hasComment,String comment) { }
    private static final String FIELDS="""
            r.review_id,r.toilet_id,r.author_user_id,r.author_detached,r.satisfaction,r.cleanliness,
            r.paper_available,r.wait_minutes,r.created_at,r.updated_at,
            """;
    private static final String RELATED="""
            , t.name AS toilet_name,t.visibility_status AS toilet_visibility,
            u.status AS author_status,u.display_name
            """;
    private static final String JOIN="""
             LEFT JOIN toilet t ON t.toilet_id=r.toilet_id
             LEFT JOIN app_user u ON u.user_id=r.author_user_id
            """;
    private static final RowMapper<Row> SUMMARY=(rs,n)->row(rs,false);
    private static final RowMapper<Row> DETAIL=(rs,n)->row(rs,true);

    List<Row> page(ReviewCursor cursor,int size) {
        // Limit reviews before joining: one bounded query, no total count, no body in list responses.
        var sql=new StringBuilder("SELECT ").append(FIELDS).append("r.has_comment").append(RELATED)
                .append(" FROM (SELECT ").append(FIELDS).append("(r.comment<>'') AS has_comment FROM toilet_review r");
        var args=new ArrayList<Object>();
        if(cursor!=null) {
            sql.append(" WHERE (r.created_at<? OR (r.created_at=? AND r.review_id<?))");
            args.add(cursor.createdAt());args.add(cursor.createdAt());args.add(cursor.id());
        }
        sql.append(" ORDER BY r.created_at DESC,r.review_id DESC LIMIT ?) r").append(JOIN)
                .append(" ORDER BY r.created_at DESC,r.review_id DESC");
        args.add(size+1);
        return jdbc.query(sql.toString(),SUMMARY,args.toArray());
    }
    Optional<Row> find(long id) {
        return jdbc.query("SELECT "+FIELDS+"(r.comment<>'') AS has_comment,r.comment"+RELATED
                +" FROM toilet_review r"+JOIN+" WHERE r.review_id=?",DETAIL,id).stream().findFirst();
    }
    private static Row row(java.sql.ResultSet rs,boolean detail) throws java.sql.SQLException {
        return new Row(rs.getLong("review_id"),rs.getLong("toilet_id"),rs.getString("toilet_name"),rs.getString("toilet_visibility"),
                rs.getInt("satisfaction"),rs.getInt("cleanliness"),rs.getBoolean("paper_available"),rs.getInt("wait_minutes"),
                rs.getString("author_status"),rs.getString("display_name"),rs.getBoolean("author_detached"),
                rs.getObject("author_user_id")==null || rs.getString("author_status")==null,
                rs.getObject("created_at",LocalDateTime.class),rs.getObject("updated_at",LocalDateTime.class),
                rs.getBoolean("has_comment"),detail?rs.getString("comment"):null);
    }
}
