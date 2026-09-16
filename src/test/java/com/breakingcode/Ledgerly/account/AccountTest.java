package com.breakingcode.Ledgerly.account;

import com.breakingcode.Ledgerly.exception.InsufficientFundsException;
import com.breakingcode.Ledgerly.exception.InvalidAmountException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two invariants the whole ledger rests on live on this entity rather than in a service,
 * so they cannot be bypassed by a future caller. These are plain unit tests -- no Spring, no
 * database -- because the rules are pure domain logic.
 */
class AccountTest {

    private static Account accountWith(String balance) {
        return new Account("Test", new BigDecimal(balance));
    }

    @Nested
    @DisplayName("debit")
    class Debit {

        @Test
        void reducesTheBalance() {
            Account account = accountWith("100.00");
            account.debit(new BigDecimal("30.00"));
            assertThat(account.getBalance()).isEqualByComparingTo("70.00");
        }

        @Test
        @DisplayName("allows spending the balance down to exactly zero")
        void allowsExactBalance() {
            Account account = accountWith("100.00");
            account.debit(new BigDecimal("100.00"));
            assertThat(account.getBalance()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("rejects one cent more than the balance")
        void rejectsOverdraft() {
            Account account = accountWith("100.00");

            assertThatThrownBy(() -> account.debit(new BigDecimal("100.01")))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(account.getBalance())
                    .as("a rejected debit must leave the balance untouched")
                    .isEqualByComparingTo("100.00");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.00", "-0.01", "-100"})
        void rejectsNonPositiveAmounts(String amount) {
            Account account = accountWith("100.00");

            assertThatThrownBy(() -> account.debit(new BigDecimal(amount)))
                    .isInstanceOf(InvalidAmountException.class);

            assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        }

        @Test
        void rejectsNull() {
            Account account = accountWith("100.00");
            assertThatThrownBy(() -> account.debit(null))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        @DisplayName("checks the amount before the balance, so a negative amount cannot be an overdraft workaround")
        void validatesAmountBeforeBalance() {
            Account account = accountWith("0.00");
            assertThatThrownBy(() -> account.debit(new BigDecimal("-50.00")))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("credit")
    class Credit {

        @Test
        void increasesTheBalance() {
            Account account = accountWith("100.00");
            account.credit(new BigDecimal("30.00"));
            assertThat(account.getBalance()).isEqualByComparingTo("130.00");
        }

        @Test
        void preservesScaleBeyondTwoDecimalPlaces() {
            Account account = accountWith("100.0000");
            account.credit(new BigDecimal("0.0001"));
            assertThat(account.getBalance()).isEqualByComparingTo("100.0001");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.00", "-0.01"})
        void rejectsNonPositiveAmounts(String amount) {
            Account account = accountWith("100.00");

            assertThatThrownBy(() -> account.credit(new BigDecimal(amount)))
                    .isInstanceOf(InvalidAmountException.class);

            assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        }

        @Test
        void rejectsNull() {
            Account account = accountWith("100.00");
            assertThatThrownBy(() -> account.credit(null))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Test
    @DisplayName("a debit and a matching credit cancel out exactly")
    void debitAndCreditAreSymmetric() {
        Account from = accountWith("100.00");
        Account to = accountWith("0.00");
        BigDecimal amount = new BigDecimal("33.33");

        from.debit(amount);
        to.credit(amount);

        assertThat(from.getBalance().add(to.getBalance()))
                .as("no money created or destroyed")
                .isEqualByComparingTo("100.00");
    }
}
