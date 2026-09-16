package com.breakingcode.Ledgerly.exception;

public abstract class LedgerException extends RuntimeException {
    public LedgerException(String message) {
        super(message);
    }
}
