package com.hytechster.ewallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import com.hytechster.ewallet.IntegrationTest;
import com.hytechster.ewallet.common.AccountFrozenException;
import com.hytechster.ewallet.common.BusinessException;
import com.hytechster.ewallet.common.InsufficientFundsException;
import com.hytechster.ewallet.common.LimitExceededException;
import com.hytechster.ewallet.merchant.Merchant;
import com.hytechster.ewallet.user.User;

class LedgerServiceTest extends IntegrationTest {

    @Autowired
    TransactionTemplate tx;

    record Line(long accountId, long amount, long balanceAfter) {
    }

    // ---------------------------------------------------------------- each operation posts balanced lines

    @Test
    void topUpCreditsWalletAndDebitsSystemAccount() {
        User aisyah = newStudent("Aisyah");

        PostedEntry posted = ledger.topUp(aisyah.getId(), 5000, UUID.randomUUID(), aisyah.getId(), "Demo");

        assertThat(posted.type()).isEqualTo(EntryType.TOPUP);
        assertThat(posted.amount()).isEqualTo(5000);
        assertThat(posted.replayed()).isFalse();
        List<Line> lines = linesOf(posted.entryId());
        assertThat(lines).extracting(Line::amount).containsExactlyInAnyOrder(5000L, -5000L);
        assertThat(lines.stream().mapToLong(Line::amount).sum()).isZero();
        assertThat(lines).anySatisfy(l -> {
            assertThat(l.accountId()).isEqualTo(walletId(aisyah));
            assertThat(l.amount()).isEqualTo(5000);
            assertThat(l.balanceAfter()).isEqualTo(5000);
        });
        assertThat(lines).anySatisfy(l -> assertThat(l.accountId()).isEqualTo(systemId()));
        assertThat(balanceOf(aisyah.getId())).isEqualTo(5000);
    }

    @Test
    void transferMovesMoneyBetweenStudents() {
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        fund(ali, 5000);

        PostedEntry posted = ledger.transfer(ali.getId(), siti.getId(), 1000, UUID.randomUUID(), "Lunch");

        assertThat(linesOf(posted.entryId())).containsExactlyInAnyOrder(
                new Line(walletId(ali), -1000, 4000),
                new Line(walletId(siti), 1000, 1000));
        assertThat(balanceOf(ali.getId())).isEqualTo(4000);
        assertThat(balanceOf(siti.getId())).isEqualTo(1000);
    }

    @Test
    void paymentMovesMoneyToMerchant() {
        User farid = newStudent("Farid");
        Merchant kafe = newMerchant("Kafe Test");
        fund(farid, 2000);

        PostedEntry posted = ledger.pay(farid.getId(), kafe.getUserId(), 650, UUID.randomUUID(), null);

        assertThat(linesOf(posted.entryId())).containsExactlyInAnyOrder(
                new Line(walletId(farid), -650, 1350),
                new Line(accounts.findIdByOwner(kafe.getUserId(), AccountType.MERCHANT_WALLET).orElseThrow(), 650, 650));
        assertThat(balanceOf(kafe.getUserId())).isEqualTo(650);
    }

    // ---------------------------------------------------------------- failures write nothing

    @Test
    void insufficientFundsThrowsAndWritesNothing() {
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        fund(ali, 500);
        long entriesBefore = entryCount();

        assertThatThrownBy(() -> ledger.transfer(ali.getId(), siti.getId(), 501, UUID.randomUUID(), null))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(entryCount()).isEqualTo(entriesBefore);
        assertThat(balanceOf(ali.getId())).isEqualTo(500);
        assertThat(balanceOf(siti.getId())).isZero();
    }

    @Test
    void frozenUserCannotSendPayOrTopUp() {
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        Merchant kafe = newMerchant("Kafe Frozen");
        fund(ali, 5000);
        userService.setFrozen(ali.getId(), true);
        long entriesBefore = entryCount();

        assertThatThrownBy(() -> ledger.transfer(ali.getId(), siti.getId(), 100, UUID.randomUUID(), null))
                .isInstanceOf(AccountFrozenException.class);
        assertThatThrownBy(() -> ledger.pay(ali.getId(), kafe.getUserId(), 100, UUID.randomUUID(), null))
                .isInstanceOf(AccountFrozenException.class);
        assertThatThrownBy(() -> ledger.topUp(ali.getId(), 100, UUID.randomUUID(), ali.getId(), null))
                .isInstanceOf(AccountFrozenException.class);

        assertThat(entryCount()).isEqualTo(entriesBefore);
        assertThat(balanceOf(ali.getId())).isEqualTo(5000);

        // A frozen user can still receive money.
        fund(siti, 300);
        ledger.transfer(siti.getId(), ali.getId(), 300, UUID.randomUUID(), null);
        assertThat(balanceOf(ali.getId())).isEqualTo(5300);
    }

