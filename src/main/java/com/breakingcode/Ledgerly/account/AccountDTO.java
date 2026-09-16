package com.breakingcode.Ledgerly.account;

import java.math.BigDecimal;

public record AccountDTO(
        Long id,
        String name,
        BigDecimal balance,
        Long version
) {
    public static AccountDTO from(Account account) {
        return new AccountDTO(
                account.getId(),
                account.getName(),
                account.getBalance(),
                account.getVersion());
    }
}
