package com.example.toiletapi.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.toiletapi.report.model.ToiletReport;
import com.example.toiletapi.report.model.ReportStatus;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Executes the real JPQL with isolated fixture rows; never opens production configuration. */
class MyReportHistoryRepositoryTest {
    @Test void boundedPagesCountsAndDetailExcludeOtherOwnersAndGuestReports() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:report-history;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan("com.example.toiletapi.report.model", "com.example.toiletapi.toilet.model");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        try (var em = factory.getObject().createEntityManager()) {
            em.getTransaction().begin();
            for (int i = 0; i < 27; i++) {
                var report = ToiletReport.createOpenTimeCorrection(10L, 3L, "24시간", "가상 제보", "owner-" + i);
                if (i % 2 == 0) report.approve(9L, "가상 검토");
                em.persist(report);
            }
            for (int i = 0; i < 120; i++) em.persist(ToiletReport.createOpenTimeCorrection(10L, 4L, "24시간", "타인 가상 제보", "other-" + i));
            em.persist(ToiletReport.quick(null, null, "NEW_FACILITY", "비회원 가상 시설", null, null, "가상 주소", null, "가상 제보", "guest", "submission", "fingerprint"));
            em.getTransaction().commit();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("update toilet_report set created_at = ?", LocalDateTime.of(2026, 10, 4, 12, 0));
            jdbc.update("update toilet_report set created_at = ? where report_id = 1", LocalDateTime.of(2026, 10, 3, 23, 59, 59));
            jdbc.update("update toilet_report set created_at = ? where report_id = 2", LocalDateTime.of(2026, 10, 5, 0, 0));
            em.clear();
            var repository = new JpaRepositoryFactory(em).getRepository(ToiletReportRepository.class);
            var sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");
            var from = LocalDateTime.of(2026, 10, 4, 0, 0);
            var to = from.plusDays(1);
            var first = repository.findOwnerHistory(3L, null, from, to, PageRequest.of(0, 10, sort));
            var second = repository.findOwnerHistory(3L, null, from, to, PageRequest.of(1, 10, sort));
            var last = repository.findOwnerHistory(3L, null, from, to, PageRequest.of(2, 10, sort));
            assertThat(first).hasSize(10).allMatch(r -> r.getReporterUserId().equals(3L));
            assertThat(first).extracting(ToiletReport::getId).containsExactly(27L, 26L, 25L, 24L, 23L, 22L, 21L, 20L, 19L, 18L);
            assertThat(second).extracting(ToiletReport::getId).doesNotContainAnyElementsOf(first.stream().map(ToiletReport::getId).toList());
            assertThat(last).hasSize(5);
            assertThat(repository.countOwnerHistory(3L, from, to)).allSatisfy(count -> assertThat(count.count()).isPositive());
            assertThat(repository.countOwnerHistory(3L, from, to).stream().mapToLong(r -> r.count()).sum()).isEqualTo(25);
            assertThat(repository.findOwnerHistory(3L, ReportStatus.APPROVED, from, to, PageRequest.of(0, 50, sort)))
                    .hasSize(13).allMatch(r -> r.getStatus() == ReportStatus.APPROVED);
            assertThat(repository.countOwnerHistory(3L, null, null).stream().mapToLong(r -> r.count()).sum()).isEqualTo(27);
            assertThat(repository.findByIdAndReporterUserId(1L, 3L)).isPresent();
            assertThat(repository.findByIdAndReporterUserId(28L, 3L)).isEmpty();
            assertThat(repository.findByIdAndReporterUserId(148L, 3L)).isEmpty();
            assertThat(repository.findOwnerHistory(99L, null, null, null, PageRequest.of(0, 10, sort))).isEmpty();
        } finally { factory.destroy(); }
    }
}
