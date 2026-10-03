package com.example.toiletapi.toilet.repository;

/** One predicate shared by map cells, bounded queries and the edge snapshot source. */
public final class MapFilterSql {
    private MapFilterSql() {}

    // SQL collations can compare y/Y and ignore spaces. Match only the detail UI's exact "Y".
    public static final String FLAGS = """
            (CASE WHEN oh.is_open_24h=TRUE
                        AND oh.normalization_status IN ('PARSED','CONFIRMED')
                        AND oh.source_changed=FALSE THEN 1 ELSE 0 END
             + CASE WHEN ASCII(t.has_cctv)=89 AND OCTET_LENGTH(t.has_cctv)=1 THEN 2 ELSE 0 END
             + CASE WHEN ASCII(t.has_diaper_table)=89 AND OCTET_LENGTH(t.has_diaper_table)=1 THEN 4 ELSE 0 END
             + CASE WHEN ASCII(t.has_emergency_bell)=89 AND OCTET_LENGTH(t.has_emergency_bell)=1 THEN 8 ELSE 0 END
             + CASE WHEN COALESCE(t.male_disabled_toilet_count,0)>0
                         OR COALESCE(t.female_disabled_toilet_count,0)>0 THEN 16 ELSE 0 END)
            """;
    public static final String FROM = " FROM toilet t LEFT JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id ";
    public static final String BOUNDS = """
             WHERE t.visibility_status='VISIBLE'
               AND t.latitude BETWEEN :southLat AND :northLat
               AND t.longitude BETWEEN :westLng AND :eastLng
            """;
    public static final String REQUIRED_FLAGS = " AND (" + FLAGS + " & :filterFlags) = :filterFlags ";
    public static final String PUBLIC_POINTS = "SELECT t.toilet_id AS id, t.latitude AS latitude, t.longitude AS longitude, "
            + FLAGS + " AS filterFlags " + FROM + """
             WHERE t.visibility_status='VISIBLE'
               AND t.latitude BETWEEN 32 AND 40 AND t.longitude BETWEEN 124 AND 132
             ORDER BY t.toilet_id
            """;
}
