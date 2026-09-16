package com.breakingcode.Ledgerly.account;

import java.math.BigDecimal;

/**
 * Port for appending an account's opening balance to the ledger.
 *
 * Declared here and implemented in the transfer package so the dependency points
 * transfer -> account, never the other way. Without it, opening an account would need
 * the account package to reach into the event log and the two packages would depend on
 * each other.
 */
public interface OpeningBalanceRecorder {

    /**
     * Appends the OPENING event for a newly created account. Must be called inside the
     * same transaction that persisted the account, so an account can never exist without
     * the event that explains its starting balance.
     */
    void recordOpeningBalance(Long accountId, BigDecimal openingBalance);
}
