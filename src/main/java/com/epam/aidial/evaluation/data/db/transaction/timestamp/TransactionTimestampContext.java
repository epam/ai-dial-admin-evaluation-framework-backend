package com.epam.aidial.evaluation.data.db.transaction.timestamp;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class TransactionTimestampContext {

    public static final String TRANSACTION_TIMESTAMP_KEY = "TRANSACTION_TIMESTAMP_KEY";

    private final Clock clock;

    public long getTimestamp() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("No active transaction");
        }

        Long timestamp = (Long) TransactionSynchronizationManager.getResource(TRANSACTION_TIMESTAMP_KEY);
        if (timestamp == null) {
            throw new IllegalStateException("Timestamp not initialized");
        }

        return timestamp;
    }

    /**
     * Binds a transaction-scoped timestamp to the current transaction if not already bound, and
     * registers cleanup on completion. Must be called inside an active transaction. Safe to call
     * multiple times — the hasResource guard prevents duplicate initialization.
     *
     * <p>Intended for programmatic transaction management (e.g. {@code TransactionTemplate}). For
     * {@code @Transactional}-annotated methods, {@code TransactionTimestampAspect} calls this
     * automatically.
     */
    public void initializeIfAbsent() {
        if (!TransactionSynchronizationManager.hasResource(TRANSACTION_TIMESTAMP_KEY)) {
            TransactionSynchronizationManager.bindResource(TRANSACTION_TIMESTAMP_KEY, clock.millis());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(TRANSACTION_TIMESTAMP_KEY);
                }
            });
        }
    }
}
