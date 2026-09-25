package com.hytechster.ewallet.wallet;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hytechster.ewallet.common.AccountFrozenException;
import com.hytechster.ewallet.common.BusinessException;
import com.hytechster.ewallet.common.EwalletProperties;
import com.hytechster.ewallet.common.InsufficientFundsException;
import com.hytechster.ewallet.common.LimitExceededException;
import com.hytechster.ewallet.common.Money;
import com.hytechster.ewallet.common.NotFoundException;
import com.hytechster.ewallet.ledger.AccountRepository;
import com.hytechster.ewallet.ledger.AccountType;
import com.hytechster.ewallet.ledger.EntryType;
import com.hytechster.ewallet.ledger.LedgerService;
import com.hytechster.ewallet.ledger.PostedEntry;
import com.hytechster.ewallet.ledger.StatementLine;
import com.hytechster.ewallet.ledger.StatementService;
import com.hytechster.ewallet.merchant.Merchant;
import com.hytechster.ewallet.merchant.MerchantService;
import com.hytechster.ewallet.user.Role;
import com.hytechster.ewallet.user.User;
import com.hytechster.ewallet.user.UserService;

/**
 * Student wallet use cases. Resolves people and merchants, runs friendly pre-checks for the
 * confirm screens, and hands the actual money movement to {@link LedgerService}, which checks
 * everything again under lock.
 */
@Service
public class WalletService {

    private final LedgerService ledger;
    private final StatementService statements;
    private final AccountRepository accounts;
    private final UserService userService;
    private final MerchantService merchantService;
    private final EwalletProperties properties;

    public WalletService(LedgerService ledger, StatementService statements, AccountRepository accounts,
                         UserService userService, MerchantService merchantService, EwalletProperties properties) {
        this.ledger = ledger;
        this.statements = statements;
        this.accounts = accounts;
        this.userService = userService;
        this.merchantService = merchantService;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Dashboard dashboard(long userId) {
        User user = userService.get(userId);
        long accountId = walletOf(userId);
        return new Dashboard(user, statements.balance(accountId), statements.recent(accountId, 5));
    }

    @Transactional(readOnly = true)
    public long balance(long userId) {
        return statements.balance(walletOf(userId));
    }

    @Transactional(readOnly = true)
    public Page<StatementLine> history(long userId, EntryType type, LocalDate from, LocalDate to, Pageable pageable) {
        Instant start = from == null ? StatementService.BEGINNING : from.atStartOfDay(properties.displayZone()).toInstant();
        Instant end = to == null ? StatementService.END : to.plusDays(1).atStartOfDay(properties.displayZone()).toInstant();
        return statements.statement(walletOf(userId), type, start, end, pageable);
    }

    /** One entry, only if this user's wallet is part of it. Otherwise it does not exist for them. */
    @Transactional(readOnly = true)
    public Receipt receipt(long userId, long entryId) {
        StatementLine line = statements.lineFor(walletOf(userId), entryId)
                .orElseThrow(() -> new NotFoundException("Entry not found"));
        return new Receipt(line, statements.reversedBy(entryId).orElse(null));
    }

    // ---------------------------------------------------------------- confirm-screen pre-checks

    @Transactional(readOnly = true)
    public Preview previewTopUp(long userId, String amountText) {
        long amount = Money.parseSen(amountText);
        User me = userService.get(userId);
        requireActive(me);
        requireLimit(amount, properties.limits().topUpMax(), "Top-ups");
        long balance = balance(userId);
        return new Preview(amount, balance, balance + amount, null, null, null);
    }

    @Transactional(readOnly = true)
    public Preview previewTransfer(long userId, String recipientQuery, String amountText, String note) {
        long amount = Money.parseSen(amountText);
        User me = userService.get(userId);
        requireActive(me);
        User recipient = userService.findByEmailOrStudentId(recipientQuery)
                .filter(u -> u.getRole() == Role.USER)
                .orElseThrow(() -> new BusinessException("We couldn't find a student with that email or student ID."));
        if (recipient.getId().equals(me.getId())) {
            throw new BusinessException("You can't send money to yourself.");
        }
        requireLimit(amount, properties.limits().transferMax(), "Transfers");
        long balance = requireBalance(userId, amount);
        return new Preview(amount, balance, balance - amount, recipient.getId(), recipient.getFullName(),
                maskEmail(recipient.getEmail()), blankToNull(note));
    }

    @Transactional(readOnly = true)
    public Preview previewPayment(long userId, String code, String amountText, String note) {
        long amount = Money.parseSen(amountText);
        User me = userService.get(userId);
        requireActive(me);
        Merchant merchant = merchantService.findByCode(code)
                .orElseThrow(() -> new BusinessException("No merchant has that code. Check the sign at the counter."));
        requireLimit(amount, properties.limits().paymentMax(), "Payments");
        long balance = requireBalance(userId, amount);
        return new Preview(amount, balance, balance - amount, merchant.getUserId(), merchant.getName(),
                merchant.getCode(), blankToNull(note));
    }

    // ---------------------------------------------------------------- money actions

    @Transactional
    public PostedEntry topUp(long userId, long amount, UUID idempotencyKey) {
        return ledger.topUp(userId, amount, idempotencyKey, userId, "Demo card top-up");
    }

    @Transactional
    public PostedEntry transfer(long userId, long recipientUserId, long amount, String note, UUID idempotencyKey) {
        return ledger.transfer(userId, recipientUserId, amount, idempotencyKey, note);
    }

    @Transactional
    public PostedEntry pay(long userId, String merchantCode, long amount, String note, UUID idempotencyKey) {
        Merchant merchant = merchantService.findByCode(merchantCode)
                .orElseThrow(() -> new BusinessException("No merchant has that code."));
        return ledger.pay(userId, merchant.getUserId(), amount, idempotencyKey, note);
    }

    // ---------------------------------------------------------------- helpers

    private long walletOf(long userId) {
        return accounts.findIdByOwner(userId, AccountType.USER_WALLET)
                .orElseThrow(() -> new NotFoundException("Wallet not found"));
    }

    private long requireBalance(long userId, long amount) {
        long balance = balance(userId);
        if (balance < amount) {
            throw new InsufficientFundsException("Not enough balance. You have " + Money.format(balance) + ".");
        }
        return balance;
    }

    private static void requireActive(User user) {
        if (user.isFrozen()) {
            throw new AccountFrozenException("Your wallet is frozen. Visit the e-wallet office for help.");
        }
    }

    private static void requireLimit(long amount, long max, String what) {
        if (amount <= 0) {
            throw new BusinessException("Amount must be more than RM 0.00.");
        }
        if (amount > max) {
            throw new LimitExceededException(what + " are limited to " + Money.format(max) + " each.");
        }
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        String name = email.substring(0, at);
        String shown = name.length() <= 2 ? name.substring(0, 1) : name.substring(0, 2);
        return shown + "***" + email.substring(at);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record Dashboard(User user, long balance, List<StatementLine> recent) {
    }

    public record Receipt(StatementLine line, Long reversedByEntryId) {
    }

    /** What the confirm screen shows. {@code partyId} is the recipient user id; {@code partyHint} a masked email or merchant code. */
    public record Preview(long amount, long balanceBefore, long balanceAfter, Long partyId, String partyName,
                          String partyHint, String note) {

        Preview(long amount, long balanceBefore, long balanceAfter, Long partyId, String partyName, String partyHint) {
            this(amount, balanceBefore, balanceAfter, partyId, partyName, partyHint, null);
        }
    }
}