    @Test
    void rejectsBadAmountsSelfTransfersAndLimits() {
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        fund(ali, 20000);

        assertThatThrownBy(() -> ledger.transfer(ali.getId(), siti.getId(), 0, UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ledger.transfer(ali.getId(), siti.getId(), -100, UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ledger.transfer(ali.getId(), ali.getId(), 100, UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ledger.topUp(ali.getId(), 20001, UUID.randomUUID(), ali.getId(), null))
                .isInstanceOf(LimitExceededException.class);
        assertThatThrownBy(() -> ledger.transfer(ali.getId(), siti.getId(), 50001, UUID.randomUUID(), null))
                .isInstanceOf(LimitExceededException.class);
        assertThat(balanceOf(ali.getId())).isEqualTo(20000);
    }

    @Test
    void cannotTransferToAMerchantOrPayAStudent() {
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        Merchant kafe = newMerchant("Kafe Rules");
        fund(ali, 1000);

        assertThatThrownBy(() -> ledger.transfer(ali.getId(), kafe.getUserId(), 100, UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ledger.pay(ali.getId(), siti.getId(), 100, UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------- reversals

    @Test
    void reversalRestoresBalancesWithMirrorLines() {
        User admin = newAdmin();
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        fund(ali, 3000);
        PostedEntry transfer = ledger.transfer(ali.getId(), siti.getId(), 1200, UUID.randomUUID(), null);

        PostedEntry reversal = ledger.reverse(transfer.entryId(), "Sent to wrong person", admin.getId(), UUID.randomUUID());

        assertThat(reversal.type()).isEqualTo(EntryType.REVERSAL);
        assertThat(balanceOf(ali.getId())).isEqualTo(3000);
        assertThat(balanceOf(siti.getId())).isZero();
        assertThat(linesOf(reversal.entryId())).containsExactlyInAnyOrder(
                new Line(walletId(ali), 1200, 3000),
                new Line(walletId(siti), -1200, 0));
        Long link = jdbc.queryForObject("SELECT reverses_entry_id FROM journal_entries WHERE id = ?", Long.class,
                reversal.entryId());
        assertThat(link).isEqualTo(transfer.entryId());
    }

    @Test
    void entryCanOnlyBeReversedOnceAndReversalsCannotBeReversed() {
        User admin = newAdmin();
        User ali = newStudent("Ali");
        fund(ali, 1000);
        long topUpId = ledger.topUp(ali.getId(), 500, UUID.randomUUID(), admin.getId(), "Counter").entryId();
        PostedEntry reversal = ledger.reverse(topUpId, "Cash was fake", admin.getId(), UUID.randomUUID());

        assertThatThrownBy(() -> ledger.reverse(topUpId, "Again", admin.getId(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been reversed");
        assertThatThrownBy(() -> ledger.reverse(reversal.entryId(), "Undo", admin.getId(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        assertThat(balanceOf(ali.getId())).isEqualTo(1000);
    }

    @Test
    void reversalFailsIfItWouldPushAWalletBelowZero() {
        User admin = newAdmin();
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        Merchant kafe = newMerchant("Kafe Spent");
        fund(ali, 1000);
        PostedEntry transfer = ledger.transfer(ali.getId(), siti.getId(), 1000, UUID.randomUUID(), null);
        ledger.pay(siti.getId(), kafe.getUserId(), 800, UUID.randomUUID(), null);
        long entriesBefore = entryCount();

        assertThatThrownBy(() -> ledger.reverse(transfer.entryId(), "Mistake", admin.getId(), UUID.randomUUID()))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(entryCount()).isEqualTo(entriesBefore);
        assertThat(balanceOf(siti.getId())).isEqualTo(200);
    }

    @Test
    void onlyAdminsCanReverse() {
        User ali = newStudent("Ali");
        fund(ali, 1000);
        long entryId = ledger.topUp(ali.getId(), 100, UUID.randomUUID(), ali.getId(), null).entryId();

        assertThatThrownBy(() -> ledger.reverse(entryId, "Please", ali.getId(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------- database backstops

    @Test
    void databaseRejectsUpdatesAndDeletesOnTheLedger() {
        User ali = newStudent("Ali");
        fund(ali, 1000);

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_lines SET amount = amount + 1"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_entries"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void databaseRejectsNegativeWalletBalance() {
        User ali = newStudent("Ali");

        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance = -1 WHERE id = ?", walletId(ali)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsAnUnbalancedEntryAtCommit() {
        User ali = newStudent("Ali");
        long entriesBefore = entryCount();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Long entryId = jdbc.queryForObject("""
                    INSERT INTO journal_entries (type, idempotency_key) VALUES ('TOPUP', ?) RETURNING id
                    """, Long.class, UUID.randomUUID());
            jdbc.update("INSERT INTO ledger_lines (entry_id, account_id, amount, balance_after) VALUES (?, ?, 100, 100)",
                    entryId, walletId(ali));
        })).rootCause().hasMessageContaining("does not balance");
        assertThat(entryCount()).isEqualTo(entriesBefore);
    }

    // ---------------------------------------------------------------- helpers

    private long walletId(User user) {
        return accounts.findByOwnerUserId(user.getId()).orElseThrow().getId();
    }

    private long systemId() {
        return accounts.findIdByType(AccountType.SYSTEM_TOPUP).orElseThrow();
    }

    private List<Line> linesOf(long entryId) {
        return jdbc.query("SELECT account_id, amount, balance_after FROM ledger_lines WHERE entry_id = ? ORDER BY id",
                (rs, i) -> new Line(rs.getLong(1), rs.getLong(2), rs.getLong(3)), entryId);
    }
}
