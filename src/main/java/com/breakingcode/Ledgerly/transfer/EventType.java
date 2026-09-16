package com.breakingcode.Ledgerly.transfer;

public enum EventType {
    /** Opening balance credited when an account is created. */
    OPENING,
    DEBIT,
    CREDIT;

    /** The signed contribution this event makes to an account's balance. */
    public java.math.BigDecimal signed(java.math.BigDecimal amount) {
        return this == DEBIT ? amount.negate() : amount;
    }
}
