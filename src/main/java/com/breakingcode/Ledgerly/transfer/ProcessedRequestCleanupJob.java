package com.breakingcode.Ledgerly.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Expires idempotency records past their retention window.
 *
 * The processed_request table is an unbounded write log otherwise: one row per transfer,
 * forever. Real payment APIs bound it the same way (Stripe expires keys after 24h). A key
 * older than the window is one no reasonable client is still retrying, so dropping it costs
 * nothing and keeps the unique index small.
 *
 * Disable with ledgerly.idempotency.cleanup.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "ledgerly.idempotency.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ProcessedRequestCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ProcessedRequestCleanupJob.class);

    private final ProcessedRequestRepository processedRequestRepository;
    private final Duration retention;

    public ProcessedRequestCleanupJob(
            ProcessedRequestRepository processedRequestRepository,
            @Value("${ledgerly.idempotency.retention:PT24H}") Duration retention) {
        this.processedRequestRepository = processedRequestRepository;
        this.retention = retention;
    }

    @Scheduled(
            initialDelayString = "${ledgerly.idempotency.cleanup.initial-delay:PT1H}",
            fixedDelayString = "${ledgerly.idempotency.cleanup.interval:PT1H}")
    @Transactional
    public void purgeExpiredRecords() {
        Instant cutoff = Instant.now().minus(retention);
        int deleted = processedRequestRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Purged {} idempotency records older than {}", deleted, cutoff);
        }
    }
}
