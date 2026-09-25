package com.hytechster.ewallet;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.hytechster.ewallet.ledger.AccountRepository;
import com.hytechster.ewallet.ledger.LedgerService;
import com.hytechster.ewallet.ledger.ReconciliationService;
import com.hytechster.ewallet.merchant.Merchant;
import com.hytechster.ewallet.merchant.MerchantService;
import com.hytechster.ewallet.user.AppUserDetails;
import com.hytechster.ewallet.user.Role;
import com.hytechster.ewallet.user.User;
import com.hytechster.ewallet.user.UserService;

/**
 * Base for tests against a real Postgres. All test classes share one Spring context and one
 * container, so every helper creates fresh users with unique emails instead of cleaning up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final String PASSWORD = "password123";

    @Autowired
    protected UserService userService;
    @Autowired
    protected MerchantService merchantService;
    @Autowired
    protected LedgerService ledger;
    @Autowired
    protected AccountRepository accounts;
    @Autowired
    protected ReconciliationService reconciliation;
    @Autowired
    protected JdbcTemplate jdbc;

    protected User newStudent(String name) {
        String tag = tag();
        return userService.createUser(tag + "@students.test", PASSWORD, name, "S" + tag, Role.USER);
    }

    protected User newAdmin() {
        return userService.createUser(tag() + "@admin.test", PASSWORD, "Test Admin", null, Role.ADMIN);
    }

    protected Merchant newMerchant(String name) {
        String tag = tag();
        return merchantService.createMerchant(tag + "@merchants.test", PASSWORD, name, "M" + tag.toUpperCase());
    }

    /** Top up through the ledger, the only legal way to put money in. */
    protected void fund(User user, long sen) {
        ledger.topUp(user.getId(), sen, UUID.randomUUID(), user.getId(), "Test top-up");
    }

    protected long balanceOf(long userId) {
        return accounts.findByOwnerUserId(userId).orElseThrow().getBalance();
    }

    protected long ledgerSumOf(long userId) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.amount), 0) FROM ledger_lines l
                JOIN accounts a ON a.id = l.account_id WHERE a.owner_user_id = ?
                """, Long.class, userId);
        return sum == null ? 0 : sum;
    }

    protected long entryCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries", Long.class);
        return count == null ? 0 : count;
    }

    protected static AppUserDetails principal(User user) {
        return AppUserDetails.of(user);
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
