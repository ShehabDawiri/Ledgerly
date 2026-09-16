package com.breakingcode.Ledgerly.transfer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface TransferEventRepository extends JpaRepository<TransferEvent, Long> {

    /** A projection of one account's balance as derived purely from its event history. */
    interface DerivedBalance {
        Long getAccountId();
        BigDecimal getAmount();
    }

    /**
     * Folds the entire event log down to one signed balance per account, in SQL.
     * Doing this as an aggregate rather than loading every event into the JVM is what
     * keeps /reconcile usable once the log has millions of rows.
     */
    @Query("""
            select e.accountId as accountId,
                   sum(case when e.eventType = :debit then -e.amount else e.amount end) as amount
            from TransferEvent e
            group by e.accountId
            """)
    List<DerivedBalance> deriveBalances(@Param("debit") EventType debit);

    /** Total of every event of a given type -- used to prove debits and credits offset exactly. */
    @Query("select coalesce(sum(e.amount), 0) from TransferEvent e where e.eventType = :type")
    BigDecimal sumAmountByType(@Param("type") EventType type);

    long countByEventType(EventType eventType);

    List<TransferEvent> findByTransferIdOrderByIdAsc(String transferId);

    Page<TransferEvent> findByAccountId(Long accountId, Pageable pageable);
}
