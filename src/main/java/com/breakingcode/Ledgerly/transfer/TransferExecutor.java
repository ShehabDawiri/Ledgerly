package com.breakingcode.Ledgerly.transfer;

import com.breakingcode.Ledgerly.account.Account;
import com.breakingcode.Ledgerly.account.AccountRepository;
import com.breakingcode.Ledgerly.exception.AccountNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * One transfer attempt = one transaction.
 *
 * This lives in its own bean on purpose. Spring's @Transactional works by proxying
 * the bean, so a call from another method of the same class bypasses it entirely.
 * The retry loop in TransferService must sit OUTSIDE this proxy, because an
 * optimistic-lock failure surfaces at commit -- which happens in the transaction
 * interceptor, after executeOnce() has already returned. A try/catch placed inside
 * this method would never see it.
 */
@Service
public class TransferExecutor {

    private final AccountRepository accountRepository;
    private final TransferEventRepository transferEventRepository;
    private final ProcessedRequestRepository processedRequestRepository;

    public TransferExecutor(AccountRepository accountRepository,
                            TransferEventRepository transferEventRepository,
                            ProcessedRequestRepository processedRequestRepository) {
        this.accountRepository = accountRepository;
        this.transferEventRepository = transferEventRepository;
        this.processedRequestRepository = processedRequestRepository;
    }

    /**
     * Accounts are re-read here, inside the transaction, on every single attempt.
     * Passing entities in from the caller would mean a retry re-applies debit() to an
     * already-mutated object and computes a balance from stale state.
     *
     * The debit, the credit, both ledger events and the idempotency record all commit
     * or roll back together. That atomicity is what makes the event log trustworthy.
     */
    @Transactional
    public TransferResult executeOnce(TransferRequest request) {
        Account from = accountRepository.findById(request.fromId())
                .orElseThrow(() -> new AccountNotFoundException(request.fromId()));
        Account to = accountRepository.findById(request.toId())
                .orElseThrow(() -> new AccountNotFoundException(request.toId()));

        // debit() enforces both the positive-amount and sufficient-funds invariants
        from.debit(request.amount());
        to.credit(request.amount());
        // no explicit save(): both are managed entities, dirty checking flushes them

        String transferId = UUID.randomUUID().toString();
        transferEventRepository.save(
                new TransferEvent(transferId, request.amount(), from.getId(), EventType.DEBIT));
        transferEventRepository.save(
                new TransferEvent(transferId, request.amount(), to.getId(), EventType.CREDIT));

        TransferResult result = new TransferResult(
                true, transferId, from.getBalance(), to.getBalance());

        // Written in the same transaction as the money movement. If this were saved
        // afterwards, a crash in between would leave a committed transfer with no
        // idempotency record -- and the client's retry would move the money twice.
        processedRequestRepository.save(new ProcessedRequest(
                request.idempotencyId(), RequestFingerprint.of(request), result));

        return result;
    }
}
