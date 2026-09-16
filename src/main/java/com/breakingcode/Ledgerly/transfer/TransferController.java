package com.breakingcode.Ledgerly.transfer;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    // No "/" in the mapping: Spring 6 removed trailing-slash matching, so @PostMapping("/")
    // would make POST /api/v1/transfers return 404.
    @PostMapping
    public ResponseEntity<TransferResult> transfer(@Valid @RequestBody TransferRequest request) {
        return ResponseEntity.ok(transferService.transfer(request));
    }

    @GetMapping
    public ResponseEntity<Page<TransferEventDTO>> getTransfers(
            @PageableDefault(size = 50, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(transferService.getAllTransfers(pageable));
    }

    /** The DEBIT/CREDIT pair that makes up one transfer. */
    @GetMapping("/{transferId}")
    public ResponseEntity<List<TransferEventDTO>> getTransfer(@PathVariable String transferId) {
        return ResponseEntity.ok(transferService.getTransferById(transferId));
    }

    @GetMapping("/processed")
    public ResponseEntity<Page<ProcessedRequestDTO>> getProcessedRequests(
            @PageableDefault(size = 50, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(transferService.getAllProcessedRequests(pageable));
    }
}
