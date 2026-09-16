package com.breakingcode.Ledgerly;

import com.breakingcode.Ledgerly.reconcile.ReconciliationReport;
import com.breakingcode.Ledgerly.transfer.TransferRequest;
import com.breakingcode.Ledgerly.transfer.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline proof for this project.
 *
 * Fires hundreds of concurrent transfers at a small pool of accounts -- guaranteeing
 * heavy contention on the same rows -- with a slice of them deliberately duplicated to
 * exercise idempotency, then asserts via /reconcile that the ledger still balances.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ConcurrentTransferStressTest {

    private static final int CONCURRENT_REQUESTS = 200;
    private static final int THREADS = 32;
    private static final BigDecimal AMOUNT = new BigDecimal("1.00");

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("200 concurrent transfers over contended accounts leave the ledger balanced")
    void concurrentTransfersPreserveTheLedger() throws Exception {
        List<Long> accountIds = accountIds();
        assertThat(accountIds).hasSizeGreaterThanOrEqualTo(2);

        ReconciliationReport before = reconcile();
        BigDecimal totalBefore = before.storedTotal();
        long transfersBefore = before.transfersRecorded();
        long replaysBefore = before.idempotentReplays();

        // Every 10th request is an exact copy of the one before it -- same accounts, same
        // amount, same idempotencyId -- so the identical logical transfer is genuinely in
        // flight twice at once. (Reusing only the key with different parameters is a
        // different scenario entirely, and is rejected; see LedgerApiTest.)
        List<TransferRequest> requests = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            if (i > 0 && i % 10 == 0) {
                requests.add(requests.get(i - 1));
            } else {
                Long from = accountIds.get(i % accountIds.size());
                Long to = accountIds.get((i + 1) % accountIds.size());
                requests.add(new TransferRequest(from, to, AMOUNT, "stress-" + i));
            }
        }
        long duplicates = CONCURRENT_REQUESTS - requests.stream()
                .map(TransferRequest::idempotencyId).distinct().count();
        assertThat(duplicates).as("the run must actually contain duplicate requests").isPositive();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> rejectionCodes = ConcurrentHashMap.newKeySet();

        List<Future<TransferResult>> futures = requests.stream()
                .map(request -> pool.submit(() -> {
                    startGun.await();
                    // Bind to Map, not TransferResult: TestRestTemplate deliberately does not
                    // throw on 4xx, so a rejected transfer would otherwise try to deserialize
                    // an ErrorResponse body into a TransferResult and blow up the worker.
                    ResponseEntity<Map<String, Object>> response = rest.exchange(
                            RequestEntity.post("/api/v1/transfers").body(request), JSON_OBJECT);
                    if (response.getStatusCode() == HttpStatus.OK) {
                        accepted.incrementAndGet();
                        return toResult(response.getBody());
                    }
                    rejected.incrementAndGet();
                    Object code = response.getBody() == null ? null : response.getBody().get("errorCode");
                    rejectionCodes.add(String.valueOf(code));
                    return null;
                }))
                .toList();

        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(180, TimeUnit.SECONDS)).isTrue();

        List<TransferResult> results = new ArrayList<>();
        for (Future<TransferResult> future : futures) {
            TransferResult result = future.get();
            if (result != null) {
                results.add(result);
            }
        }

        ReconciliationReport report = reconcile();

        System.out.printf("accepted=%d rejected=%d retriesAbsorbed=%d idempotentReplays=%d rejectionCodes=%s%n",
                accepted.get(), rejected.get(), report.retriesAbsorbed(),
                report.idempotentReplays() - replaysBefore, rejectionCodes);

        // 1. Event sourcing: every stored balance is reproducible from the log alone.
        assertThat(report.discrepancies())
                .as("accounts whose stored balance disagrees with their event history")
                .isEmpty();

        // 2. Conservation: money only moved. None was created or destroyed.
        assertThat(report.totalDebited()).isEqualByComparingTo(report.totalCredited());
        assertThat(report.storedTotal()).isEqualByComparingTo(totalBefore);
        assertThat(report.balanced()).isTrue();

        // 3. Idempotency: the duplicates really were replayed, not re-executed.
        //    Without this the rest of the idempotency assertions would hold vacuously.
        assertThat(report.idempotentReplays() - replaysBefore)
                .as("duplicate requests must have been served from the idempotency cache")
                .isPositive();
        assertThat(rejectionCodes)
                .as("exact duplicates must never be rejected as key reuse")
                .doesNotContain("IDEMPOTENCY_KEY_REUSED");

        long distinctKeysAttempted = requests.stream()
                .map(TransferRequest::idempotencyId).distinct().count();
        long distinctTransferIds = results.stream()
                .map(TransferResult::transferId).distinct().count();
        assertThat(distinctTransferIds).isLessThanOrEqualTo(distinctKeysAttempted);
        assertThat(report.transfersRecorded() - transfersBefore).isEqualTo(distinctTransferIds);

        // ...and every duplicate of a key got an identical result back.
        Map<String, List<TransferResult>> byTransferId = results.stream()
                .collect(Collectors.groupingBy(TransferResult::transferId));
        assertThat(byTransferId.values()).allSatisfy(group ->
                assertThat(group.stream().distinct().count())
                        .as("all responses sharing a transferId must be identical")
                        .isEqualTo(1L));
    }

    @Test
    @DisplayName("Replaying an idempotencyId returns the original result without moving money again")
    void retryingTheSameRequestIsANoOp() {
        List<Long> accountIds = accountIds();
        String key = "replay-" + UUID.randomUUID();
        TransferRequest request =
                new TransferRequest(accountIds.get(0), accountIds.get(1), new BigDecimal("25.00"), key);

        TransferResult first = rest.postForEntity("/api/v1/transfers", request, TransferResult.class).getBody();
        long eventsAfterFirst = reconcile().totalEvents();

        TransferResult second = rest.postForEntity("/api/v1/transfers", request, TransferResult.class).getBody();
        long eventsAfterSecond = reconcile().totalEvents();

        assertThat(second).isEqualTo(first);
        assertThat(eventsAfterSecond)
                .as("a replayed request must not append anything to the ledger")
                .isEqualTo(eventsAfterFirst);
    }

    @Test
    @DisplayName("Transferring to the same account is rejected")
    void selfTransferIsRejected() {
        Long id = accountIds().get(0);
        ResponseEntity<String> response = rest.postForEntity("/api/v1/transfers",
                new TransferRequest(id, id, new BigDecimal("5.00"), "self-" + UUID.randomUUID()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("SELF_TRANSFER");
    }

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private static TransferResult toResult(Map<String, Object> body) {
        return new TransferResult(
                Boolean.TRUE.equals(body.get("success")),
                (String) body.get("transferId"),
                new BigDecimal(String.valueOf(body.get("fromBalance"))),
                new BigDecimal(String.valueOf(body.get("toBalance"))));
    }

    @SuppressWarnings("unchecked")
    private List<Long> accountIds() {
        Map<String, Object> page = rest.getForObject("/api/v1/accounts", Map.class);
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        return content.stream().map(a -> ((Number) a.get("id")).longValue()).toList();
    }

    private ReconciliationReport reconcile() {
        return rest.getForEntity("/api/v1/reconcile", ReconciliationReport.class).getBody();
    }
}
