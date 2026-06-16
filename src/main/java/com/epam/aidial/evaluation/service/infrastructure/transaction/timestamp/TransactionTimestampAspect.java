package com.epam.aidial.evaluation.service.infrastructure.transaction.timestamp;

import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Aspect
@Component
@RequiredArgsConstructor
public class TransactionTimestampAspect {

    private static final String META_TRANSACTION_MANAGER = "metaTransactionManager";

    private final TransactionTimestampContext transactionTimestampContext;

    @Before("@annotation(transactional)")
    // Same-TM nested transactions (e.g., PROPAGATION_REQUIRES_NEW) are not used in this codebase.
    // The hasResource guard in initializeIfAbsent prevents duplicate initialization.
    public void initializeTransactionTimestamp(Transactional transactional) {
        if (!isMetaTransaction(transactional)) {
            return;
        }
        transactionTimestampContext.initializeIfAbsent();
    }

    private static boolean isMetaTransaction(Transactional transactional) {
        String value = transactional.value();
        return value.isEmpty() || META_TRANSACTION_MANAGER.equals(value);
    }
}
