package com.hytechster.ewallet.user;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.hytechster.ewallet.common.BusinessException;

import jakarta.validation.Valid;

@Controller
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal AppUserDetails me) {
        return me != null ? "redirect:/" : "auth/login";
    }

    @GetMapping("/register")
    public String registerForm(Authentication authentication, Model model) {
        if (authentication != null && authentication.isAuthenticated()) {
            return "redirect:/";
        }
        model.addAttribute("form", RegistrationForm.empty());
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult errors,
                           RedirectAttributes redirect) {
        if (errors.hasErrors()) {
            return "auth/register";
        }
        try {
            userService.registerStudent(form);
        } catch (BusinessException e) {
            errors.reject("taken", e.getMessage());
            return "auth/register";
        }
        redirect.addFlashAttribute("registered", form.email().trim().toLowerCase());
        return "redirect:/login";
    }
}
