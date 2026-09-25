package com.hytechster.ewallet.admin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hytechster.ewallet.common.EwalletProperties;
import com.hytechster.ewallet.common.Money;
import com.hytechster.ewallet.ledger.EntryType;
import com.hytechster.ewallet.ledger.ReconciliationService;

/** Read-only admin reports, straight from the ledger with SQL. */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final JdbcTemplate jdbc;
    private final ReconciliationService reconciliation;
    private final ZoneId zone;

    public ReportService(JdbcTemplate jdbc, ReconciliationService reconciliation, EwalletProperties properties) {
        this.jdbc = jdbc;
        this.reconciliation = reconciliation;
        this.zone = properties.displayZone();
    }

    public Reports reports(int days) {
        return new Reports(holdings(), dailyTotals(days), topMerchants(5), reconciliation.run());
    }

    /** Money sitting in wallets right now. The SYSTEM_TOPUP balance is minus the sum of all top-ups. */
    Holdings holdings() {
        Map<String, long[]> byType = new TreeMap<>();
        jdbc.query("SELECT type, COUNT(*) AS n, COALESCE(SUM(balance), 0) AS total FROM accounts GROUP BY type",
                rs -> {
                    byType.put(rs.getString("type"), new long[] {rs.getLong("n"), rs.getLong("total")});
                });
        long[] none = {0, 0};
        return new Holdings(byType.getOrDefault("USER_WALLET", none)[1], byType.getOrDefault("USER_WALLET", none)[0],
                byType.getOrDefault("MERCHANT_WALLET", none)[1], byType.getOrDefault("MERCHANT_WALLET", none)[0],
                byType.getOrDefault("SYSTEM_TOPUP", none)[1]);
    }

    /** Count and total per entry type per day, newest day first. */
    List<DayTotals> dailyTotals(int days) {
        LocalDate firstDay = LocalDate.now(zone).minusDays(days - 1L);
        Timestamp since = Timestamp.from(firstDay.atStartOfDay(zone).toInstant());
        Map<LocalDate, DayTotals> byDay = new TreeMap<>(java.util.Comparator.reverseOrder());
        jdbc.query("""
                SELECT (e.created_at AT TIME ZONE ?)::date AS day, e.type, COUNT(*) AS n, SUM(p.amount) AS total
                FROM journal_entries e
                JOIN (SELECT entry_id, SUM(amount) FILTER (WHERE amount > 0) AS amount
                      FROM ledger_lines GROUP BY entry_id) p ON p.entry_id = e.id
                WHERE e.created_at >= ?
                GROUP BY day, e.type
                """, rs -> {
            LocalDate day = rs.getObject("day", LocalDate.class);
            DayTotals totals = byDay.computeIfAbsent(day, d -> new DayTotals(d, new EnumMap<>(EntryType.class)));
            totals.cells().put(EntryType.valueOf(rs.getString("type")), new Cell(rs.getLong("n"), rs.getLong("total")));
        }, zone.getId(), since);
        return new ArrayList<>(byDay.values());
    }

    /** Net sales (payments minus reversed payments) per merchant, all time. */
    List<MerchantSales> topMerchants(int limit) {
        return jdbc.query("""
                SELECT m.name, m.code,
                       COALESCE(SUM(l.amount), 0) AS net_sales,
                       COUNT(e.id) FILTER (WHERE e.type = 'PAYMENT') AS payments
                FROM merchants m
                JOIN accounts a ON a.owner_user_id = m.user_id
                LEFT JOIN ledger_lines l ON l.account_id = a.id
                LEFT JOIN journal_entries e ON e.id = l.entry_id
                GROUP BY m.id, m.name, m.code
                ORDER BY net_sales DESC, m.name
                LIMIT ?
                """, (rs, i) -> new MerchantSales(rs.getString("name"), rs.getString("code"), rs.getLong("net_sales"),
                rs.getLong("payments")), limit);
    }

    /** One CSV row per ledger line for entries created between the two dates (inclusive, local time). */
    public void exportCsv(LocalDate from, LocalDate to, Writer out) {
        Timestamp start = Timestamp.from(from.atStartOfDay(zone).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());
        write(out, "entry_id,created_at,type,description,reverses_entry_id,account_id,account_type,account_owner,amount_rm,balance_after_rm\n");
        jdbc.query("""
                SELECT e.id, e.created_at, e.type, e.description, e.reverses_entry_id,
                       l.account_id, a.type AS account_type,
                       COALESCE(m.name, u.full_name, 'System top-up') AS owner,
                       l.amount, l.balance_after
                FROM journal_entries e
                JOIN ledger_lines l ON l.entry_id = e.id
                JOIN accounts a ON a.id = l.account_id
                LEFT JOIN users u ON u.id = a.owner_user_id
                LEFT JOIN merchants m ON m.user_id = a.owner_user_id
                WHERE e.created_at >= ? AND e.created_at < ?
                ORDER BY e.id, l.id
                """, rs -> {
            Object reverses = rs.getObject("reverses_entry_id");
            String row = String.join(",",
                    rs.getString("id"),
                    rs.getTimestamp("created_at").toInstant().atZone(zone).toOffsetDateTime().format(CSV_TIME),
                    rs.getString("type"),
                    csvText(rs.getString("description")),
                    reverses == null ? "" : reverses.toString(),
                    rs.getString("account_id"),
                    rs.getString("account_type"),
                    csvText(rs.getString("owner")),
                    Money.plain(rs.getLong("amount")),
                    Money.plain(rs.getLong("balance_after")));
            write(out, row + "\n");
        }, start, end);
    }

    /** Quotes a text cell and defuses spreadsheet formula injection. */
    static String csvText(String value) {
        if (value == null) {
            return "";
        }
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static void write(Writer out, String text) {
        try {
            out.write(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record Reports(Holdings holdings, List<DayTotals> days, List<MerchantSales> topMerchants,
                          ReconciliationService.Report reconciliation) {
    }

    public record Holdings(long userWallets, long userCount, long merchantWallets, long merchantCount,
                           long systemTopup) {

        public long total() {
            return userWallets + merchantWallets;
        }
    }

    public record Cell(long count, long total) {
    }

    public record DayTotals(LocalDate day, Map<EntryType, Cell> cells) {

        public Cell cell(EntryType type) {
            return cells.getOrDefault(type, new Cell(0, 0));
        }
    }

    public record MerchantSales(String name, String code, long netSales, long payments) {
    }
}
