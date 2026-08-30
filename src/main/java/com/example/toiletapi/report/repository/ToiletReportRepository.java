package com.example.toiletapi.report.repository;
import com.example.toiletapi.report.model.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
public interface ToiletReportRepository extends JpaRepository<ToiletReport, Long> {
    boolean existsByActiveRequestKey(String activeRequestKey);
    List<ToiletReport> findByReporterUserIdOrderByCreatedAtDesc(Long reporterUserId);
    List<ToiletReport> findByStatusOrderByCreatedAtAsc(ReportStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from ToiletReport r where r.id = :id") Optional<ToiletReport> findByIdForUpdate(Long id);
}
