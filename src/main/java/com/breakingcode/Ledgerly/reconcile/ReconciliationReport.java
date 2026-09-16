package com.breakingcode.Ledgerly.reconcile;

import java.math.BigDecimal;
import java.util.List;

/**
 * The headline proof: every stored balance recomputed from the event log, plus the
 * conservation check that no money was created or destroyed in aggregate.
 */
public record ReconciliationReport(
        boolean balanced,
        int accountsChecked,
        long totalEvents,
        long transfersRecorded,
        BigDecimal storedTotal,
        BigDecimal derivedTotal,
        BigDecimal openingTotal,
        BigDecimal totalDebited,
        BigDecimal totalCredited,
        boolean conserved,
        long retriesAbsorbed,
        long retriesExhausted,
        long idempotentReplays,
        List<AccountReconciliation> accounts,
        List<AccountReconciliation> discrepancies
) {}
