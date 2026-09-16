package com.breakingcode.Ledgerly.transfer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A stable hash of the parameters that define a transfer.
 *
 * Stored alongside the idempotency key so a key reused with *different* parameters can be
 * rejected rather than silently answered with the original result. Without this, sending
 * {amount: 100, key: "abc"} and then {amount: 500, key: "abc"} returns the 100 result with
 * a 200, hiding what is almost always a client bug.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(TransferRequest request) {
        // stripTrailingZeros so "100", "100.0" and "100.0000" are treated as the same
        // transfer -- they are the same amount, and JSON clients are inconsistent about scale.
        String canonical = request.fromId()
                + "|" + request.toId()
                + "|" + normalize(request.amount());
        return sha256(canonical);
    }

    private static String normalize(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM, so this is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
