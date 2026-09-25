package com.hytechster.ewallet.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hytechster.ewallet.common.AccountFrozenException;
import com.hytechster.ewallet.common.BusinessException;
import com.hytechster.ewallet.common.EwalletProperties;
import com.hytechster.ewallet.common.InsufficientFundsException;
import com.hytechster.ewallet.common.LimitExceededException;
import com.hytechster.ewallet.common.Money;
import com.hytechster.ewallet.common.NotFoundException;
import com.hytechster.ewallet.user.Role;
import com.hytechster.ewallet.user.User;
import com.hytechster.ewallet.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * The only class that writes ledger lines or changes account balances.
 *
 * <p>Every operation is one transaction that follows the same steps:
 * <ol>
 *   <li>Lock every involved account with {@code SELECT ... FOR UPDATE}, in ascending id order,
 *       so two operations touching the same accounts can never deadlock.</li>
 *   <li>Look up the idempotency key. If it was used before, return the original entry.</li>
 *   <li>Check the business rules (frozen users, reversal rules) and the balances, now that they
 *       cannot change under us.</li>
 *   <li>Check the lines sum to exactly 0, then insert the entry, its lines and the new balances.</li>
 * </ol>
 * Any exception rolls the whole thing back.
 */
@Service
public class LedgerService {

    private final EntityManager em;
    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final UserRepository users;
    private final EwalletProperties.Limits limits;

    public LedgerService(EntityManager em, AccountRepository accounts, JournalEntryRepository entries,
                         UserRepository users, EwalletProperties properties) {
        this.em = em;
        this.accounts = accounts;
        this.entries = entries;
        this.users = users;
        this.limits = properties.limits();
    }

    /** Opens the one wallet a user or merchant has. Admins have no wallet. */
    @Transactional
    public long openWallet(User user) {
        AccountType type = switch (user.getRole()) {
            case USER -> AccountType.USER_WALLET;
            case MERCHANT -> AccountType.MERCHANT_WALLET;
            case ADMIN -> throw new IllegalArgumentException("Admins do not have a wallet");
        };
        Account account = new Account(user.getId(), type);
        em.persist(account);
        return account.getId();
    }

    /**
     * Money in from outside (the demo payment button or cash at the office).
     * Posts: user wallet +amount, SYSTEM_TOPUP -amount.
     */
    @Transactional
    public PostedEntry topUp(long userId, long amount, UUID idempotencyKey, long actorUserId, String description) {
        requireValidAmount(amount, limits.topUpMax(), "Top-ups");
        long wallet = walletOf(userId, AccountType.USER_WALLET, "Only student wallets can be topped up.");
        long system = accounts.findIdByType(AccountType.SYSTEM_TOPUP)
                .orElseThrow(() -> new IllegalStateException("SYSTEM_TOPUP account is missing"));

        return post(new Request(EntryType.TOPUP, idempotencyKey, actorUserId, description, null,
                        "Top-up could not be completed.")
                        .line(wallet, amount)
                        .line(system, -amount),
                () -> requireActive(userId, "This wallet is frozen, so it can't be topped up."));
    }

    /** Student to student. Posts: sender -amount, recipient +amount. */
    @Transactional
    public PostedEntry transfer(long fromUserId, long toUserId, long amount, UUID idempotencyKey, String note) {
        if (fromUserId == toUserId) {
            throw new BusinessException("You can't send money to yourself.");
        }
        requireValidAmount(amount, limits.transferMax(), "Transfers");
        long from = walletOf(fromUserId, AccountType.USER_WALLET, "Only student wallets can send transfers.");
        long to = walletOf(toUserId, AccountType.USER_WALLET, "That person can't receive transfers.");

        return post(new Request(EntryType.TRANSFER, idempotencyKey, fromUserId, note, null,
                        "Not enough balance for this transfer.")
                        .line(from, -amount)
                        .line(to, amount),
                () -> requireActive(fromUserId, "Your wallet is frozen, so you can't send money right now."));
    }

    /** Student pays a merchant. Posts: user -amount, merchant +amount. */
    @Transactional
    public PostedEntry pay(long userId, long merchantUserId, long amount, UUID idempotencyKey, String note) {
        requireValidAmount(amount, limits.paymentMax(), "Payments");
        long from = walletOf(userId, AccountType.USER_WALLET, "Only student wallets can pay merchants.");
        long to = walletOf(merchantUserId, AccountType.MERCHANT_WALLET, "That merchant can't take payments.");

        return post(new Request(EntryType.PAYMENT, idempotencyKey, userId, note, null,
                        "Not enough balance for this payment.")
                        .line(from, -amount)
                        .line(to, amount),
                () -> requireActive(userId, "Your wallet is frozen, so you can't pay right now."));
    }

