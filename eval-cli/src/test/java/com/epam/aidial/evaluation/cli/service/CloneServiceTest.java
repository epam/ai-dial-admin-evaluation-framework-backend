package com.epam.aidial.evaluation.cli.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.cli.client.source.TestSuiteApiClient;
import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteResponseDto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CloneServiceTest {

    @Mock
    private TestSuiteApiClient testSuiteApiClient;

    private CloneService cloneService;

    private final UUID sourceSuiteId = UUID.randomUUID();
    private final UUID existingCloneId = UUID.randomUUID();
    private final UUID newCloneId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cloneService = new CloneService(testSuiteApiClient);
    }

    @Test
    @DisplayName("resolveClone reuses an existing clone with matching name")
    void resolveCloneReusesExistingClone() {
        final TestSuiteResponseDto sourceSuite = TestSuiteResponseDto.builder()
                .id(sourceSuiteId)
                .name("My Suite")
                .build();
        final TestSuiteResponseDto existingClone = TestSuiteResponseDto.builder()
                .id(existingCloneId)
                .name("My Suite_ci")
                .build();

        when(testSuiteApiClient.findById(sourceSuiteId)).thenReturn(Optional.of(sourceSuite));
        when(testSuiteApiClient.findByExactName("My Suite_ci")).thenReturn(Optional.of(existingClone));

        final UUID result = cloneService.resolveClone(sourceSuiteId, "ci");

        assertThat(result).isEqualTo(existingCloneId);
        verify(testSuiteApiClient, never()).clone(any(), any());
    }

    @Test
    @DisplayName("resolveClone creates a new clone when none exists with matching name")
    void resolveCloneCreatesNewCloneWhenNoneExists() {
        final TestSuiteResponseDto sourceSuite = TestSuiteResponseDto.builder()
                .id(sourceSuiteId)
                .name("My Suite")
                .build();
        final TestSuiteResponseDto newClone = TestSuiteResponseDto.builder()
                .id(newCloneId)
                .name("My Suite_ci")
                .build();

        when(testSuiteApiClient.findById(sourceSuiteId)).thenReturn(Optional.of(sourceSuite));
        when(testSuiteApiClient.findByExactName("My Suite_ci")).thenReturn(Optional.empty());
        when(testSuiteApiClient.clone(eq(sourceSuiteId), any(TestSuiteCloneRequestDto.class)))
                .thenReturn(newClone);

        final UUID result = cloneService.resolveClone(sourceSuiteId, "ci");

        assertThat(result).isEqualTo(newCloneId);
        verify(testSuiteApiClient).clone(eq(sourceSuiteId), any(TestSuiteCloneRequestDto.class));
    }

    @Test
    @DisplayName("resolveClone throws when source suite is not found")
    void resolveCloneThrowsWhenSourceSuiteNotFound() {
        when(testSuiteApiClient.findById(sourceSuiteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cloneService.resolveClone(sourceSuiteId, "ci"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(sourceSuiteId.toString());
    }

    @Test
    @DisplayName("resolveClones returns a map for all given suites")
    void resolveClonesReturnsMappingForAllGivenSuites() {
        final TestSuiteResponseDto sourceSuite =
                TestSuiteResponseDto.builder().id(sourceSuiteId).name("Suite A").build();
        final TestSuiteResponseDto existingClone = TestSuiteResponseDto.builder()
                .id(existingCloneId)
                .name("Suite A_ci")
                .build();

        when(testSuiteApiClient.findById(sourceSuiteId)).thenReturn(Optional.of(sourceSuite));
        when(testSuiteApiClient.findByExactName("Suite A_ci")).thenReturn(Optional.of(existingClone));

        final Map<UUID, UUID> result = cloneService.resolveClones(List.of(sourceSuiteId), "ci");

        assertThat(result).hasSize(1).containsEntry(sourceSuiteId, existingCloneId);
    }
}
