package com.hytechster.ewallet.wallet;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.hytechster.ewallet.common.BusinessException;
import com.hytechster.ewallet.common.EwalletProperties;
import com.hytechster.ewallet.ledger.EntryType;
import com.hytechster.ewallet.ledger.PostedEntry;
import com.hytechster.ewallet.user.AppUserDetails;
import com.hytechster.ewallet.wallet.WalletForms.PayConfirm;
import com.hytechster.ewallet.wallet.WalletForms.PayForm;
import com.hytechster.ewallet.wallet.WalletForms.TopUpConfirm;
import com.hytechster.ewallet.wallet.WalletForms.TopUpForm;
import com.hytechster.ewallet.wallet.WalletForms.TransferConfirm;
import com.hytechster.ewallet.wallet.WalletForms.TransferForm;

import jakarta.validation.Valid;

/**
 * Every money action is three steps: form, confirm (shows amount and recipient, carries a fresh
 * idempotency key), then a POST that redirects to the receipt. The sender is always the principal.
 */
@Controller
@RequestMapping("/wallet")
public class WalletController {

    private static final int HISTORY_PAGE_SIZE = 20;

    private final WalletService wallet;
    private final EwalletProperties properties;

    public WalletController(WalletService wallet, EwalletProperties properties) {
        this.wallet = wallet;
        this.properties = properties;
    }

    @GetMapping
    public String dashboard(@AuthenticationPrincipal AppUserDetails me, Model model) {
        model.addAttribute("dashboard", wallet.dashboard(me.id()));
        return "wallet/dashboard";
    }

    // ---------------------------------------------------------------- top up

    @GetMapping("/topup")
    public String topUpForm(@AuthenticationPrincipal AppUserDetails me,
                            @RequestParam(defaultValue = "") String amount, Model model) {
        model.addAttribute("form", new TopUpForm(amount));
        return topUpPage(me, model);
    }

    @PostMapping("/topup/review")
    public String reviewTopUp(@AuthenticationPrincipal AppUserDetails me, @Valid @ModelAttribute("form") TopUpForm form,
                              BindingResult errors, Model model) {
        if (errors.hasErrors()) {
            return topUpPage(me, model);
        }
        try {
            model.addAttribute("preview", wallet.previewTopUp(me.id(), form.amount()));
        } catch (BusinessException e) {
            errors.reject("rule", e.getMessage());
            return topUpPage(me, model);
        }
        model.addAttribute("idempotencyKey", UUID.randomUUID());
        return "wallet/topup-confirm";
    }

    @PostMapping("/topup")
    public String topUp(@AuthenticationPrincipal AppUserDetails me, @Valid TopUpConfirm confirm, BindingResult errors,
                        RedirectAttributes redirect) {
        requireComplete(errors);
        return receipt(wallet.topUp(me.id(), confirm.amountSen(), confirm.idempotencyKey()), redirect);
    }

    private String topUpPage(AppUserDetails me, Model model) {
        model.addAttribute("balance", wallet.balance(me.id()));
        model.addAttribute("limit", properties.limits().topUpMax());
        return "wallet/topup";
    }

    // ---------------------------------------------------------------- transfer

    @GetMapping("/transfer")
    public String transferForm(@AuthenticationPrincipal AppUserDetails me,
                               @RequestParam(defaultValue = "") String recipient,
                               @RequestParam(defaultValue = "") String amount,
                               @RequestParam(defaultValue = "") String note, Model model) {
        model.addAttribute("form", new TransferForm(recipient, amount, note));
        return transferPage(me, model);
    }

