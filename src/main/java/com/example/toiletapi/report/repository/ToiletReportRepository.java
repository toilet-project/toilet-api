package com.example.toiletapi.report.repository;
import com.example.toiletapi.report.model.*;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
public interface ToiletReportRepository extends JpaRepository<ToiletReport, Long> {
    boolean existsByActiveRequestKey(String activeRequestKey);
    List<ToiletReport> findByReporterUserIdOrderByCreatedAtDesc(Long reporterUserId);
    List<ToiletReport> findByStatusOrderByCreatedAtAsc(ReportStatus status);
    List<ToiletReport> findTop5ByStatusOrderByCreatedAtAsc(ReportStatus status);
    long countByStatus(ReportStatus status);
    @Query(value = """
            select r from ToiletReport r where r.status = :status and (
                :keyword = '' or exists (
                    select t.id from Toilet t where t.id = r.toiletId
                    and lower(t.name) like lower(concat('%', :keyword, '%'))
                )
            ) order by r.createdAt asc
            """, countQuery = """
            select count(r) from ToiletReport r where r.status = :status and (
                :keyword = '' or exists (
                    select t.id from Toilet t where t.id = r.toiletId
                    and lower(t.name) like lower(concat('%', :keyword, '%'))
                )
            )
            """)
    Page<ToiletReport> findPendingByToiletName(@Param("status") ReportStatus status, @Param("keyword") String keyword, Pageable pageable);
    @Query(value = """
            select r from ToiletReport r where (:status is null or r.status = :status)
              and (:from is null or r.createdAt >= :from)
              and (:to is null or r.createdAt < :to)
              and (:keyword = '' or exists (
                select t.id from Toilet t where t.id = r.toiletId
                and lower(t.name) like lower(concat('%', :keyword, '%'))
              ))
            """, countQuery = """
            select count(r) from ToiletReport r where (:status is null or r.status = :status)
              and (:from is null or r.createdAt >= :from)
              and (:to is null or r.createdAt < :to)
              and (:keyword = '' or exists (
                select t.id from Toilet t where t.id = r.toiletId
                and lower(t.name) like lower(concat('%', :keyword, '%'))
              ))
            """)
    Page<ToiletReport> findByFilters(@Param("status") ReportStatus status, @Param("keyword") String keyword,
                                     @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to,
                                     Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from ToiletReport r where r.id = :id") Optional<ToiletReport> findByIdForUpdate(Long id);
}
