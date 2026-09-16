package com.breakingcode.Ledgerly.transfer;

import com.breakingcode.Ledgerly.account.OpeningBalanceRecorder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/** Writes opening balances into the event log. See {@link OpeningBalanceRecorder}. */
@Component
class TransferEventOpeningBalanceRecorder implements OpeningBalanceRecorder {

    private final TransferEventRepository transferEventRepository;

    TransferEventOpeningBalanceRecorder(TransferEventRepository transferEventRepository) {
        this.transferEventRepository = transferEventRepository;
    }

    @Override
    public void recordOpeningBalance(Long accountId, BigDecimal openingBalance) {
        transferEventRepository.save(new TransferEvent(
                UUID.randomUUID().toString(), openingBalance, accountId, EventType.OPENING));
    }
}
