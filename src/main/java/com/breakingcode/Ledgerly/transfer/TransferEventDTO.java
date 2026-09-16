package com.breakingcode.Ledgerly.transfer;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferEventDTO(
        Long id,
        String transferId,
        Long accountId,
        EventType eventType,
        BigDecimal amount,
        Instant createdAt
) {
    public static TransferEventDTO from(TransferEvent event) {
        return new TransferEventDTO(
                event.getId(),
                event.getTransferId(),
                event.getAccountId(),
                event.getEventType(),
                event.getAmount(),
                event.getCreatedAt());
    }
}
