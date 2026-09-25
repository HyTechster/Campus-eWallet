package com.hytechster.ewallet.wallet;

import java.util.UUID;

import com.hytechster.ewallet.common.Money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Form records. Step one of each flow takes what the user types; the confirm step carries the
 * resolved values plus the idempotency key in hidden fields. The sender is never a form field.
 */
public final class WalletForms {

    private WalletForms() {
    }

    public record TopUpForm(
            @NotBlank(message = "Enter an amount")
            @Pattern(regexp = Money.INPUT_PATTERN, message = "Enter an amount like 20.00") String amount) {
    }

    public record TopUpConfirm(
            @NotNull @Positive Long amountSen,
            @NotNull UUID idempotencyKey) {
    }

    public record TransferForm(
            @NotBlank(message = "Enter an email or student ID") @Size(max = 255) String recipient,
            @NotBlank(message = "Enter an amount")
            @Pattern(regexp = Money.INPUT_PATTERN, message = "Enter an amount like 10.00") String amount,
            @Size(max = 80, message = "Keep the note under 80 characters") String note) {
    }

    public record TransferConfirm(
            @NotNull Long recipientUserId,
            @NotNull @Positive Long amountSen,
            @Size(max = 80) String note,
            @NotNull UUID idempotencyKey) {
    }

    public record PayForm(
            @NotBlank(message = "Enter the merchant code") @Size(max = 20) String code,
            @NotBlank(message = "Enter an amount")
            @Pattern(regexp = Money.INPUT_PATTERN, message = "Enter an amount like 6.50") String amount,
            @Size(max = 80, message = "Keep the note under 80 characters") String note) {
    }

    public record PayConfirm(
            @NotBlank @Size(max = 20) String code,
            @NotNull @Positive Long amountSen,
            @Size(max = 80) String note,
            @NotNull UUID idempotencyKey) {
    }
}