    @PostMapping("/transfer/review")
    public String reviewTransfer(@AuthenticationPrincipal AppUserDetails me,
                                 @Valid @ModelAttribute("form") TransferForm form, BindingResult errors, Model model) {
        if (errors.hasErrors()) {
            return transferPage(me, model);
        }
        try {
            model.addAttribute("preview", wallet.previewTransfer(me.id(), form.recipient(), form.amount(), form.note()));
        } catch (BusinessException e) {
            errors.reject("rule", e.getMessage());
            return transferPage(me, model);
        }
        model.addAttribute("idempotencyKey", UUID.randomUUID());
        return "wallet/transfer-confirm";
    }

    @PostMapping("/transfer")
    public String transfer(@AuthenticationPrincipal AppUserDetails me, @Valid TransferConfirm confirm,
                           BindingResult errors, RedirectAttributes redirect) {
        requireComplete(errors);
        return receipt(wallet.transfer(me.id(), confirm.recipientUserId(), confirm.amountSen(), confirm.note(),
                confirm.idempotencyKey()), redirect);
    }

    private String transferPage(AppUserDetails me, Model model) {
        model.addAttribute("balance", wallet.balance(me.id()));
        model.addAttribute("limit", properties.limits().transferMax());
        return "wallet/transfer";
    }

    // ---------------------------------------------------------------- pay merchant

    @GetMapping("/pay")
    public String payForm(@AuthenticationPrincipal AppUserDetails me, @RequestParam(defaultValue = "") String code,
                          @RequestParam(defaultValue = "") String amount,
                          @RequestParam(defaultValue = "") String note, Model model) {
        model.addAttribute("form", new PayForm(code, amount, note));
        return payPage(me, model);
    }

    @PostMapping("/pay/review")
    public String reviewPay(@AuthenticationPrincipal AppUserDetails me, @Valid @ModelAttribute("form") PayForm form,
                            BindingResult errors, Model model) {
        if (errors.hasErrors()) {
            return payPage(me, model);
        }
        try {
            model.addAttribute("preview", wallet.previewPayment(me.id(), form.code(), form.amount(), form.note()));
        } catch (BusinessException e) {
            errors.reject("rule", e.getMessage());
            return payPage(me, model);
        }
        model.addAttribute("idempotencyKey", UUID.randomUUID());
        return "wallet/pay-confirm";
    }

    @PostMapping("/pay")
    public String pay(@AuthenticationPrincipal AppUserDetails me, @Valid PayConfirm confirm, BindingResult errors,
                      RedirectAttributes redirect) {
        requireComplete(errors);
        return receipt(wallet.pay(me.id(), confirm.code(), confirm.amountSen(), confirm.note(),
                confirm.idempotencyKey()), redirect);
    }

    private String payPage(AppUserDetails me, Model model) {
        model.addAttribute("balance", wallet.balance(me.id()));
        model.addAttribute("limit", properties.limits().paymentMax());
        return "wallet/pay";
    }

    // ---------------------------------------------------------------- history and receipts

    @GetMapping("/history")
    public String history(@AuthenticationPrincipal AppUserDetails me,
                          @RequestParam(required = false) EntryType type,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(defaultValue = "0") int page,
                          Model model) {
        model.addAttribute("page", wallet.history(me.id(), type, from, to,
                PageRequest.of(Math.max(page, 0), HISTORY_PAGE_SIZE)));
        model.addAttribute("type", type);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("types", EntryType.values());
        return "wallet/history";
    }

    @GetMapping("/receipt/{entryId}")
    public String receipt(@AuthenticationPrincipal AppUserDetails me, @PathVariable long entryId, Model model) {
        model.addAttribute("receipt", wallet.receipt(me.id(), entryId));
        return "wallet/receipt";
    }

    private static String receipt(PostedEntry posted, RedirectAttributes redirect) {
        redirect.addFlashAttribute("done", true);
        redirect.addFlashAttribute("replayed", posted.replayed());
        return "redirect:/wallet/receipt/" + posted.entryId();
    }

    private static void requireComplete(BindingResult errors) {
        if (errors.hasErrors()) {
            throw new BusinessException("Some details were missing, so nothing was sent. Please start again.");
        }
    }
}
