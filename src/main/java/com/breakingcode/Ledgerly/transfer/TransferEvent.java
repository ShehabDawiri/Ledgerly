package com.breakingcode.Ledgerly.transfer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An immutable ledger event. This table is the source of truth: every balance in
 * the system is a fold over the events for that account, and /reconcile proves it.
 * Nothing here is ever updated or deleted -- hence updatable = false throughout.
 */
@Entity
@Table(name = "transfer_event",
        indexes = {
                @Index(name = "idx_transfer_event_account_id", columnList = "account_id"),
                @Index(name = "idx_transfer_event_transfer_id", columnList = "transfer_id")
        })
@Getter
@ToString
@NoArgsConstructor
public class TransferEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private String transferId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    // STRING, not the default ORDINAL: the event log is permanent history, and an
    // ordinal would silently rewrite it the moment someone reorders the enum.
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 16)
    private EventType eventType;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    public TransferEvent(String transferId, BigDecimal amount, Long accountId, EventType eventType) {
        this.transferId = transferId;
        this.amount = amount;
        this.accountId = accountId;
        this.eventType = eventType;
    }
}
