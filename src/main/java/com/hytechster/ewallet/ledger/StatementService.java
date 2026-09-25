package com.hytechster.ewallet.ledger;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hytechster.ewallet.common.NotFoundException;
import com.hytechster.ewallet.user.User;
import com.hytechster.ewallet.user.UserRepository;

/**
 * Read side of the ledger: statements and entry details with human labels.
 * Callers are responsible for passing an account the current user owns.
 */
@Service
@Transactional(readOnly = true)
public class StatementService {

    /** Wide enough to mean "no date filter". */
    public static final Instant BEGINNING = Instant.parse("2000-01-01T00:00:00Z");
    public static final Instant END = Instant.parse("2999-01-01T00:00:00Z");

    private final LedgerLineRepository lines;
    private final JournalEntryRepository entries;
    private final AccountRepository accounts;
    private final UserRepository users;

    public StatementService(LedgerLineRepository lines, JournalEntryRepository entries, AccountRepository accounts,
                            UserRepository users) {
        this.lines = lines;
        this.entries = entries;
        this.accounts = accounts;
        this.users = users;
    }

    public long balance(long accountId) {
        return accounts.findById(accountId).map(Account::getBalance)
                .orElseThrow(() -> new NotFoundException("Account not found"));
    }

    public Page<StatementLine> statement(long accountId, EntryType type, Instant from, Instant to, Pageable pageable) {
        Page<LedgerLine> page = lines.findStatement(accountId, type, from, to, pageable);
        return new PageImpl<>(toViews(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    public List<StatementLine> recent(long accountId, int count) {
        return statement(accountId, null, BEGINNING, END, PageRequest.of(0, count)).getContent();
    }

    /** The entry as seen from this account, or empty if the account is not part of it. */
    public Optional<StatementLine> lineFor(long accountId, long entryId) {
        return lines.findByAccountAndEntry(accountId, entryId).map(line -> toViews(List.of(line)).getFirst());
    }

    public Optional<Long> reversedBy(long entryId) {
        return entries.findByReversesEntryId(entryId).map(JournalEntry::getId);
    }

    public EntryView entry(long entryId) {
        JournalEntry entry = entries.findWithLines(entryId).orElseThrow(() -> new NotFoundException("Entry not found"));
        Map<Long, AccountLabel> labels = labels(entry.getLines().stream().map(LedgerLine::getAccountId).toList());
        String createdByName = entry.getCreatedBy() == null ? null
                : users.findById(entry.getCreatedBy()).map(User::getFullName).orElse(null);
        List<EntryView.Line> lineViews = entry.getLines().stream().map(l -> {
            AccountLabel label = labels.get(l.getAccountId());
            return new EntryView.Line(l.getAccountId(), label.type(), label.ownerUserId(), label.name(), l.getAmount(),
                    l.getBalanceAfter());
        }).toList();
        return new EntryView(entry.getId(), entry.getType(), entry.getCreatedAt(), entry.amount(),
                entry.getDescription(), entry.getCreatedBy(), createdByName, entry.getIdempotencyKey(),
                entry.getReversesEntryId(), reversedBy(entryId).orElse(null), lineViews);
    }

    private List<StatementLine> toViews(List<LedgerLine> own) {
        if (own.isEmpty()) {
            return List.of();
        }
        Set<Long> entryIds = own.stream().map(l -> l.getEntry().getId()).collect(Collectors.toSet());
        List<LedgerLine> all = lines.findByEntryIds(entryIds);
        Set<Long> accountIds = new HashSet<>();
        all.forEach(l -> accountIds.add(l.getAccountId()));
        Map<Long, AccountLabel> labels = labels(accountIds);

        return own.stream().map(line -> {
            JournalEntry entry = line.getEntry();
            String counterparty = all.stream()
                    .filter(o -> o.getEntry().getId().equals(entry.getId()) && !o.getAccountId().equals(line.getAccountId()))
                    .findFirst()
                    .map(o -> labels.get(o.getAccountId()).name())
                    .orElse("");
            return new StatementLine(entry.getId(), entry.getType(), entry.getCreatedAt(), line.getAmount(),
                    line.getBalanceAfter(), title(entry, line.getAmount(), counterparty), counterparty,
                    entry.getDescription(), entry.getReversesEntryId());
        }).toList();
    }

    private static String title(JournalEntry entry, long amount, String counterparty) {
        boolean in = amount > 0;
        return switch (entry.getType()) {
            case TOPUP -> "Top up";
            case TRANSFER -> in ? "From " + counterparty : "To " + counterparty;
            case PAYMENT -> counterparty;
            case REVERSAL -> "Reversal of #" + entry.getReversesEntryId();
        };
    }

    private Map<Long, AccountLabel> labels(java.util.Collection<Long> accountIds) {
        return accounts.findLabels(accountIds).stream()
                .collect(Collectors.toMap(AccountLabel::accountId, Function.identity()));
    }
}
