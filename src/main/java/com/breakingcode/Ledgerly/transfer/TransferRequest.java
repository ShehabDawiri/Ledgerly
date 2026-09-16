package com.breakingcode.Ledgerly.transfer;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record TransferRequest(
        @NotNull Long fromId,
        @NotNull Long toId,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank String idempotencyId) {
}
