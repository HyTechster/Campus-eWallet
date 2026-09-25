package com.hytechster.ewallet.ledger;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the books are right. The ledger lines are the source of truth; {@code accounts.balance}
 * is a cache. Runs in one REPEATABLE READ snapshot so concurrent postings can't cause false alarms.
 */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report run() {
        List<Mismatch> mismatches = jdbc.query("""
                SELECT a.id, a.type, a.balance, COALESCE(SUM(l.amount), 0) AS ledger_sum
                FROM accounts a LEFT JOIN ledger_lines l ON l.account_id = a.id
                GROUP BY a.id, a.type, a.balance
                HAVING a.balance <> COALESCE(SUM(l.amount), 0)
                ORDER BY a.id
                """, (rs, i) -> new Mismatch(rs.getLong("id"), rs.getString("type"), rs.getLong("balance"),
                rs.getLong("ledger_sum")));

        List<Long> unbalancedEntries = jdbc.queryForList("""
                SELECT e.id FROM journal_entries e LEFT JOIN ledger_lines l ON l.entry_id = e.id
                GROUP BY e.id
                HAVING COUNT(l.id) < 2 OR COALESCE(SUM(l.amount), 0) <> 0
                ORDER BY e.id
                """, Long.class);

        long accountCount = count("SELECT COUNT(*) FROM accounts");
        long entryCount = count("SELECT COUNT(*) FROM journal_entries");
        long balanceTotal = count("SELECT COALESCE(SUM(balance), 0) FROM accounts");
        long ledgerTotal = count("SELECT COALESCE(SUM(amount), 0) FROM ledger_lines");
        long negativeWallets = count("SELECT COUNT(*) FROM accounts WHERE type <> 'SYSTEM_TOPUP' AND balance < 0");

        return new Report(mismatches, unbalancedEntries, accountCount, entryCount, balanceTotal, ledgerTotal,
                negativeWallets, Instant.now());
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    public record Mismatch(long accountId, String type, long balance, long ledgerSum) {
    }

    public record Report(
            List<Mismatch> mismatches,
            List<Long> unbalancedEntries,
            long accountCount,
            long entryCount,
            long balanceTotal,
            long ledgerTotal,
            long negativeWallets,
            Instant checkedAt) {

        public boolean passed() {
            return mismatches.isEmpty() && unbalancedEntries.isEmpty() && balanceTotal == 0 && ledgerTotal == 0
                    && negativeWallets == 0;
        }
    }
}
