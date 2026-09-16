package com.breakingcode.Ledgerly;

import com.breakingcode.Ledgerly.account.AccountRepository;
import com.breakingcode.Ledgerly.account.AccountService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Seeds demo accounts on an empty database so the API is usable immediately after startup.
 * Goes through AccountService.openAccount so seeded accounts get the same OPENING events as
 * any other account -- seeding straight into the table would leave balances that reconciliation
 * could not explain.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final AccountRepository accountRepository;
    private final AccountService accountService;

    public DataSeeder(AccountRepository accountRepository, AccountService accountService) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
    }

    @Override
    public void run(String... args) {
        if (accountRepository.count() > 0) {
            return;
        }
        accountService.openAccount("Alice", new BigDecimal("1000.00"));
        accountService.openAccount("Bob", new BigDecimal("500.00"));
        accountService.openAccount("Carol", new BigDecimal("750.00"));
        accountService.openAccount("Dave", new BigDecimal("250.00"));
    }
}
