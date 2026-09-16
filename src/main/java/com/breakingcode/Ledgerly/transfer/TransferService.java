package com.breakingcode.Ledgerly.transfer;

import com.breakingcode.Ledgerly.exception.IdempotencyConflictException;
import com.breakingcode.Ledgerly.exception.SelfTransferException;
import com.breakingcode.Ledgerly.exception.TransferNotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orchestrates a transfer: idempotency check, then a retry loop around the transactional
 * unit of work in {@link TransferExecutor}.
 *
 * Deliberately NOT @Transactional. If a transaction were open here, every retry attempt
 * would join it instead of starting fresh, and one failed attempt would mark the whole
 * thing rollback-only, making the retry pointless.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferExecutor executor;
    private final TransferEventRepository transferEventRepository;
    private final ProcessedRequestRepository processedRequestRepository;

    private final int maxAttempts;
    private final long baseBackoffMillis;
    private final long maxBackoffMillis;

    private final Counter retries;
    private final Counter replays;
    private final Counter exhausted;

    public TransferService(TransferExecutor executor,
                           TransferEventRepository transferEventRepository,
                           ProcessedRequestRepository processedRequestRepository,
                           MeterRegistry meterRegistry,
                           @Value("${ledgerly.transfer.max-attempts:10}") int maxAttempts,
                           @Value("${ledgerly.transfer.base-backoff-millis:5}") long baseBackoffMillis,
                           @Value("${ledgerly.transfer.max-backoff-millis:250}") long maxBackoffMillis) {
        this.executor = executor;
        this.transferEventRepository = transferEventRepository;
        this.processedRequestRepository = processedRequestRepository;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMillis = baseBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;

        this.retries = Counter.builder("ledgerly.transfer.retries")
                .description("Optimistic-lock conflicts detected and retried")
                .register(meterRegistry);
        this.replays = Counter.builder("ledgerly.transfer.idempotent.replays")
                .description("Requests answered from the idempotency cache instead of re-executing")
                .register(meterRegistry);
        this.exhausted = Counter.builder("ledgerly.transfer.retries.exhausted")
                .description("Transfers rejected after using their entire retry budget")
                .register(meterRegistry);
    }

    public TransferResult transfer(TransferRequest request) {
        if (request.fromId().equals(request.toId())) {
            throw new SelfTransferException(request.fromId());
        }

        String fingerprint = RequestFingerprint.of(request);

        return processedRequestRepository.findByIdempotencyId(request.idempotencyId())
                .map(processed -> replay(processed, request, fingerprint))
                .orElseGet(() -> attemptWithRetry(request, fingerprint));
    }

    private TransferResult attemptWithRetry(TransferRequest request, String fingerprint) {
        for (int attempt = 1; ; attempt++) {
            try {
                return executor.executeOnce(request);

            } catch (ConcurrencyFailureException e) {
                // Someone else committed against one of these accounts first (optimistic
                // version clash, or a row-lock timeout under load). Nothing of ours was
                // written, so we can simply read fresh state and try again.
                if (attempt >= maxAttempts) {
                    exhausted.increment();
                    log.warn("Transfer {} gave up after {} attempts", request.idempotencyId(), attempt);
                    throw e;
                }
                retries.increment();
                backoff(attempt);

            } catch (DataIntegrityViolationException e) {
                // A concurrent duplicate of this same idempotencyId beat us to the unique
                // index. This is the idempotency guarantee doing its job: the winner's
                // transfer committed, ours rolled back, and we replay their result.
                return processedRequestRepository.findByIdempotencyId(request.idempotencyId())
                        .map(processed -> replay(processed, request, fingerprint))
                        .orElseThrow(() -> e);
            }
        }
    }

    /**
     * Returns the result recorded the first time this key was used -- but only if the request
     * really is the same one. A key reused with different parameters is a client bug, and
     * answering it with an unrelated result would hide that.
     */
    private TransferResult replay(ProcessedRequest processed, TransferRequest request, String fingerprint) {
        if (!processed.getRequestFingerprint().equals(fingerprint)) {
            log.warn("Idempotency key {} reused with different parameters", request.idempotencyId());
            throw new IdempotencyConflictException(request.idempotencyId());
        }
        replays.increment();
        return TransferResult.from(processed);
    }

    /** Exponential backoff with full jitter, so retrying threads spread out instead of colliding again. */
    private void backoff(int attempt) {
        long ceiling = Math.min(baseBackoffMillis * (1L << Math.min(attempt - 1, 20)), maxBackoffMillis);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(ceiling + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off before transfer retry", ie);
        }
    }

    @Transactional(readOnly = true)
    public Page<TransferEventDTO> getAllTransfers(Pageable pageable) {
        return transferEventRepository.findAll(pageable).map(TransferEventDTO::from);
    }

    /** The two events (one DEBIT, one CREDIT) that make up a single transfer. */
    @Transactional(readOnly = true)
    public List<TransferEventDTO> getTransferById(String transferId) {
        List<TransferEvent> events = transferEventRepository.findByTransferIdOrderByIdAsc(transferId);
        if (events.isEmpty()) {
            throw new TransferNotFoundException(transferId);
        }
        return events.stream().map(TransferEventDTO::from).toList();
    }

    @Transactional(readOnly = true)
    public Page<TransferEventDTO> getEventsForAccount(Long accountId, Pageable pageable) {
        return transferEventRepository.findByAccountId(accountId, pageable).map(TransferEventDTO::from);
    }

    @Transactional(readOnly = true)
    public Page<ProcessedRequestDTO> getAllProcessedRequests(Pageable pageable) {
        return processedRequestRepository.findAll(pageable).map(ProcessedRequestDTO::from);
    }

    public long getRetryCount() {
        return (long) retries.count();
    }

    public long getIdempotentReplayCount() {
        return (long) replays.count();
    }

    public long getExhaustedCount() {
        return (long) exhausted.count();
    }
}
