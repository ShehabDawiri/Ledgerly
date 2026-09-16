package com.breakingcode.Ledgerly.exception;

public class TransferNotFoundException extends LedgerException {
    public TransferNotFoundException(String transferId) {
        super("Transfer not found: " + transferId);
    }
}
