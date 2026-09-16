package com.breakingcode.Ledgerly;

import com.breakingcode.Ledgerly.account.AccountDTO;
import com.breakingcode.Ledgerly.account.CreateAccountRequest;
import com.breakingcode.Ledgerly.reconcile.AccountReconciliation;
import com.breakingcode.Ledgerly.reconcile.ReconciliationReport;
import com.breakingcode.Ledgerly.transfer.TransferRequest;
import com.breakingcode.Ledgerly.transfer.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the API surface, including the cases that prove the safety
 * mechanisms actually fire rather than being decorative.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class LedgerApiTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_ARRAY =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ accounts

    @Test
    @DisplayName("Opening an account records its balance in the event log and keeps the ledger balanced")
    void openAccountWritesAnOpeningEvent() {
        ResponseEntity<AccountDTO> created = rest.postForEntity("/api/v1/accounts",
                new CreateAccountRequest("Eve " + UUID.randomUUID(), new BigDecimal("640.00")),
                AccountDTO.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();

        AccountDTO account = created.getBody();
        assertThat(account).isNotNull();
        assertThat(account.balance()).isEqualByComparingTo("640.00");

        // The opening balance must exist as an event, otherwise reconciliation could never
        // explain where this account's money came from.
        ResponseEntity<Map<String, Object>> events = rest.exchange(
                "/api/v1/accounts/" + account.id() + "/events",
                org.springframework.http.HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) events.getBody().get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("eventType")).isEqualTo("OPENING");
        assertThat(new BigDecimal(String.valueOf(content.get(0).get("amount")))).isEqualByComparingTo("640.00");

        assertThat(reconcile().balanced())
                .as("a newly opened account must not break reconciliation")
                .isTrue();
    }

    @Test
    @DisplayName("An account may open with a zero balance")
    void openAccountAllowsZeroOpeningBalance() {
        ResponseEntity<AccountDTO> created = rest.postForEntity("/api/v1/accounts",
                new CreateAccountRequest("Empty " + UUID.randomUUID(), BigDecimal.ZERO),
                AccountDTO.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().balance()).isEqualByComparingTo("0");
        assertThat(reconcile().balanced())
                .as("a zero-balance account must still reconcile")
                .isTrue();
    }

    @Test
    void openAccountRejectsANegativeOpeningBalance() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/accounts",
                new CreateAccountRequest("Bad", new BigDecimal("-1.00")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_FAILED");
    }

    @Test
    void eventsForAnUnknownAccountAre404() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/accounts/999999/events", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("ACCOUNT_NOT_FOUND");
    }

    // ------------------------------------------------------------------ transfers

    @Test
    @DisplayName("A transfer is retrievable as its DEBIT/CREDIT pair")
    void transferByIdReturnsBothLegs() {
        List<Long> ids = accountIds();
        TransferResult result = transfer(ids.get(0), ids.get(1), "40.00", "byid-" + UUID.randomUUID());

        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                "/api/v1/transfers/" + result.transferId(),
                org.springframework.http.HttpMethod.GET, null, JSON_ARRAY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> legs = response.getBody();
        assertThat(legs).hasSize(2);
        assertThat(legs).extracting(e -> e.get("eventType"))
                .containsExactlyInAnyOrder("DEBIT", "CREDIT");
        assertThat(legs).allSatisfy(leg ->
                assertThat(new BigDecimal(String.valueOf(leg.get("amount")))).isEqualByComparingTo("40.00"));
    }

    @Test
    void unknownTransferIdIs404() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/transfers/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("TRANSFER_NOT_FOUND");
    }

    @Test
    @DisplayName("Reusing an idempotency key with different parameters is rejected, not silently replayed")
    void idempotencyKeyReuseWithDifferentParametersIsRejected() {
        List<Long> ids = accountIds();
        String key = "reuse-" + UUID.randomUUID();

        TransferResult first = transfer(ids.get(0), ids.get(1), "10.00", key);
        assertThat(first).isNotNull();

        // Same key, different amount. Answering this with the 10.00 result would hide a client bug.
        ResponseEntity<String> second = rest.postForEntity("/api/v1/transfers",
                new TransferRequest(ids.get(0), ids.get(1), new BigDecimal("500.00"), key), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("IDEMPOTENCY_KEY_REUSED");

        // ...and the original transfer is still replayable with its original parameters.
        TransferResult replayed = transfer(ids.get(0), ids.get(1), "10.00", key);
        assertThat(replayed.transferId()).isEqualTo(first.transferId());
    }

    // ------------------------------------------------------------------ reconciliation

    @Test
    @DisplayName("Reconciliation detects a balance that disagrees with the event log")
    void reconcileDetectsACorruptedBalance() {
        List<Long> ids = accountIds();
        Long victim = ids.get(0);

        assertThat(reconcile().balanced())
                .as("precondition: the ledger starts balanced")
                .isTrue();

        // Corrupt the projection directly in SQL, bypassing the domain and the event log --
        // exactly the drift reconciliation exists to catch. Without this test, /reconcile has
        // only ever been observed passing and proves nothing.
        BigDecimal original = jdbc.queryForObject(
                "select balance from account where id = ?", BigDecimal.class, victim);
        jdbc.update("update account set balance = balance + 1 where id = ?", victim);

        try {
            ResponseEntity<ReconciliationReport> response =
                    rest.getForEntity("/api/v1/reconcile", ReconciliationReport.class);

            assertThat(response.getStatusCode())
                    .as("an unbalanced ledger must report 409, not 200")
                    .isEqualTo(HttpStatus.CONFLICT);

            ReconciliationReport report = response.getBody();
            assertThat(report).isNotNull();
            assertThat(report.balanced()).isFalse();
            assertThat(report.conserved()).isFalse();
            assertThat(report.discrepancies())
                    .extracting(AccountReconciliation::accountId)
                    .containsExactly(victim);
            assertThat(report.discrepancies().get(0).difference()).isEqualByComparingTo("1");

        } finally {
            jdbc.update("update account set balance = ? where id = ?", original, victim);
        }

        assertThat(reconcile().balanced())
                .as("and it reports healthy again once the drift is corrected")
                .isTrue();
    }

    // ------------------------------------------------------------------ actuator

    @Test
    void healthEndpointIsUp() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/actuator/health", org.springframework.http.HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("UP");
    }

    // ------------------------------------------------------------------ helpers

    private TransferResult transfer(Long from, Long to, String amount, String key) {
        ResponseEntity<TransferResult> response = rest.postForEntity("/api/v1/transfers",
                new TransferRequest(from, to, new BigDecimal(amount), key), TransferResult.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
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
