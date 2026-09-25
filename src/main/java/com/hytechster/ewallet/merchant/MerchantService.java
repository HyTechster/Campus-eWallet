package com.hytechster.ewallet.merchant;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hytechster.ewallet.common.BusinessException;
import com.hytechster.ewallet.common.EwalletProperties;
import com.hytechster.ewallet.common.NotFoundException;
import com.hytechster.ewallet.ledger.AccountRepository;
import com.hytechster.ewallet.ledger.AccountType;
import com.hytechster.ewallet.ledger.EntryType;
import com.hytechster.ewallet.ledger.LedgerLineRepository;
import com.hytechster.ewallet.ledger.StatementLine;
import com.hytechster.ewallet.ledger.StatementService;
import com.hytechster.ewallet.user.Role;
import com.hytechster.ewallet.user.User;
import com.hytechster.ewallet.user.UserService;

@Service
public class MerchantService {

    private final MerchantRepository merchants;
    private final UserService userService;
    private final AccountRepository accounts;
    private final LedgerLineRepository lines;
    private final StatementService statements;
    private final EwalletProperties properties;

    public MerchantService(MerchantRepository merchants, UserService userService, AccountRepository accounts,
                           LedgerLineRepository lines, StatementService statements, EwalletProperties properties) {
        this.merchants = merchants;
        this.userService = userService;
        this.accounts = accounts;
        this.lines = lines;
        this.statements = statements;
        this.properties = properties;
    }

    @Transactional
    public Merchant createMerchant(String email, String rawPassword, String name, String code) {
        if (merchants.existsByCode(code.trim().toUpperCase())) {
            throw new BusinessException("That merchant code is taken.");
        }
        User user = userService.createUser(email, rawPassword, name, null, Role.MERCHANT);
        return merchants.save(new Merchant(user.getId(), code, name));
    }

    @Transactional(readOnly = true)
    public Optional<Merchant> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return merchants.findByCode(code.trim().toUpperCase());
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(long merchantUserId) {
        Merchant merchant = merchantOf(merchantUserId);
        long accountId = walletOf(merchantUserId);
        Instant startOfToday = LocalDate.now(properties.displayZone()).atStartOfDay(properties.displayZone()).toInstant();
        long todaySales = lines.sumSince(accountId, List.of(EntryType.PAYMENT, EntryType.REVERSAL), startOfToday);
        long todayCount = lines.countSince(accountId, EntryType.PAYMENT, startOfToday);
        return new Dashboard(merchant, statements.balance(accountId), todaySales, todayCount,
                statements.recent(accountId, 5));
    }

    @Transactional(readOnly = true)
    public Page<StatementLine> payments(long merchantUserId, Pageable pageable) {
        return statements.statement(walletOf(merchantUserId), null, StatementService.BEGINNING, StatementService.END,
                pageable);
    }

    private Merchant merchantOf(long userId) {
        return merchants.findByUserId(userId).orElseThrow(() -> new NotFoundException("Merchant not found"));
    }

    private long walletOf(long userId) {
        return accounts.findIdByOwner(userId, AccountType.MERCHANT_WALLET)
                .orElseThrow(() -> new NotFoundException("Merchant wallet not found"));
    }

    public record Dashboard(Merchant merchant, long balance, long todaySales, long todayCount,
                            List<StatementLine> recent) {
    }
}
