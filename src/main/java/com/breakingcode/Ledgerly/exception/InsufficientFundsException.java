package com.breakingcode.Ledgerly.exception;

import java.math.BigDecimal;

public class InsufficientFundsException extends LedgerException {
    public InsufficientFundsException(Long accountId, BigDecimal balance, BigDecimal amount) {
        super("Insufficient funds in account " + accountId
                + ": balance " + balance + ", requested " + amount);
    }
}
