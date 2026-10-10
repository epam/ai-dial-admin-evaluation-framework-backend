package com.epam.aidial.evaluation.service.domain;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TestCaseService.renameDataFields")
class TestCaseServiceRenameDataFieldsTest {

    @Mock
    private TestCaseRepository testCaseRepository;

    @InjectMocks
    private TestCaseService service;

    @Test
    @DisplayName("non-empty renames are passed through to the repository")
    void passesThrough() {
        final UUID datasetId = UUID.randomUUID();
        final Map<String, String> renames = Map.of("a", "b");

        service.renameDataFields(datasetId, renames);

        verify(testCaseRepository).renameDataFields(datasetId, renames);
    }

    @Test
    @DisplayName("empty renames short-circuit without touching the repository")
    void emptyShortCircuits() {
        service.renameDataFields(UUID.randomUUID(), Map.of());

        verifyNoInteractions(testCaseRepository);
    }
}
