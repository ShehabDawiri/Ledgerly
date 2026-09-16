package com.breakingcode.Ledgerly.account;

import com.breakingcode.Ledgerly.exception.InsufficientFundsException;
import com.breakingcode.Ledgerly.exception.InvalidAmountException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "account")
@ToString
@Getter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    // Optimistic concurrency control: Hibernate appends "where version = ?" to every
    // update and bumps it. Two transfers touching this row concurrently means one
    // commit wins and the other fails with OptimisticLockingFailureException, which
    // TransferService retries against freshly read state. No lost updates.
    @Version
    private Long version;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public Account(String name, BigDecimal balance) {
        this.name = name;
        this.balance = balance;
    }

    public void debit(BigDecimal amount) {
        requirePositive(amount);

        if (this.balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(this.id, this.balance, amount);
        }

        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        requirePositive(amount);
        this.balance = this.balance.add(amount);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException(amount);
        }
    }
}
