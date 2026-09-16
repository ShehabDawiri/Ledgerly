package com.breakingcode.Ledgerly.exception;

public class SelfTransferException extends LedgerException {
    public SelfTransferException(Long accountId) {
        super("Cannot transfer to the same account: " + accountId);
    }
}
