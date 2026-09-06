package com.example.toiletapi.auth.repository;

import com.example.toiletapi.auth.model.AccountWithdrawal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AccountWithdrawalRepository extends JpaRepository<AccountWithdrawal, Long> {
    @Query("select w.userId from AccountWithdrawal w where w.nextAttemptAt <= :now order by w.nextAttemptAt, w.userId")
    List<Long> findDue(LocalDateTime now, Pageable pageable);
    long countByPurgeAfterLessThanEqual(LocalDateTime now);
}
