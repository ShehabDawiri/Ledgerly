package com.breakingcode.Ledgerly.exception;

public class IdempotencyConflictException extends LedgerException {
    public IdempotencyConflictException(String idempotencyId) {
        super("Idempotency key '" + idempotencyId
                + "' was already used for a transfer with different parameters");
    }
}
