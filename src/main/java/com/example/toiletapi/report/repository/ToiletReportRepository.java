package com.example.toiletapi.report.repository;
import com.example.toiletapi.report.model.*;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
public interface ToiletReportRepository extends JpaRepository<ToiletReport, Long> {
    List<ToiletReport> findByToiletIdInAndStatusAndReportTypeOrderByCreatedAtAsc(
            Collection<Long> toiletIds, ReportStatus status, String reportType);
    boolean existsByActiveRequestKey(String activeRequestKey);
    Optional<ToiletReport> findBySubmissionKey(String submissionKey);
    List<ToiletReport> findByReporterUserIdOrderByCreatedAtDesc(Long reporterUserId);
    Optional<ToiletReport> findByIdAndReporterUserId(Long id, Long reporterUserId);
    @Query("""
            select r from ToiletReport r where r.reporterUserId = :owner
              and (:status is null or r.status = :status)
              and (:from is null or r.createdAt >= :from)
              and (:to is null or r.createdAt < :to)
            """)
    List<ToiletReport> findOwnerHistory(@Param("owner") Long owner, @Param("status") ReportStatus status,
            @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to, Pageable pageable);
    @Query("""
            select new com.example.toiletapi.report.dto.ReportStatusCount(r.status, count(r))
            from ToiletReport r where r.reporterUserId = :owner
              and (:from is null or r.createdAt >= :from)
              and (:to is null or r.createdAt < :to)
            group by r.status
            """)
    List<com.example.toiletapi.report.dto.ReportStatusCount> countOwnerHistory(@Param("owner") Long owner,
            @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);
    List<ToiletReport> findByStatusOrderByCreatedAtAsc(ReportStatus status);
    List<ToiletReport> findTop5ByStatusOrderByCreatedAtAsc(ReportStatus status);
    long countByStatus(ReportStatus status);
    @Query(value = """
            select r from ToiletReport r where r.status = :status and (
                :keyword = '' or lower(r.proposedName) like lower(concat('%', :keyword, '%')) or exists (
                    select t.id from Toilet t where t.id = r.toiletId
                    and lower(t.name) like lower(concat('%', :keyword, '%'))
                )
            ) order by r.createdAt asc
            """, countQuery = """
            select count(r) from ToiletReport r where r.status = :status and (
                :keyword = '' or lower(r.proposedName) like lower(concat('%', :keyword, '%')) or exists (
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
              and (:keyword = '' or lower(r.proposedName) like lower(concat('%', :keyword, '%')) or exists (
                select t.id from Toilet t where t.id = r.toiletId
                and lower(t.name) like lower(concat('%', :keyword, '%'))
              ))
            """, countQuery = """
            select count(r) from ToiletReport r where (:status is null or r.status = :status)
              and (:from is null or r.createdAt >= :from)
              and (:to is null or r.createdAt < :to)
              and (:keyword = '' or lower(r.proposedName) like lower(concat('%', :keyword, '%')) or exists (
                select t.id from Toilet t where t.id = r.toiletId
                and lower(t.name) like lower(concat('%', :keyword, '%'))
              ))
            """)
    Page<ToiletReport> findByFilters(@Param("status") ReportStatus status, @Param("keyword") String keyword,
                                     @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to,
                                     Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from ToiletReport r where r.id = :id") Optional<ToiletReport> findByIdForUpdate(Long id);
}
