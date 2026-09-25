package com.hytechster.ewallet.merchant;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.hytechster.ewallet.user.AppUserDetails;

@Controller
@RequestMapping("/merchant")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @GetMapping
    public String dashboard(@AuthenticationPrincipal AppUserDetails me, Model model) {
        model.addAttribute("dashboard", merchantService.dashboard(me.id()));
        return "merchant/dashboard";
    }

    @GetMapping("/payments")
    public String payments(@AuthenticationPrincipal AppUserDetails me, @RequestParam(defaultValue = "0") int page,
                           Model model) {
        model.addAttribute("page", merchantService.payments(me.id(), PageRequest.of(Math.max(page, 0), 20)));
        return "merchant/payments";
    }
}
