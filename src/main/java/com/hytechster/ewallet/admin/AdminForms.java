package com.hytechster.ewallet.admin;

import java.util.UUID;

import com.hytechster.ewallet.common.Money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class AdminForms {

    private AdminForms() {
    }

    public record CounterTopUpForm(
            @NotBlank(message = "Enter an email or student ID") @Size(max = 255) String recipient,
            @NotBlank(message = "Enter an amount")
            @Pattern(regexp = Money.INPUT_PATTERN, message = "Enter an amount like 50.00") String amount) {
    }

    public record CounterTopUpConfirm(
            @NotNull Long userId,
            @NotNull @Positive Long amountSen,
            @NotNull UUID idempotencyKey) {
    }

    public record ReverseForm(
            @NotBlank(message = "Give a reason") @Size(max = 150, message = "Keep it under 150 characters") String reason) {

        public static ReverseForm empty() {
            return new ReverseForm("");
        }
    }

    public record ReverseConfirm(
            @NotBlank @Size(max = 150) String reason,
            @NotNull UUID idempotencyKey) {
    }
}
