package com.breakingcode.Ledgerly.exception;

import java.math.BigDecimal;

public class InvalidAmountException extends LedgerException {
  public InvalidAmountException(BigDecimal amount) {
    super("Amount must be positive, got: " + amount);
  }
}
