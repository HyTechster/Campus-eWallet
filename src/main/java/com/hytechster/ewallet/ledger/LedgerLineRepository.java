package com.hytechster.ewallet.ledger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only on purpose: lines are only ever inserted by {@link LedgerService}, through their entry.
 */
public interface LedgerLineRepository extends Repository<LedgerLine, Long> {

    @Query(value = """
            select l from LedgerLine l join fetch l.entry e
            where l.accountId = :accountId
              and (:type is null or e.type = :type)
              and e.createdAt >= :from and e.createdAt < :to
            order by l.id desc
            """,
            countQuery = """
            select count(l) from LedgerLine l join l.entry e
            where l.accountId = :accountId
              and (:type is null or e.type = :type)
              and e.createdAt >= :from and e.createdAt < :to
            """)
    Page<LedgerLine> findStatement(@Param("accountId") long accountId, @Param("type") EntryType type,
                                   @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    @Query("select l from LedgerLine l join fetch l.entry e where l.accountId = :accountId and e.id = :entryId")
    Optional<LedgerLine> findByAccountAndEntry(@Param("accountId") long accountId, @Param("entryId") long entryId);

    @Query("select l from LedgerLine l where l.entry.id in :entryIds")
    List<LedgerLine> findByEntryIds(@Param("entryIds") Collection<Long> entryIds);

    @Query("""
            select coalesce(sum(l.amount), 0) from LedgerLine l join l.entry e
            where l.accountId = :accountId and e.type in :types and e.createdAt >= :from
            """)
    long sumSince(@Param("accountId") long accountId, @Param("types") Collection<EntryType> types,
                  @Param("from") Instant from);

    @Query("""
            select count(l) from LedgerLine l join l.entry e
            where l.accountId = :accountId and e.type = :type and e.createdAt >= :from
            """)
    long countSince(@Param("accountId") long accountId, @Param("type") EntryType type, @Param("from") Instant from);
}
