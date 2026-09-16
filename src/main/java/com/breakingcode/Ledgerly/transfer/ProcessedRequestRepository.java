package com.breakingcode.Ledgerly.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface ProcessedRequestRepository extends JpaRepository<ProcessedRequest, Long> {

    Optional<ProcessedRequest> findByIdempotencyId(String idempotencyId);

    /**
     * Drops idempotency records past their retention window. Real payment APIs expire these
     * (Stripe uses 24h); without it the table grows without bound, and a key old enough to
     * have expired is one no sane client is still retrying.
     */
    @Modifying
    @Query("delete from ProcessedRequest p where p.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
