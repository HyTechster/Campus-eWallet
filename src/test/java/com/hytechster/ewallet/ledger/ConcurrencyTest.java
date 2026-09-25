package com.hytechster.ewallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.hytechster.ewallet.IntegrationTest;
import com.hytechster.ewallet.common.InsufficientFundsException;
import com.hytechster.ewallet.user.User;

/**
 * The point of the project: many requests hitting one wallet at the same moment must never
 * overspend it, never deadlock, and never leave the books out of balance.
 */
class ConcurrencyTest extends IntegrationTest {

    enum Outcome { OK, INSUFFICIENT }

    @Test
    void fiftyParallelTransfersNeverOverspendAWallet() throws Exception {
        User sender = newStudent("Sender");
        User receiver = newStudent("Receiver");
        fund(sender, 10_000); // RM 100

        List<Outcome> outcomes = runAtOnce(50, i ->
                () -> ledger.transfer(sender.getId(), receiver.getId(), 500, UUID.randomUUID(), "Race " + i));

        assertThat(outcomes).filteredOn(o -> o == Outcome.OK).hasSize(20);
        assertThat(outcomes).filteredOn(o -> o == Outcome.INSUFFICIENT).hasSize(30);
        assertThat(balanceOf(sender.getId())).isZero();
        assertThat(balanceOf(receiver.getId())).isEqualTo(10_000);
        assertThat(ledgerSumOf(sender.getId())).isZero();
        Long lowest = jdbc.queryForObject("""
                SELECT MIN(l.balance_after) FROM ledger_lines l JOIN accounts a ON a.id = l.account_id
                WHERE a.owner_user_id = ?
                """, Long.class, sender.getId());
        assertThat(lowest).isZero();
        assertThat(reconciliation.run().passed()).isTrue();
    }

    @Test
    void transfersInBothDirectionsAtOnceDoNotDeadlock() throws Exception {
        User a = newStudent("Alpha");
        User b = newStudent("Bravo");
        fund(a, 5_000);
        fund(b, 5_000);

        List<Outcome> outcomes = runAtOnce(40, i -> i % 2 == 0
                ? () -> ledger.transfer(a.getId(), b.getId(), 100, UUID.randomUUID(), null)
                : () -> ledger.transfer(b.getId(), a.getId(), 100, UUID.randomUUID(), null));

        assertThat(outcomes).containsOnly(Outcome.OK);
        assertThat(balanceOf(a.getId()) + balanceOf(b.getId())).isEqualTo(10_000);
        assertThat(balanceOf(a.getId())).isEqualTo(5_000);
        assertThat(reconciliation.run().passed()).isTrue();
    }

    @Test
    void parallelPaymentsAndTopUpsKeepTheBooksBalanced() throws Exception {
        User student = newStudent("Busy");
        var kafe = newMerchant("Kafe Rush");
        fund(student, 2_000);

        List<Outcome> outcomes = runAtOnce(30, i -> i % 3 == 0
                ? () -> ledger.topUp(student.getId(), 300, UUID.randomUUID(), student.getId(), null)
                : () -> ledger.pay(student.getId(), kafe.getUserId(), 250, UUID.randomUUID(), null));

        long paid = balanceOf(kafe.getUserId());
        long okPayments = paid / 250;
        long okTopUps = outcomes.stream().filter(o -> o == Outcome.OK).count() - okPayments;
        assertThat(okTopUps).isEqualTo(10);
        assertThat(balanceOf(student.getId())).isEqualTo(2_000 + 10 * 300 - paid).isNotNegative();
        assertThat(reconciliation.run().passed()).isTrue();
    }

    interface Task {
        Callable<Object> make(int i);
    }

    /** Starts every task at the same instant and waits for all of them. */
    private static List<Outcome> runAtOnce(int count, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<Object> work = task.make(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        work.call();
                        return Outcome.OK;
                    } catch (InsufficientFundsException e) {
                        return Outcome.INSUFFICIENT;
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS)); // any other exception fails the test here
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
