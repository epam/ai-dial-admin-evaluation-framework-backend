package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.service.domain.dto.RunErrorCategory;
import com.epam.aidial.evaluation.service.domain.dto.RunErrorDetailsDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class TestSuiteRunReconciliation {

    private final TestSuiteRunRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional("metaTransactionManager")
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOrphanedRuns() {
        String errorDetails = buildErrorDetails(
                "ORPHANED_RUN",
                RunErrorCategory.INTERNAL,
                "Run was not completed because the application restarted",
                null);

        int updated = repository.failOrphanedRuns(
                List.of(RunStatus.PENDING.name(), RunStatus.RUNNING.name()),
                RunStatus.FAILED.name(),
                "Run was orphaned due to application restart",
                errorDetails);

        if (updated > 0) {
            log.info("Reconciliation: marked {} orphaned runs as FAILED", updated);
        } else {
            log.debug("Reconciliation: no orphaned runs found");
        }
    }

    private String buildErrorDetails(String code, RunErrorCategory category, String message, Object details) {
        RunErrorDetailsDto dto = RunErrorDetailsDto.builder()
                .code(code)
                .category(category.name())
                .message(message)
                .build();
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize error details for reconciliation", ex);
            return null;
        }
    }
}
