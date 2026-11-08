package com.payflow.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proves the ledger is internally consistent:
 * <ol>
 *   <li>every account's materialised balance == SUM(its postings)</li>
 *   <li>SUM(all postings) == 0 per currency (money is conserved)</li>
 * </ol>
 * Runs in a REPEATABLE READ transaction so both checks see ONE consistent snapshot while payments keep
 * flowing. A transaction-scoped Postgres advisory lock ensures only one instance runs it at a time
 * (a distributed lock with no extra infrastructure, released automatically if the instance dies).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final long ADVISORY_LOCK_KEY = 7_340_001L;

    public record Run(UUID id, String status, int accountsChecked, String mismatches, String globalSums) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshotTx;
    private final ObjectMapper objectMapper;
    private final AtomicLong lastMismatches = new AtomicLong();

    public ReconciliationService(JdbcTemplate jdbc, PlatformTransactionManager txManager, ObjectMapper objectMapper,
                                 MeterRegistry meters) {
        this.jdbc = jdbc;
        this.snapshotTx = new TransactionTemplate(txManager);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.objectMapper = objectMapper;
        Gauge.builder("payflow.reconciliation.mismatches", lastMismatches, AtomicLong::get)
                .description("accounts whose balance != sum(postings) in the last run (should be 0)").register(meters);
    }

    @Scheduled(cron = "${payflow.reconciliation.cron}")
    public void scheduled() {
        run().ifPresentOrElse(
                r -> log.info("reconciliation {} {}: {} accounts checked", r.id(), r.status(), r.accountsChecked()),
                () -> log.info("reconciliation skipped: another instance holds the lock"));
    }

    /** @return empty if another instance is already reconciling. */
    public Optional<Run> run() {
        return Optional.ofNullable(snapshotTx.execute(status -> {
            Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return null;
            }
            List<Map<String, Object>> mismatches = jdbc.queryForList("""
                    SELECT a.id::text AS account_id, a.balance_minor, COALESCE(p.total, 0) AS postings_total
                    FROM accounts a
                    LEFT JOIN (SELECT account_id, SUM(amount_minor) AS total FROM postings GROUP BY account_id) p
                           ON p.account_id = a.id
                    WHERE a.balance_minor <> COALESCE(p.total, 0)
                    """);
            List<Map<String, Object>> globalSums = jdbc.queryForList(
                    "SELECT currency, SUM(amount_minor) AS total FROM postings GROUP BY currency");
            boolean conserved = globalSums.stream().allMatch(r -> ((Number) r.get("total")).longValue() == 0L);
            Integer accounts = jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class);
            String state = mismatches.isEmpty() && conserved ? "PASSED" : "FAILED";
            String mismatchJson = json(mismatches);
            String sumsJson = json(globalSums);
            UUID id = jdbc.queryForObject("""
                    INSERT INTO reconciliation_runs (status, finished_at, accounts_checked, mismatches, global_sum_minor)
                    VALUES (?, now(), ?, ?::jsonb, ?::jsonb) RETURNING id
                    """, UUID.class, state, accounts, mismatchJson, sumsJson);
            lastMismatches.set(mismatches.size());
            if ("FAILED".equals(state)) {
                log.error("RECONCILIATION FAILED: mismatches={} sums={}", mismatchJson, sumsJson);
            }
            return new Run(id, state, accounts == null ? 0 : accounts, mismatchJson, sumsJson);
        }));
    }

    public Optional<Run> latest() {
        return jdbc.query("""
                        SELECT id, status, accounts_checked, mismatches::text AS m, global_sum_minor::text AS g
                        FROM reconciliation_runs ORDER BY started_at DESC LIMIT 1
                        """,
                (rs, i) -> new Run(rs.getObject("id", UUID.class), rs.getString("status"), rs.getInt("accounts_checked"),
                        rs.getString("m"), rs.getString("g"))).stream().findFirst();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
