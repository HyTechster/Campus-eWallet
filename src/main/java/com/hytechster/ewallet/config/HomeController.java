package com.hytechster.ewallet.config;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.hytechster.ewallet.user.AppUserDetails;

/** Sends each role to its own home after login. */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home(@AuthenticationPrincipal AppUserDetails me) {
        return switch (me.role()) {
            case ADMIN -> "redirect:/admin";
            case MERCHANT -> "redirect:/merchant";
            case USER -> "redirect:/wallet";
        };
    }
}
