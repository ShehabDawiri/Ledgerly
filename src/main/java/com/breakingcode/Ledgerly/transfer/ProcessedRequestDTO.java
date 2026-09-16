package com.breakingcode.Ledgerly.transfer;

import java.math.BigDecimal;
import java.time.Instant;

public record ProcessedRequestDTO(
        String idempotencyId,
        String requestFingerprint,
        String transferId,
        BigDecimal fromBalance,
        BigDecimal toBalance,
        Instant createdAt
) {
    public static ProcessedRequestDTO from(ProcessedRequest processed) {
        return new ProcessedRequestDTO(
                processed.getIdempotencyId(),
                processed.getRequestFingerprint(),
                processed.getTransferId(),
                processed.getFromBalance(),
                processed.getToBalance(),
                processed.getCreatedAt());
    }
}
