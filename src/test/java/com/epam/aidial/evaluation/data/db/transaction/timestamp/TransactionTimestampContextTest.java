package com.epam.aidial.evaluation.data.db.transaction.timestamp;

import static com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext.TRANSACTION_TIMESTAMP_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("TransactionTimestampContext")
class TransactionTimestampContextTest {

    private static final long FIXED_MS = 1_700_000_000_000L;

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(FIXED_MS), ZoneOffset.UTC);
    private final TransactionTimestampContext context = new TransactionTimestampContext(clock);

    @BeforeEach
    void openTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void closeTransaction() {
        TransactionSynchronizationManager.unbindResourceIfPossible(TRANSACTION_TIMESTAMP_KEY);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("getTimestamp throws when no active transaction")
    void getTimestampThrowsWhenNoTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(context::getTimestamp)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active transaction");
    }

    @Test
    @DisplayName("getTimestamp throws when timestamp not initialized even if transaction active")
    void getTimestampThrowsWhenResourceAbsent() {
        assertThatThrownBy(context::getTimestamp)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timestamp not initialized");
    }

    @Test
    @DisplayName("initializeIfAbsent binds clock-based timestamp as a transaction resource")
    void initializeIfAbsentBindsTimestamp() {
        context.initializeIfAbsent();

        assertThat(TransactionSynchronizationManager.hasResource(TRANSACTION_TIMESTAMP_KEY))
                .isTrue();
        assertThat(context.getTimestamp()).isEqualTo(FIXED_MS);
    }

    @Test
    @DisplayName("initializeIfAbsent is idempotent — repeated calls keep the original timestamp")
    void initializeIfAbsentIsIdempotent() {
        context.initializeIfAbsent();
        long first = context.getTimestamp();

        context.initializeIfAbsent();

        assertThat(context.getTimestamp()).isEqualTo(first);
    }

    @Test
    @DisplayName("initializeIfAbsent registers synchronization that unbinds on afterCompletion")
    void initializeIfAbsentRegistersCleanupSynchronization() {
        context.initializeIfAbsent();
        assertThat(TransactionSynchronizationManager.hasResource(TRANSACTION_TIMESTAMP_KEY))
                .isTrue();

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }

        assertThat(TransactionSynchronizationManager.hasResource(TRANSACTION_TIMESTAMP_KEY))
                .isFalse();
    }
}
