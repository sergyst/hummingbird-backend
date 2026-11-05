package com.example.alerts.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<AlertRecord, Long> {

    Optional<AlertRecord> findByExternalId(String externalId);

    boolean existsByExternalId(String externalId);

    List<AlertRecord> findByStateOrderByCreatedAtAsc(AlertState state);

    List<AlertRecord> findByCreatedAtAfter(Instant since);

    List<AlertRecord> findByCaseToken(String caseToken);

    /**
     * Search by optional state and free text (alert id, rule, subject, case token).
     * {@code anyState = true} disables the state filter; {@code q = ''} and {@code outcome = ''} disable theirs.
     */
    @Query("""
            select a from AlertRecord a
            where (:anyState = true or a.state = :state)
              and (:outcome = '' or a.outcome = :outcome)
              and (:q = '' or lower(a.externalId) like lower(concat('%', :q, '%'))
                           or lower(coalesce(a.rule, '')) like lower(concat('%', :q, '%'))
                           or lower(coalesce(a.subjectName, '')) like lower(concat('%', :q, '%'))
                           or lower(coalesce(a.caseToken, '')) like lower(concat('%', :q, '%')))
            """)
    Page<AlertRecord> search(@Param("anyState") boolean anyState,
                             @Param("state") AlertState state,
                             @Param("q") String q,
                             @Param("outcome") String outcome,
                             Pageable pageable);

    @Query("select a.state, count(a) from AlertRecord a group by a.state")
    List<Object[]> countByState();

    @Query("select a.outcome, count(a) from AlertRecord a where a.outcome is not null group by a.outcome")
    List<Object[]> countByOutcome();
}
