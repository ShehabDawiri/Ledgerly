package com.breakingcode.Ledgerly.transfer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The idempotency record: one row per accepted idempotencyId, holding the result
 * that was returned the first time. A retry of the same request replays this row
 * instead of moving money again.
 *
 * The unique constraint on idempotency_id is what actually enforces idempotency --
 * a check-then-act in application code races under concurrency, so we let the
 * database arbitrate and the loser of the race replays the winner's result.
 */
@Entity
@Table(name = "processed_request",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_processed_request_idempotency_id",
                columnNames = "idempotency_id"))
@ToString
@Getter
@NoArgsConstructor
public class ProcessedRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_id", nullable = false, updatable = false)
    private String idempotencyId;

    /** SHA-256 of the transfer parameters, so a key reused with different params is caught. */
    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private String transferId;

    @Column(name = "from_balance", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal fromBalance;

    @Column(name = "to_balance", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal toBalance;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    public ProcessedRequest(String idempotencyId, String requestFingerprint, TransferResult result) {
        this.idempotencyId = idempotencyId;
        this.requestFingerprint = requestFingerprint;
        this.transferId = result.transferId();
        this.fromBalance = result.fromBalance();
        this.toBalance = result.toBalance();
    }
}
