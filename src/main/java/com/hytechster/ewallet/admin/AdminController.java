package com.hytechster.ewallet.admin;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
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

import com.hytechster.ewallet.admin.AdminForms.CounterTopUpConfirm;
import com.hytechster.ewallet.admin.AdminForms.CounterTopUpForm;
import com.hytechster.ewallet.admin.AdminForms.ReverseConfirm;
import com.hytechster.ewallet.admin.AdminForms.ReverseForm;
import com.hytechster.ewallet.common.BusinessException;
import com.hytechster.ewallet.common.EwalletProperties;
import com.hytechster.ewallet.ledger.EntryType;
import com.hytechster.ewallet.ledger.EntryView;
import com.hytechster.ewallet.ledger.PostedEntry;
import com.hytechster.ewallet.user.AppUserDetails;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final int REPORT_DAYS = 14;

    private final AdminService admin;
    private final ReportService reports;
    private final EwalletProperties properties;

    public AdminController(AdminService admin, ReportService reports, EwalletProperties properties) {
        this.admin = admin;
        this.reports = reports;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- reports

    @GetMapping
    public String reports(Model model) {
        model.addAttribute("reports", reports.reports(REPORT_DAYS));
        model.addAttribute("types", EntryType.values());
        model.addAttribute("today", LocalDate.now(properties.displayZone()));
        model.addAttribute("monthStart", LocalDate.now(properties.displayZone()).withDayOfMonth(1));
        return "admin/reports";
    }

    @GetMapping("/export.csv")
    public void exportCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          HttpServletResponse response) throws IOException {
        if (to.isBefore(from)) {
            throw new BusinessException("The end date is before the start date.");
        }
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"ledger-" + from + "-to-" + to + ".csv\"");
        Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        reports.exportCsv(from, to, out);
        out.flush();
    }

    // ---------------------------------------------------------------- users

    @GetMapping("/users")
    public String users(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
                        Model model) {
        model.addAttribute("q", q);
        model.addAttribute("page", admin.searchUsers(q, PageRequest.of(Math.max(page, 0), 25)));
        return "admin/users";
    }

    @PostMapping("/users/{id}/freeze")
    public String freeze(@PathVariable long id, @RequestParam(defaultValue = "") String q, RedirectAttributes redirect) {
        admin.setFrozen(id, true);
        redirect.addFlashAttribute("notice", "Wallet frozen.");
        redirect.addAttribute("q", q);
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/unfreeze")
    public String unfreeze(@PathVariable long id, @RequestParam(defaultValue = "") String q,
                           RedirectAttributes redirect) {
        admin.setFrozen(id, false);
        redirect.addFlashAttribute("notice", "Wallet unfrozen.");
        redirect.addAttribute("q", q);
        return "redirect:/admin/users";
    }

    // ---------------------------------------------------------------- counter top-up

    @GetMapping("/topup")
    public String topUpForm(@RequestParam(defaultValue = "") String recipient, Model model) {
        model.addAttribute("form", new CounterTopUpForm(recipient, ""));
        model.addAttribute("limit", properties.limits().topUpMax());
        return "admin/topup";
    }

    @PostMapping("/topup/review")
    public String reviewTopUp(@Valid @ModelAttribute("form") CounterTopUpForm form, BindingResult errors, Model model) {
        model.addAttribute("limit", properties.limits().topUpMax());
        if (errors.hasErrors()) {
            return "admin/topup";
        }
        try {
            model.addAttribute("preview", admin.previewCounterTopUp(form.recipient(), form.amount()));
        } catch (BusinessException e) {
            errors.reject("rule", e.getMessage());
            return "admin/topup";
        }
        model.addAttribute("idempotencyKey", UUID.randomUUID());
        return "admin/topup-confirm";
    }

    @PostMapping("/topup")
    public String topUp(@AuthenticationPrincipal AppUserDetails me, @Valid CounterTopUpConfirm confirm,
                        BindingResult errors, RedirectAttributes redirect) {
        requireComplete(errors);
        PostedEntry posted = admin.counterTopUp(me.id(), confirm.userId(), confirm.amountSen(), confirm.idempotencyKey());
        redirect.addFlashAttribute("done", posted.replayed() ? "This top-up was already recorded." : "Top-up recorded.");
        return "redirect:/admin/entries/" + posted.entryId();
    }

    // ---------------------------------------------------------------- entries and reversals

    @GetMapping("/entries")
    public String entries(@RequestParam(required = false) EntryType type,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", admin.entries(type, from, to, PageRequest.of(Math.max(page, 0), 30)));
        model.addAttribute("type", type);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("types", EntryType.values());
        return "admin/entries";
    }

    @GetMapping("/entries/{id}")
    public String entry(@PathVariable long id, Model model) {
        model.addAttribute("entry", admin.entry(id));
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", ReverseForm.empty());
        }
        return "admin/entry";
    }

    @PostMapping("/entries/{id}/reverse/review")
    public String reviewReverse(@PathVariable long id, @Valid @ModelAttribute("form") ReverseForm form,
                                BindingResult errors, Model model) {
        EntryView entry = admin.entry(id);
        model.addAttribute("entry", entry);
        if (errors.hasErrors()) {
            return "admin/entry";
        }
        if (!entry.reversible()) {
            errors.reject("rule", entry.reversedByEntryId() != null
                    ? "This entry has already been reversed." : "A reversal can't be reversed.");
            return "admin/entry";
        }
        model.addAttribute("idempotencyKey", UUID.randomUUID());
        return "admin/reverse-confirm";
    }

    @PostMapping("/entries/{id}/reverse")
    public String reverse(@AuthenticationPrincipal AppUserDetails me, @PathVariable long id,
                          @Valid ReverseConfirm confirm, BindingResult errors, RedirectAttributes redirect) {
        requireComplete(errors);
        PostedEntry posted = admin.reverse(me.id(), id, confirm.reason(), confirm.idempotencyKey());
        redirect.addFlashAttribute("done", posted.replayed() ? "This reversal was already recorded." : "Entry reversed.");
        return "redirect:/admin/entries/" + posted.entryId();
    }

    private static void requireComplete(BindingResult errors) {
        if (errors.hasErrors()) {
            throw new BusinessException("Some details were missing, so nothing was posted. Please start again.");
        }
    }
}
