package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ActiveRunRegistry")
class ActiveRunRegistryTest {

    private final ActiveRunRegistry registry = new ActiveRunRegistry();

    @Test
    @DisplayName("register makes the handle findable and not cancelled")
    void registerMakesHandleFindable() {
        UUID runId = UUID.randomUUID();

        RunHandle handle = registry.register(runId);

        assertThat(registry.find(runId)).contains(handle);
        assertThat(handle.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("cancel of a registered run cancels its handle")
    void cancelRegisteredRun() {
        UUID runId = UUID.randomUUID();
        RunHandle handle = registry.register(runId);

        registry.cancel(runId);

        assertThat(handle.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("cancel of an unknown run id is a no-op")
    void cancelUnknownRunIsNoop() {
        assertThatCode(() -> registry.cancel(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("remove clears the handle so find returns empty")
    void removeClearsHandle() {
        UUID runId = UUID.randomUUID();
        registry.register(runId);

        registry.remove(runId);

        assertThat(registry.find(runId)).isEmpty();
    }

    @Test
    @DisplayName("find returns empty for an id that was never registered")
    void findReturnsEmptyForUnknownId() {
        assertThat(registry.find(UUID.randomUUID())).isEmpty();
    }
}
