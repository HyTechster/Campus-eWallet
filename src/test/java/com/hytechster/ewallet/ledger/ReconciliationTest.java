package com.hytechster.ewallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hytechster.ewallet.IntegrationTest;
import com.hytechster.ewallet.common.BusinessException;
import com.hytechster.ewallet.merchant.Merchant;
import com.hytechster.ewallet.user.User;

class ReconciliationTest extends IntegrationTest {

    @Test
    void randomMixOfOperationsAlwaysReconciles() {
        Random random = new Random(20260925L);
        User admin = newAdmin();
        List<User> students = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            students.add(newStudent("Student " + i));
        }
        List<Merchant> merchants = List.of(newMerchant("Kafe Mix"), newMerchant("Fotostat Mix"));
        List<Long> entries = new ArrayList<>();
        int succeeded = 0;
        int rejected = 0;

        for (int i = 0; i < 250; i++) {
            User student = students.get(random.nextInt(students.size()));
            long amount = 1 + random.nextInt(8_000);
            try {
                PostedEntry posted = switch (random.nextInt(10)) {
                    case 0, 1, 2 -> ledger.topUp(student.getId(), Math.min(amount, 20_000), UUID.randomUUID(),
                            admin.getId(), null);
                    case 3, 4, 5 -> ledger.transfer(student.getId(),
                            students.get(random.nextInt(students.size())).getId(), amount, UUID.randomUUID(), null);
                    case 6, 7, 8 -> ledger.pay(student.getId(),
                            merchants.get(random.nextInt(merchants.size())).getUserId(), amount, UUID.randomUUID(), null);
                    default -> entries.isEmpty() ? null : ledger.reverse(entries.get(random.nextInt(entries.size())),
                            "Random reversal", admin.getId(), UUID.randomUUID());
                };
                if (posted != null) {
                    entries.add(posted.entryId());
                    succeeded++;
                }
            } catch (BusinessException expected) {
                rejected++; // insufficient funds, self transfer, already reversed, ...
            }
        }

        assertThat(succeeded).isGreaterThan(100);
        assertThat(rejected).isGreaterThan(0);

        ReconciliationService.Report report = reconciliation.run();
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.unbalancedEntries()).isEmpty();
        assertThat(report.balanceTotal()).isZero();
        assertThat(report.ledgerTotal()).isZero();
        assertThat(report.negativeWallets()).isZero();
        assertThat(report.passed()).isTrue();
        for (User student : students) {
            assertThat(balanceOf(student.getId())).isEqualTo(ledgerSumOf(student.getId())).isNotNegative();
        }
    }
}
