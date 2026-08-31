package com.example.toiletapi.report.repository;
import com.example.toiletapi.report.model.CoordinateRevision;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CoordinateRevisionRepository extends JpaRepository<CoordinateRevision, Long> {
    List<CoordinateRevision> findByToiletIdInOrderByAppliedAtDesc(Collection<Long> toiletIds);
}
