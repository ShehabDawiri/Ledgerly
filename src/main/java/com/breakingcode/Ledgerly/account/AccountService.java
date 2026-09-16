package com.breakingcode.Ledgerly.account;

import com.breakingcode.Ledgerly.exception.AccountNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final OpeningBalanceRecorder openingBalanceRecorder;

    public AccountService(AccountRepository accountRepository,
                          OpeningBalanceRecorder openingBalanceRecorder) {
        this.accountRepository = accountRepository;
        this.openingBalanceRecorder = openingBalanceRecorder;
    }

    /**
     * Creates an account and records its opening balance as a ledger event, atomically.
     *
     * Both halves must commit together: an account whose starting balance never reached the
     * event log would fail reconciliation forever, because a balance is derived purely by
     * folding that log.
     */
    @Transactional
    public Account openAccount(String name, BigDecimal openingBalance) {
        Account account = accountRepository.save(new Account(name, openingBalance));
        openingBalanceRecorder.recordOpeningBalance(account.getId(), openingBalance);
        return account;
    }

    @Transactional(readOnly = true)
    public Account getAccountInfo(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<AccountDTO> getAccounts(Pageable pageable) {
        return accountRepository.findAll(pageable).map(AccountDTO::from);
    }

    @Transactional(readOnly = true)
    public boolean exists(Long id) {
        return accountRepository.existsById(id);
    }
}
