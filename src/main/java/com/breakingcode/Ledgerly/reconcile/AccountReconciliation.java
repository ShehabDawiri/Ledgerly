package com.breakingcode.Ledgerly.reconcile;

import java.math.BigDecimal;

/** One account's stored balance compared against the balance derived from its events. */
public record AccountReconciliation(
        Long accountId,
        String name,
        BigDecimal storedBalance,
        BigDecimal derivedBalance,
        BigDecimal difference,
        boolean matches
) {}
