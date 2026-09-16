package com.breakingcode.Ledgerly.transfer;

import java.math.BigDecimal;

public record TransferResult(
        boolean success,
        String transferId,
        BigDecimal fromBalance,
        BigDecimal toBalance
) {
    /** Replays a previously recorded result for a repeated idempotencyId. */
    public static TransferResult from(ProcessedRequest processed) {
        return new TransferResult(true,
                processed.getTransferId(),
                processed.getFromBalance(),
                processed.getToBalance());
    }
}
