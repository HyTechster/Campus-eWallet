package com.hytechster.ewallet.ledger;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only on purpose: the ledger is append-only and only {@link LedgerService} inserts entries.
 */
public interface JournalEntryRepository extends Repository<JournalEntry, Long> {

    @Query("select e from JournalEntry e left join fetch e.lines where e.id = :id")
    Optional<JournalEntry> findWithLines(@Param("id") long id);

    @Query("select e from JournalEntry e left join fetch e.lines where e.idempotencyKey = :key")
    Optional<JournalEntry> findByIdempotencyKey(@Param("key") UUID key);

    boolean existsByReversesEntryId(Long entryId);

    Optional<JournalEntry> findByReversesEntryId(Long entryId);

    @Query(value = """
            select e from JournalEntry e
            where e.createdAt >= :from and e.createdAt < :to
              and (:type is null or e.type = :type)
            order by e.id desc
            """,
            countQuery = """
            select count(e) from JournalEntry e
            where e.createdAt >= :from and e.createdAt < :to
              and (:type is null or e.type = :type)
            """)
    Page<JournalEntry> search(@Param("type") EntryType type, @Param("from") Instant from,
                              @Param("to") Instant to, Pageable pageable);
}