    /**
     * Admin only. Posts the mirror of every line of the original entry, linked by
     * {@code reverses_entry_id}. Fails if that would push a wallet below zero.
     */
    @Transactional
    public PostedEntry reverse(long entryId, String reason, long adminUserId, UUID idempotencyKey) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Give a reason for the reversal.");
        }
        User admin = users.findById(adminUserId).orElseThrow(() -> new NotFoundException("Admin not found"));
        if (admin.getRole() != Role.ADMIN) {
            throw new BusinessException("Only admins can reverse entries.");
        }
        JournalEntry original = entries.findWithLines(entryId)
                .orElseThrow(() -> new NotFoundException("Entry not found"));
        if (original.getType() == EntryType.REVERSAL) {
            throw new BusinessException("A reversal can't be reversed. Post a new entry instead.");
        }

        Request request = new Request(EntryType.REVERSAL, idempotencyKey, adminUserId,
                "Reversal of #" + entryId + ": " + reason.trim(), entryId,
                "Can't reverse: a wallet in this entry no longer has enough balance.");
        for (LedgerLine line : original.getLines()) {
            request.line(line.getAccountId(), -line.getAmount());
        }
        return post(request, () -> {
            if (entries.existsByReversesEntryId(entryId)) {
                throw new BusinessException("This entry has already been reversed.");
            }
        });
    }

    // ---------------------------------------------------------------- the one write path

    private PostedEntry post(Request request, Runnable rules) {
        Objects.requireNonNull(request.idempotencyKey, "idempotency key is required");

        // 1. Lock every involved account, lowest id first. TreeMap iterates in ascending order.
        Map<Long, Account> locked = new LinkedHashMap<>();
        for (Long accountId : new TreeMap<>(request.netByAccount()).keySet()) {
            locked.put(accountId, lock(accountId));
        }

        // 2. A key we have seen before returns the original result. Checked after locking so a
        //    concurrent duplicate waits for the first one to commit, then finds it here.
        var existing = entries.findByIdempotencyKey(request.idempotencyKey);
        if (existing.isPresent()) {
            JournalEntry original = existing.get();
            if (original.getType() != request.type || !Objects.equals(original.getCreatedBy(), request.createdBy)) {
                throw new BusinessException("This request was already used for something else. Please start again.");
            }
            return PostedEntry.of(original, true);
        }

        // 3. Business rules, then balances, with the rows locked.
        rules.run();

        long sum = request.lines.stream().mapToLong(Line::amount).sum();
        if (sum != 0) {
            throw new IllegalStateException("Journal entry does not balance: sum = " + sum);
        }
        if (request.lines.size() < 2 || request.lines.stream().anyMatch(l -> l.amount == 0)) {
            throw new IllegalStateException("Journal entry needs at least two non-zero lines");
        }

        for (var net : request.netByAccount().entrySet()) {
            Account account = locked.get(net.getKey());
            long after = Math.addExact(account.getBalance(), net.getValue());
            if (after < 0 && !account.getType().mayGoNegative()) {
                throw new InsufficientFundsException(request.insufficientMessage
                        + " Balance: " + Money.format(account.getBalance()) + ".");
            }
        }

        // 4. Write. Lines are applied in order so balance_after is right even if an account appears twice.
        JournalEntry entry = new JournalEntry(request.type, request.idempotencyKey, request.description,
                request.createdBy, request.reversesEntryId);
        for (Line line : request.lines) {
            Account account = locked.get(line.accountId);
            account.apply(line.amount);
            entry.addLine(line.accountId, line.amount, account.getBalance());
        }
        em.persist(entry);
        em.flush();
        return PostedEntry.of(entry, false);
    }

    /**
     * {@code SELECT ... FOR UPDATE} on one account. {@code refresh} (not a plain locking find) makes
     * sure we read the latest committed balance even if this account was already loaded earlier in
     * the same persistence context.
     */
    private Account lock(long accountId) {
        Account account = em.find(Account.class, accountId);
        if (account == null) {
            throw new NotFoundException("Account " + accountId + " not found");
        }
        em.refresh(account, LockModeType.PESSIMISTIC_WRITE);
        return account;
    }

    private long walletOf(long userId, AccountType type, String message) {
        return accounts.findIdByOwner(userId, type).orElseThrow(() -> new BusinessException(message));
    }

    private void requireActive(long userId, String message) {
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (user.isFrozen()) {
            throw new AccountFrozenException(message);
        }
    }

    private static void requireValidAmount(long amount, long max, String what) {
        if (amount <= 0) {
            throw new BusinessException("Amount must be more than RM 0.00.");
        }
        if (amount > max) {
            throw new LimitExceededException(what + " are limited to " + Money.format(max) + " each.");
        }
    }

    private record Line(long accountId, long amount) {
    }

    private static final class Request {
        final EntryType type;
        final UUID idempotencyKey;
        final Long createdBy;
        final String description;
        final Long reversesEntryId;
        final String insufficientMessage;
        final List<Line> lines = new ArrayList<>();

        Request(EntryType type, UUID idempotencyKey, Long createdBy, String description, Long reversesEntryId,
                String insufficientMessage) {
            this.type = type;
            this.idempotencyKey = idempotencyKey;
            this.createdBy = createdBy;
            this.description = description == null || description.isBlank() ? null : description.trim();
            this.reversesEntryId = reversesEntryId;
            this.insufficientMessage = insufficientMessage;
        }

        Request line(long accountId, long amount) {
            lines.add(new Line(accountId, amount));
            return this;
        }

        Map<Long, Long> netByAccount() {
            Map<Long, Long> net = new LinkedHashMap<>();
            for (Line line : lines) {
                net.merge(line.accountId, line.amount, Math::addExact);
            }
            return net;
        }
    }
}
