package com.breakingcode.Ledgerly.reconcile;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reconcile")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /** 200 when the ledger balances, 409 when it does not -- so a CI check can just assert the status. */
    @GetMapping
    public ResponseEntity<ReconciliationReport> reconcile() {
        ReconciliationReport report = reconciliationService.reconcile();
        return ResponseEntity
                .status(report.balanced() ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(report);
    }
}
