package com.hytechster.ewallet.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read-only on purpose: there is no save(). Accounts are created and balances changed
 * only through {@link LedgerService}.
 */
public interface AccountRepository extends Repository<Account, Long> {

    Optional<Account> findById(Long id);

    Optional<Account> findByOwnerUserId(Long ownerUserId);

    @Query("select a.id from Account a where a.ownerUserId = :userId and a.type = :type")
    Optional<Long> findIdByOwner(@Param("userId") long userId, @Param("type") AccountType type);

    @Query("select a.id from Account a where a.type = :type")
    Optional<Long> findIdByType(@Param("type") AccountType type);

    @Query("""
            select new com.hytechster.ewallet.ledger.AccountLabel(
                a.id, a.type, a.ownerUserId, coalesce(m.name, u.fullName, 'Campus e-wallet'))
            from Account a
            left join User u on u.id = a.ownerUserId
            left join Merchant m on m.userId = a.ownerUserId
            where a.id in :ids
            """)
    List<AccountLabel> findLabels(@Param("ids") Collection<Long> ids);
}
