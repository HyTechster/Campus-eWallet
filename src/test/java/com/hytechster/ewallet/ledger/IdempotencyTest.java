package com.hytechster.ewallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.hytechster.ewallet.IntegrationTest;
import com.hytechster.ewallet.common.BusinessException;
import com.hytechster.ewallet.user.User;

class IdempotencyTest extends IntegrationTest {

    @Test
    void sameKeyTwiceCreatesOneEntry() {
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        fund(ali, 5000);
        UUID key = UUID.randomUUID();
        long entriesBefore = entryCount();

        PostedEntry first = ledger.transfer(ali.getId(), siti.getId(), 1000, key, null);
        PostedEntry second = ledger.transfer(ali.getId(), siti.getId(), 1000, key, null);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.entryId()).isEqualTo(first.entryId());
        assertThat(second.amount()).isEqualTo(1000);
        assertThat(entryCount()).isEqualTo(entriesBefore + 1);
        assertThat(balanceOf(ali.getId())).isEqualTo(4000);
        assertThat(balanceOf(siti.getId())).isEqualTo(1000);
    }

    @Test
    void replayReturnsOriginalEvenWhenBalanceIsNowTooLow() {
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        fund(ali, 1000);
        UUID key = UUID.randomUUID();

        PostedEntry first = ledger.transfer(ali.getId(), siti.getId(), 1000, key, null);
        PostedEntry again = ledger.transfer(ali.getId(), siti.getId(), 1000, key, null);

        assertThat(again.entryId()).isEqualTo(first.entryId());
        assertThat(balanceOf(ali.getId())).isZero();
    }

    @Test
    void sameKeySubmittedConcurrentlyCreatesOneEntry() throws Exception {
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        fund(ali, 5000);
        UUID key = UUID.randomUUID();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<PostedEntry>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return ledger.transfer(ali.getId(), siti.getId(), 700, key, null);
                }));
            }
            go.countDown();
            List<PostedEntry> results = new ArrayList<>();
            for (Future<PostedEntry> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).extracting(PostedEntry::entryId).containsOnly(results.getFirst().entryId());
            assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
            assertThat(balanceOf(ali.getId())).isEqualTo(4300);
            assertThat(balanceOf(siti.getId())).isEqualTo(700);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void keyCannotBeReusedByAnotherUser() {
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        fund(ali, 1000);
        fund(siti, 1000);
        UUID key = UUID.randomUUID();
        ledger.transfer(ali.getId(), siti.getId(), 100, key, null);

        assertThatThrownBy(() -> ledger.transfer(siti.getId(), ali.getId(), 100, key, null))
                .isInstanceOf(BusinessException.class);
        assertThat(balanceOf(siti.getId())).isEqualTo(1100);
    }
}
