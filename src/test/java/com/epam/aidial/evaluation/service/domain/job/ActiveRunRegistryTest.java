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
    @DisplayName("register returns a fresh, not-yet-cancelled handle")
    void registerReturnsFreshHandle() {
        UUID runId = UUID.randomUUID();

        RunHandle handle = registry.register(runId);

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
    @DisplayName("remove clears the handle so a subsequent cancel is a no-op")
    void removeClearsHandleSoCancelIsNoop() {
        UUID runId = UUID.randomUUID();
        RunHandle handle = registry.register(runId);

        registry.remove(runId);
        registry.cancel(runId);

        assertThat(handle.isCancelled())
                .as("cancel after remove must not reach the handle that was just removed")
                .isFalse();
    }
}
