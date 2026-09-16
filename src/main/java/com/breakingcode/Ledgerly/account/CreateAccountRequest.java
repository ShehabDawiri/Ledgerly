package com.breakingcode.Ledgerly.account;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank @Size(max = 100) String name,

        // Zero is allowed: an account may legitimately open empty. Negative is not --
        // the ledger has no concept of an overdraft.
        @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal openingBalance) {
}
