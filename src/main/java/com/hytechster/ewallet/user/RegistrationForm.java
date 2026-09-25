package com.hytechster.ewallet.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrationForm(
        @NotBlank(message = "Enter your name") @Size(max = 120) String fullName,
        @NotBlank(message = "Enter your email") @Email(message = "That email doesn't look right") @Size(max = 255) String email,
        @NotBlank(message = "Enter your student ID")
        @Pattern(regexp = "^[A-Za-z0-9]{4,30}$", message = "Letters and numbers only, 4 to 30 characters") String studentId,
        @NotBlank(message = "Choose a password") @Size(min = 8, max = 72, message = "Use 8 to 72 characters") String password) {

    public static RegistrationForm empty() {
        return new RegistrationForm("", "", "", "");
    }
}
