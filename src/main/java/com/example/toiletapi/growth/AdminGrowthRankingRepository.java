package com.example.toiletapi.growth;

import com.example.toiletapi.auth.privacy.EmailMask;
import com.geupddong.growth.GrowthLevelPolicy;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Current GrowthLedger balances, including missing (=zero) accounts. No projections or writes. */
@Repository
public class AdminGrowthRankingRepository {
    private final JdbcTemplate jdbc;
    public AdminGrowthRankingRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    private static final String COHORT="u.status IN ('ACTIVE','SUSPENDED')";
    private static final String SEARCH="(? IS NULL OR LOWER(COALESCE(display_name,'')) LIKE ? ESCAPE '!' OR LOWER(TRIM(email))=? OR email_lookup_hash=?)";
    private static final String RANKED="""
            WITH ranked AS (
              SELECT u.user_id,u.display_name,u.email,u.email_masked,u.email_lookup_hash,u.status,
                     COALESCE(g.total_xp,0) AS total_xp,
                     RANK() OVER (ORDER BY COALESCE(g.total_xp,0) DESC) AS position
              FROM app_user u LEFT JOIN growth_account g ON g.user_id=u.user_id
              WHERE u.status IN ('ACTIVE','SUSPENDED')
            )
            SELECT user_id,display_name,email,email_masked,status,total_xp,position
            FROM ranked WHERE
            """+SEARCH;
    static final String PAGE_DESC=RANKED+" ORDER BY total_xp DESC,user_id ASC LIMIT ? OFFSET ?";
    static final String PAGE_ASC=RANKED+" ORDER BY total_xp ASC,user_id ASC LIMIT ? OFFSET ?";
    static final String COUNT="SELECT COUNT(*) FROM app_user u WHERE "+COHORT+" AND "+SEARCH;
    static final String DISTRIBUTION="""
            SELECT rank_index,COUNT(*) AS members FROM (
              SELECT CASE WHEN COALESCE(g.total_xp,0)<? THEN 0
                          WHEN COALESCE(g.total_xp,0)<? THEN 1
                          WHEN COALESCE(g.total_xp,0)<? THEN 2
                          WHEN COALESCE(g.total_xp,0)<? THEN 3
                          WHEN COALESCE(g.total_xp,0)<? THEN 4
                          WHEN COALESCE(g.total_xp,0)<? THEN 5 ELSE 6 END AS rank_index
              FROM app_user u LEFT JOIN growth_account g ON g.user_id=u.user_id
              WHERE u.status IN ('ACTIVE','SUSPENDED')
            ) levels GROUP BY rank_index
            """;
    public List<AdminGrowthRankingModels.Item> page(String keyword,String lookupHash,boolean ascending,int size,long offset) {
        Object[] search=searchParameters(keyword,lookupHash);
        List<Object> args=new ArrayList<>(Arrays.asList(search));args.add(size);args.add(offset);
        return jdbc.query(ascending?PAGE_ASC:PAGE_DESC,args.toArray(),
                new int[]{Types.VARCHAR,Types.VARCHAR,Types.VARCHAR,Types.VARCHAR,Types.INTEGER,Types.BIGINT},(rs,n)->{
            long xp=rs.getLong("total_xp");var tier=GrowthLevelPolicy.tier(xp);
            String legacy=rs.getString("email");
            return new AdminGrowthRankingModels.Item(Long.toString(rs.getLong("user_id")),rs.getString("display_name"),
                    EmailMask.mask(legacy==null?rs.getString("email_masked"):legacy),rs.getString("status"),xp,
                    GrowthLevelPolicy.level(xp),tier.rank(),tier.rankName(),rs.getLong("position"));
        });
    }
    public long count(String keyword,String lookupHash) {
        Long count=jdbc.queryForObject(COUNT,searchParameters(keyword,lookupHash),
                new int[]{Types.VARCHAR,Types.VARCHAR,Types.VARCHAR,Types.VARCHAR},Long.class);
        return count==null?0:count;
    }
    public long[] distribution() {
        long[] counts=new long[GrowthLevelPolicy.TIERS.size()];
        Object[] boundaries=GrowthLevelPolicy.TIERS.stream().skip(1).map(GrowthLevelPolicy.Tier::minXp).toArray();
        jdbc.query(DISTRIBUTION,rs->{counts[rs.getInt("rank_index")]=rs.getLong("members");},boundaries);
        return counts;
    }
    private static Object[] searchParameters(String keyword,String hash) {
        String normalized=keyword==null?null:keyword.toLowerCase(Locale.ROOT);
        String escaped=normalized==null?null:normalized.replace("!","!!").replace("%","!%").replace("_","!_");
        return new Object[]{normalized,escaped==null?null:"%"+escaped+"%",normalized,hash};
    }
}
