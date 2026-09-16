package com.breakingcode.Ledgerly.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private static TransferRequest request(long from, long to, String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount), "key");
    }

    @Test
    void isStableForTheSameParameters() {
        assertThat(RequestFingerprint.of(request(1, 2, "100.00")))
                .isEqualTo(RequestFingerprint.of(request(1, 2, "100.00")));
    }

    @Test
    @DisplayName("ignores trailing-zero differences: 100, 100.0 and 100.0000 are one amount")
    void ignoresScale() {
        String canonical = RequestFingerprint.of(request(1, 2, "100"));
        assertThat(RequestFingerprint.of(request(1, 2, "100.0"))).isEqualTo(canonical);
        assertThat(RequestFingerprint.of(request(1, 2, "100.0000"))).isEqualTo(canonical);
    }

    @Test
    void differsWhenTheAmountDiffers() {
        assertThat(RequestFingerprint.of(request(1, 2, "100.00")))
                .isNotEqualTo(RequestFingerprint.of(request(1, 2, "100.01")));
    }

    @Test
    void differsWhenTheAccountsDiffer() {
        String base = RequestFingerprint.of(request(1, 2, "100.00"));
        assertThat(RequestFingerprint.of(request(3, 2, "100.00"))).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(request(1, 3, "100.00"))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("direction matters: 1->2 is not the same transfer as 2->1")
    void isDirectional() {
        assertThat(RequestFingerprint.of(request(1, 2, "100.00")))
                .isNotEqualTo(RequestFingerprint.of(request(2, 1, "100.00")));
    }

    @Test
    @DisplayName("field boundaries cannot be confused by ids that concatenate the same way")
    void isNotAmbiguousAcrossFieldBoundaries() {
        // 1|12 and 11|2 must not collide
        assertThat(RequestFingerprint.of(request(1, 12, "5")))
                .isNotEqualTo(RequestFingerprint.of(request(11, 2, "5")));
    }

    @Test
    void ignoresTheIdempotencyKeyItself() {
        TransferRequest a = new TransferRequest(1L, 2L, new BigDecimal("10.00"), "key-a");
        TransferRequest b = new TransferRequest(1L, 2L, new BigDecimal("10.00"), "key-b");
        assertThat(RequestFingerprint.of(a)).isEqualTo(RequestFingerprint.of(b));
    }

    @Test
    void producesAHexSha256() {
        assertThat(RequestFingerprint.of(request(1, 2, "100.00")))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }
}
