package com.breakingcode.Ledgerly.exception;


public class AccountNotFoundException extends LedgerException {
    public AccountNotFoundException(Long accountId) {
        super("Account not found: " + accountId);
    }
}
