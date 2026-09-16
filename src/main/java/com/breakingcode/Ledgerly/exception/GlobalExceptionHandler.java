package com.breakingcode.Ledgerly.exception;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT) // 409
                .body(new ErrorResponse("INSUFFICIENT_FUNDS", e.getMessage()));
    }

    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAmount(InvalidAmountException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST) // 400
                .body(new ErrorResponse("INVALID_AMOUNT", e.getMessage()));
    }

    @ExceptionHandler(SelfTransferException.class)
    public ResponseEntity<ErrorResponse> handleSelfTransfer(SelfTransferException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST) // 400
                .body(new ErrorResponse("SELF_TRANSFER", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
        // The key is known but the parameters differ -- answering with the original result
        // would silently swallow a client bug.
        return ResponseEntity.status(HttpStatus.CONFLICT) // 409
                .body(new ErrorResponse("IDEMPOTENCY_KEY_REUSED", e.getMessage()));
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransferNotFound(TransferNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND) // 404
                .body(new ErrorResponse("TRANSFER_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND) // 404
                .body(new ErrorResponse("ACCOUNT_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrencyConflict(ConcurrencyFailureException e) {
        // Reached only when every retry attempt lost its optimistic-lock race.
        // Nothing was written -- the client is safe to retry with the same idempotencyId.
        return ResponseEntity.status(HttpStatus.CONFLICT) // 409
                .body(new ErrorResponse("CONCURRENT_MODIFICATION", "Too many concurrent updates, please retry"));
    }

    /** @Valid failures, so a bad request body gets our ErrorResponse shape rather than Spring's default. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST) // 400
                .body(new ErrorResponse("VALIDATION_FAILED", details));
    }

    /** Catch-all for any future LedgerException so a new subtype can never leak a raw 500. */
    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ErrorResponse> handleLedger(LedgerException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST) // 400
                .body(new ErrorResponse("LEDGER_ERROR", e.getMessage()));
    }
}
