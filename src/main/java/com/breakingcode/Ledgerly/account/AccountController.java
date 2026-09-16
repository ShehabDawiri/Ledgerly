package com.breakingcode.Ledgerly.account;

import com.breakingcode.Ledgerly.transfer.TransferEventDTO;
import com.breakingcode.Ledgerly.transfer.TransferService;
import com.breakingcode.Ledgerly.exception.AccountNotFoundException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final TransferService transferService;

    public AccountController(AccountService accountService, TransferService transferService) {
        this.accountService = accountService;
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<AccountDTO> openAccount(@Valid @RequestBody CreateAccountRequest request,
                                                  UriComponentsBuilder uriBuilder) {
        Account account = accountService.openAccount(request.name(), request.openingBalance());
        return ResponseEntity
                .created(uriBuilder.path("/api/v1/accounts/{id}").build(account.getId()))
                .body(AccountDTO.from(account));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountDTO> getAccountInfo(@PathVariable Long id) {
        return ResponseEntity.ok(AccountDTO.from(accountService.getAccountInfo(id)));
    }

    @GetMapping
    public ResponseEntity<Page<AccountDTO>> getAccounts(
            @PageableDefault(size = 50, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(accountService.getAccounts(pageable));
    }

    /** This account's slice of the event log -- the history its balance is derived from. */
    @GetMapping("/{id}/events")
    public ResponseEntity<Page<TransferEventDTO>> getAccountEvents(
            @PathVariable Long id,
            @PageableDefault(size = 50, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        // Check first, so an unknown account 404s instead of returning a misleading empty page.
        if (!accountService.exists(id)) {
            throw new AccountNotFoundException(id);
        }
        return ResponseEntity.ok(transferService.getEventsForAccount(id, pageable));
    }
}
