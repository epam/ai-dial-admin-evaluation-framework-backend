package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.TestSuiteSchedule;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteScheduleRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("ScheduleRefResolver")
@ExtendWith(MockitoExtension.class)
class ScheduleRefResolverTest {

    private static final UUID SUITE_ID = UUID.randomUUID();

    @Mock
    private TestSuiteScheduleRepository repository;

    @InjectMocks
    private ScheduleRefResolver resolver;

    @Test
    @DisplayName("shouldReturnTheScheduleIdWhenTheReferenceBelongsToAScheduleOfTheSameSuite")
    void shouldResolveReferenceOfSameSuite() {
        when(repository.findByRef("ref-1")).thenReturn(Optional.of(schedule("sch-1", SUITE_ID)));

        assertThat(resolver.resolveScheduleId("ref-1", SUITE_ID)).contains("sch-1");
    }

    @Test
    @DisplayName("shouldReturnEmptyWithoutQueryingWhenTheReferenceIsNullOrBlank")
    void shouldReturnEmptyForBlankReference() {
        assertThat(resolver.resolveScheduleId(null, SUITE_ID)).isEmpty();
        assertThat(resolver.resolveScheduleId("", SUITE_ID)).isEmpty();
        assertThat(resolver.resolveScheduleId("   ", SUITE_ID)).isEmpty();

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("shouldReturnEmptyWhenNoScheduleOwnsTheReference")
    void shouldReturnEmptyForUnknownReference() {
        when(repository.findByRef("unknown")).thenReturn(Optional.empty());

        assertThat(resolver.resolveScheduleId("unknown", SUITE_ID)).isEmpty();
    }

    @Test
    @DisplayName("shouldReturnEmptyWhenTheReferenceBelongsToAScheduleOfAnotherSuite")
    void shouldReturnEmptyForReferenceOfAnotherSuite() {
        when(repository.findByRef("ref-1")).thenReturn(Optional.of(schedule("sch-1", UUID.randomUUID())));

        assertThat(resolver.resolveScheduleId("ref-1", SUITE_ID)).isEmpty();
    }

    private static TestSuiteSchedule schedule(String scheduleId, UUID suiteId) {
        return TestSuiteSchedule.builder()
                .scheduleId(scheduleId)
                .testSuiteId(suiteId)
                .scheduleRef("ref-1")
                .build();
    }
}
