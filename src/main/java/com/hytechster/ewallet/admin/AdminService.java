package com.hytechster.ewallet.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hytechster.ewallet.common.AccountFrozenException;
import com.hytechster.ewallet.common.BusinessException;
import com.hytechster.ewallet.common.EwalletProperties;
import com.hytechster.ewallet.common.LimitExceededException;
import com.hytechster.ewallet.common.Money;
import com.hytechster.ewallet.ledger.Account;
import com.hytechster.ewallet.ledger.AccountRepository;
import com.hytechster.ewallet.ledger.EntryType;
import com.hytechster.ewallet.ledger.EntryView;
import com.hytechster.ewallet.ledger.JournalEntryRepository;
import com.hytechster.ewallet.ledger.LedgerService;
import com.hytechster.ewallet.ledger.PostedEntry;
import com.hytechster.ewallet.ledger.StatementService;
import com.hytechster.ewallet.user.Role;
import com.hytechster.ewallet.user.User;
import com.hytechster.ewallet.user.UserRepository;
import com.hytechster.ewallet.user.UserService;
import com.hytechster.ewallet.user.UserStatus;

@Service
public class AdminService {

    private final UserRepository users;
    private final UserService userService;
    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final StatementService statements;
    private final LedgerService ledger;
    private final EwalletProperties properties;

    public AdminService(UserRepository users, UserService userService, AccountRepository accounts,
                        JournalEntryRepository entries, StatementService statements, LedgerService ledger,
                        EwalletProperties properties) {
        this.users = users;
        this.userService = userService;
        this.accounts = accounts;
        this.entries = entries;
        this.statements = statements;
        this.ledger = ledger;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Page<UserRow> searchUsers(String query, Pageable pageable) {
        return users.search(query == null ? "" : query.trim(), pageable).map(u -> new UserRow(
                u.getId(), u.getFullName(), u.getEmail(), u.getStudentId(), u.getRole(), u.getStatus(),
                accounts.findByOwnerUserId(u.getId()).map(Account::getBalance).orElse(null)));
    }

    @Transactional
    public void setFrozen(long userId, boolean frozen) {
        userService.setFrozen(userId, frozen);
    }

    @Transactional(readOnly = true)
    public CounterPreview previewCounterTopUp(String recipientQuery, String amountText) {
        long amount = Money.parseSen(amountText);
        User user = userService.findByEmailOrStudentId(recipientQuery)
                .filter(u -> u.getRole() == Role.USER)
                .orElseThrow(() -> new BusinessException("No student has that email or student ID."));
        if (user.isFrozen()) {
            throw new AccountFrozenException(user.getFullName() + "'s wallet is frozen. Unfreeze it first.");
        }
        if (amount <= 0) {
            throw new BusinessException("Amount must be more than RM 0.00.");
        }
        if (amount > properties.limits().topUpMax()) {
            throw new LimitExceededException("Top-ups are limited to " + Money.format(properties.limits().topUpMax()) + " each.");
        }
        long balance = accounts.findByOwnerUserId(user.getId()).map(Account::getBalance).orElse(0L);
        return new CounterPreview(user.getId(), user.getFullName(), user.getEmail(), user.getStudentId(), amount,
                balance, balance + amount);
    }

    public PostedEntry counterTopUp(long adminUserId, long userId, long amount, UUID idempotencyKey) {
        return ledger.topUp(userId, amount, idempotencyKey, adminUserId, "Counter top-up (cash)");
    }

    @Transactional(readOnly = true)
    public Page<EntryRow> entries(EntryType type, LocalDate from, LocalDate to, Pageable pageable) {
        Instant start = from == null ? StatementService.BEGINNING : from.atStartOfDay(properties.displayZone()).toInstant();
        Instant end = to == null ? StatementService.END : to.plusDays(1).atStartOfDay(properties.displayZone()).toInstant();
        return entries.search(type, start, end, pageable).map(e -> new EntryRow(e.getId(), e.getType(),
                e.getCreatedAt(), e.amount(), e.getDescription(), e.getReversesEntryId()));
    }

    @Transactional(readOnly = true)
    public EntryView entry(long entryId) {
        return statements.entry(entryId);
    }

    public PostedEntry reverse(long adminUserId, long entryId, String reason, UUID idempotencyKey) {
        return ledger.reverse(entryId, reason, adminUserId, idempotencyKey);
    }

    public record UserRow(long id, String fullName, String email, String studentId, Role role, UserStatus status,
                          Long balance) {

        public boolean frozen() {
            return status == UserStatus.FROZEN;
        }
    }

    public record CounterPreview(long userId, String fullName, String email, String studentId, long amount,
                                 long balanceBefore, long balanceAfter) {
    }

    public record EntryRow(long id, EntryType type, Instant createdAt, long amount, String description,
                           Long reversesEntryId) {
    }
}
