package com.example.toiletapi.quality.service;

import com.example.toiletapi.global.time.KoreanTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.example.toiletapi.quality.dto.DuplicateNameModels.*;

@Service
@Transactional(readOnly=true)
public class DuplicateNameService {
    private final NamedParameterJdbcTemplate jdbc;
    public DuplicateNameService(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String SELECT = """
        SELECT t.*, e.reason AS hidden_reason, e.occurred_at AS hidden_at,
               r.sigungu_code, CONCAT_WS(' ',r.sido_name,r.sigungu_name) AS region_name
        FROM toilet t LEFT JOIN toilet_visibility_event e ON e.event_id=t.hidden_event_id
        LEFT JOIN current_toilet_region r ON r.toilet_id=t.toilet_id
        """;

    public Page groups(String keyword, boolean includeHidden, int page, int size) {
        return groups(keyword,includeHidden,page,size,Comparison.ALL);
    }
    public Page groups(String keyword, boolean includeHidden, int page, int size, Comparison match) {
        return groups(keyword,includeHidden,page,size,match,0,WorkVisibility.VISIBLE);
    }
    public Page groups(String keyword, boolean includeHidden, int page, int size, Comparison match,long actor,WorkVisibility workVisibility) {
        if(page<0 || page>100000 || size<1 || size>100 || keyword.length()>100) throw new IllegalArgumentException("검색 범위를 확인해 주세요.");
        Objects.requireNonNull(match,"비교 기준이 필요합니다.");
        var p=new MapSqlParameterSource("term","%"+keyword.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%")
            .addValue("offset",(long)page*size).addValue("limit",size).addValue("actor",actor);
        String from="FROM toilet t LEFT JOIN duplicate_name_work_visibility w ON w.group_name=LOWER(TRIM(t.name)) AND w.admin_user_id=:actor "+(match==Comparison.DISTRICT?"JOIN current_toilet_region r ON r.toilet_id=t.toilet_id ":"");
        String condition=switch(match){
            case ALL -> "";
            case COORDINATES -> " AND t.latitude BETWEEN -90 AND 90 AND t.longitude BETWEEN -180 AND 180 AND NOT(t.latitude=0 AND t.longitude=0) ";
            case DISTRICT -> " AND r.sigungu_code REGEXP '^[0-9]{5}$' ";
        };
        String keys=switch(match){case ALL->"";case COORDINATES->",t.latitude,t.longitude";case DISTRICT->",r.sigungu_code";};
        String having=includeHidden?(match==Comparison.ALL?"(COUNT(*)>1 OR SUM(CASE WHEN t.visibility_status='HIDDEN_DUPLICATE' THEN 1 ELSE 0 END)>0)":"COUNT(*)>1"):
            "SUM(CASE WHEN t.visibility_status='VISIBLE' THEN 1 ELSE 0 END)>1";
        having+=switch(Objects.requireNonNull(workVisibility)){
            case ALL->"";case VISIBLE->" AND MAX(CASE WHEN w.work_hidden=TRUE THEN 1 ELSE 0 END)=0";
            case HIDDEN->" AND MAX(CASE WHEN w.work_hidden=TRUE THEN 1 ELSE 0 END)=1";
        };
        String grouped=from+" WHERE NULLIF(TRIM(t.name),'') IS NOT NULL "+condition+" GROUP BY LOWER(TRIM(t.name))"+keys+" HAVING "+having+
            " AND MAX(CASE WHEN t.name LIKE :term ESCAPE '!' OR t.road_address LIKE :term ESCAPE '!' OR t.mng_no LIKE :term ESCAPE '!' THEN 1 ELSE 0 END)=1 ";
        String extra=match==Comparison.COORDINATES?"t.latitude AS match_lat,t.longitude AS match_lng,NULL AS region_code,NULL AS region_label":
            match==Comparison.DISTRICT?"NULL AS match_lat,NULL AS match_lng,r.sigungu_code AS region_code,MAX(CONCAT_WS(' ',r.sido_name,r.sigungu_name)) AS region_label":
            "NULL AS match_lat,NULL AS match_lng,NULL AS region_code,NULL AS region_label";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT MIN(t.toilet_id) "+grouped+") candidates",p,Long.class);
        var items=jdbc.query("SELECT MIN(TRIM(t.name)) AS group_name,COUNT(*) AS total,SUM(CASE WHEN t.visibility_status='HIDDEN_DUPLICATE' THEN 1 ELSE 0 END) AS hidden,MAX(CASE WHEN w.work_hidden=TRUE THEN 1 ELSE 0 END) AS work_hidden,COALESCE(MAX(w.version),0) AS work_version,"+extra+" "+grouped+" ORDER BY total DESC,group_name,match_lat,match_lng,region_code LIMIT :limit OFFSET :offset",p,
            (rs,n)->new Group(rs.getString("group_name"),rs.getLong("total"),rs.getLong("hidden"),rs.getBigDecimal("match_lat"),rs.getBigDecimal("match_lng"),rs.getString("region_code"),rs.getString("region_label"),rs.getBoolean("work_hidden"),rs.getLong("work_version")));
        return new Page(items,page,size,total);
    }
    @Transactional
    public WorkVisibilityResult setWorkVisibility(long actor,WorkVisibilityRequest request){
        if(actor<=0||request.name()==null||request.name().isBlank()||request.name().length()>100||request.hidden()==null||request.expectedVersion()<0)throw new IllegalArgumentException("작업 숨김 설정을 확인해 주세요.");
        var p=new MapSqlParameterSource("actor",actor).addValue("name",request.name().trim().toLowerCase(Locale.ROOT)).addValue("at",KoreanTime.now());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM toilet WHERE LOWER(TRIM(name))=:name",p,Long.class)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"그룹을 찾을 수 없습니다.");
        jdbc.update("INSERT IGNORE INTO duplicate_name_work_visibility(admin_user_id,group_name,work_hidden,version,updated_at) VALUES(:actor,:name,FALSE,0,:at)",p);
        var current=jdbc.queryForObject("SELECT work_hidden,version FROM duplicate_name_work_visibility WHERE admin_user_id=:actor AND group_name=:name FOR UPDATE",p,(r,n)->new WorkVisibilityResult(r.getBoolean(1),r.getLong(2)));
        if(current.version()!=request.expectedVersion())throw new ResponseStatusException(HttpStatus.CONFLICT,"작업 숨김 설정이 변경됐습니다. 새로고침 후 다시 시도해 주세요.");
        if(current.hidden()==request.hidden())return current;
        jdbc.update("UPDATE duplicate_name_work_visibility SET work_hidden=:hidden,version=version+1,updated_at=:at WHERE admin_user_id=:actor AND group_name=:name",p.addValue("hidden",request.hidden()));
        return new WorkVisibilityResult(request.hidden(),current.version()+1);
    }
    public List<Facility> facilities(String name) {
        if(name==null || name.isBlank() || name.length()>100) throw new IllegalArgumentException("시설명을 확인해 주세요.");
        return jdbc.query(SELECT+" WHERE LOWER(TRIM(t.name))=LOWER(TRIM(:name)) ORDER BY t.toilet_id LIMIT 1000",Map.of("name",name),(rs,n)->facility(rs));
    }
    public List<Event> history(long id) {
        return jdbc.query("SELECT * FROM toilet_visibility_event WHERE toilet_id=:id ORDER BY event_id DESC LIMIT 100",Map.of("id",id),
            (rs,n)->new Event(rs.getLong("event_id"),rs.getLong("toilet_id"),rs.getObject("representative_toilet_id",Long.class),rs.getString("action"),rs.getString("reason"),rs.getTimestamp("occurred_at").toLocalDateTime(),rs.getString("snapshot_name"),rs.getString("snapshot_road_address")));
    }
    @Transactional
    public List<Facility> hide(long actor, HideRequest request) {
        return hideChecked(actor,request,false);
    }
    public List<Facility> coordinateFacilities(java.math.BigDecimal latitude,java.math.BigDecimal longitude) {
        if(!validCoordinates(latitude,longitude))throw new IllegalArgumentException("유효한 좌표를 확인해 주세요.");
        return jdbc.query(SELECT+" WHERE t.latitude=:lat AND t.longitude=:lng ORDER BY t.toilet_id",Map.of("lat",latitude,"lng",longitude),(rs,n)->facility(rs));
    }
    @Transactional
    public List<Facility> hideAtCoordinates(long actor,HideRequest request) {
        return hideChecked(actor,request,true);
    }
    private List<Facility> hideChecked(long actor,HideRequest request,boolean sameCoordinates) {
        reason(request.reason());
        var ids=new TreeSet<>(request.toiletIds());
        if(ids.isEmpty() || ids.size()>100 || ids.size()!=request.toiletIds().size() || ids.contains(request.representativeId())) throw new IllegalArgumentException("대표 시설과 숨길 시설을 따로 선택해 주세요.");
        ids.add(request.representativeId());
        // Sorted base-row locks serialize hide/restore and batch capture; never lock an outer join.
        var rows=lock(ids);
        var representative=rows.stream().filter(f->f.id()==request.representativeId()).findFirst().orElseThrow();
        for(var f:rows) {
            if(!Objects.equals(request.expectedVersions().get(f.id()), f.version()) || !"VISIBLE".equals(f.visibilityStatus())) throw conflict();
            if(sameCoordinates) {
                if(!validCoordinates(f.latitude(),f.longitude()) || !validCoordinates(representative.latitude(),representative.longitude())
                    || f.latitude().compareTo(representative.latitude())!=0 || f.longitude().compareTo(representative.longitude())!=0)
                    throw new IllegalArgumentException("같은 좌표 그룹의 시설만 선택해 주세요.");
            } else if(f.name()==null || representative.name()==null || !f.name().trim().equalsIgnoreCase(representative.name().trim())) throw new IllegalArgumentException("같은 이름 그룹의 시설만 선택해 주세요.");
        }
        var targets=new TreeSet<>(ids); targets.remove(request.representativeId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM toilet WHERE visibility_status='HIDDEN_DUPLICATE' AND representative_toilet_id IN (:ids)",Map.of("ids",targets),Long.class)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"다른 시설의 대표로 사용 중인 시설은 숨길 수 없습니다.");
        for(var f:rows) if(targets.contains(f.id())) {
            long event=event(actor,f,request.representativeId(),"HIDE",request.reason());
            jdbc.update("UPDATE toilet SET visibility_status='HIDDEN_DUPLICATE',representative_toilet_id=:rep,hidden_event_id=:event,visibility_version=visibility_version+1 WHERE toilet_id=:id",new MapSqlParameterSource("id",f.id()).addValue("rep",request.representativeId()).addValue("event",event));
            supersede(f.id());
        }
        return sameCoordinates?coordinateFacilities(representative.latitude(),representative.longitude()):facilities(representative.name());
    }
    private static boolean validCoordinates(java.math.BigDecimal lat,java.math.BigDecimal lng) {
        return lat!=null && lng!=null && lat.abs().compareTo(java.math.BigDecimal.valueOf(90))<=0
            && lng.abs().compareTo(java.math.BigDecimal.valueOf(180))<=0 && (lat.signum()!=0 || lng.signum()!=0);
    }
    @Transactional
    public List<Facility> restore(long actor, long id, RestoreRequest request) {
        reason(request.reason());var f=lock(Set.of(id)).getFirst();
        if(!"HIDDEN_DUPLICATE".equals(f.visibilityStatus()) || f.version()!=request.expectedVersion()) throw conflict();
        event(actor,f,f.representativeToiletId(),"RESTORE",request.reason());
        jdbc.update("UPDATE toilet SET visibility_status='VISIBLE',representative_toilet_id=NULL,hidden_event_id=NULL,visibility_version=visibility_version+1 WHERE toilet_id=:id",Map.of("id",id));
        supersede(id);return facilities(f.name());
    }
    private List<Facility> lock(Set<Long> ids) {
        var rows=jdbc.query("SELECT t.*, NULL AS hidden_reason,NULL AS hidden_at,NULL AS sigungu_code,NULL AS region_name FROM toilet t WHERE toilet_id IN (:ids) ORDER BY toilet_id FOR UPDATE",Map.of("ids",ids),(rs,n)->facility(rs));
        if(rows.size()!=ids.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"시설을 찾을 수 없습니다.");return rows;
    }
    private long event(long actor,Facility f,Long rep,String action,String reason) {
        var p=new MapSqlParameterSource("id",f.id()).addValue("rep",rep).addValue("action",action).addValue("reason",reason.trim()).addValue("actor",actor).addValue("at",KoreanTime.now()).addValue("version",f.version()).addValue("name",f.name()).addValue("road",f.roadAddress()).addValue("jibun",f.jibunAddress()).addValue("lat",f.latitude()).addValue("lng",f.longitude());
        var keys=new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO toilet_visibility_event(toilet_id,representative_toilet_id,action,reason,actor_user_id,occurred_at,previous_version,snapshot_name,snapshot_road_address,snapshot_jibun_address,snapshot_latitude,snapshot_longitude)
            VALUES(:id,:rep,:action,:reason,:actor,:at,:version,:name,:road,:jibun,:lat,:lng)
            """,p,keys,new String[]{"event_id"});return Objects.requireNonNull(keys.getKey()).longValue();
    }
    private void supersede(long id) {
        jdbc.update("UPDATE public_data_change_review SET status='SUPERSEDED',status_reason='VISIBILITY_CHANGED',active_toilet_id=NULL,version=version+1 WHERE active_toilet_id=:id AND status='PENDING'",Map.of("id",id));
    }
    private static Facility facility(ResultSet r)throws SQLException {
        var at=r.getTimestamp("hidden_at");return new Facility(r.getLong("toilet_id"),r.getString("name"),r.getString("mng_no"),r.getString("road_address"),r.getString("jibun_address"),r.getBigDecimal("latitude"),r.getBigDecimal("longitude"),r.getString("coordinate_source"),r.getString("open_time"),r.getString("data_source"),r.getString("visibility_status"),r.getObject("representative_toilet_id",Long.class),r.getLong("visibility_version"),r.getString("hidden_reason"),at==null?null:at.toLocalDateTime(),r.getString("sigungu_code"),r.getString("region_name"));
    }
    private static void reason(String value){if(value==null||value.isBlank()||value.length()>500)throw new IllegalArgumentException("500자 이내의 처리 근거를 입력해 주세요.");}
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"시설 상태가 변경됐습니다. 새로고침 후 다시 선택해 주세요.");}
}
