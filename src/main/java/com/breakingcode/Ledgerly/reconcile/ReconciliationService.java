package com.breakingcode.Ledgerly.reconcile;

import com.breakingcode.Ledgerly.account.Account;
import com.breakingcode.Ledgerly.account.AccountRepository;
import com.breakingcode.Ledgerly.transfer.EventType;
import com.breakingcode.Ledgerly.transfer.TransferEventRepository;
import com.breakingcode.Ledgerly.transfer.TransferService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReconciliationService {

    private static final int ACCOUNT_PAGE_SIZE = 500;

    private final AccountRepository accountRepository;
    private final TransferEventRepository transferEventRepository;
    private final TransferService transferService;

    public ReconciliationService(AccountRepository accountRepository,
                                 TransferEventRepository transferEventRepository,
                                 TransferService transferService) {
        this.accountRepository = accountRepository;
        this.transferEventRepository = transferEventRepository;
        this.transferService = transferService;
    }

    /**
     * Recomputes every balance from the event log and compares it to what's stored.
     *
     * Because opening balances are themselves OPENING events, the derived balance is a
     * pure fold over the log -- no external "initial balance" input is needed for this
     * to be a complete proof.
     *
     * readOnly so the whole report is computed against one consistent snapshot rather
     * than racing in-flight transfers mid-scan.
     */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        Map<Long, BigDecimal> derived = transferEventRepository.deriveBalances(EventType.DEBIT).stream()
                .collect(Collectors.toMap(
                        TransferEventRepository.DerivedBalance::getAccountId,
                        TransferEventRepository.DerivedBalance::getAmount,
                        (a, b) -> a,
                        HashMap::new));

        List<AccountReconciliation> accounts = new ArrayList<>();
        BigDecimal storedTotal = BigDecimal.ZERO;
        BigDecimal derivedTotal = BigDecimal.ZERO;

        // Paged rather than findAll(): this endpoint walks every account in the system, and
        // loading them all into one list stops working long before the event fold does.
        Pageable pageRequest = PageRequest.of(0, ACCOUNT_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id"));
        Page<Account> page;
        do {
            page = accountRepository.findAll(pageRequest);
            for (Account account : page.getContent()) {
                BigDecimal stored = account.getBalance();
                BigDecimal fromEvents = derived.getOrDefault(account.getId(), BigDecimal.ZERO);
                BigDecimal difference = stored.subtract(fromEvents);

                // compareTo, not equals: BigDecimal.equals is scale-sensitive, so 100.00
                // and 100.0000 would compare unequal and every account would look broken.
                boolean matches = difference.compareTo(BigDecimal.ZERO) == 0;

                accounts.add(new AccountReconciliation(
                        account.getId(), account.getName(), stored, fromEvents, difference, matches));

                storedTotal = storedTotal.add(stored);
                derivedTotal = derivedTotal.add(fromEvents);
            }
            pageRequest = pageRequest.next();
        } while (page.hasNext());

        BigDecimal opening = transferEventRepository.sumAmountByType(EventType.OPENING);
        BigDecimal debited = transferEventRepository.sumAmountByType(EventType.DEBIT);
        BigDecimal credited = transferEventRepository.sumAmountByType(EventType.CREDIT);

        // Every transfer writes exactly one DEBIT and one matching CREDIT, so across the
        // whole ledger they must cancel: money only ever moved, it was never created.
        boolean conserved = debited.compareTo(credited) == 0
                && storedTotal.compareTo(opening) == 0;

        List<AccountReconciliation> discrepancies = accounts.stream()
                .filter(a -> !a.matches())
                .toList();

        return new ReconciliationReport(
                discrepancies.isEmpty() && conserved,
                accounts.size(),
                transferEventRepository.count(),
                transferEventRepository.countByEventType(EventType.DEBIT),
                storedTotal,
                derivedTotal,
                opening,
                debited,
                credited,
                conserved,
                transferService.getRetryCount(),
                transferService.getExhaustedCount(),
                transferService.getIdempotentReplayCount(),
                accounts,
                discrepancies);
    }
}
