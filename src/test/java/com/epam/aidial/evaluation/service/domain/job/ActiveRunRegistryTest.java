package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ActiveRunRegistry")
class ActiveRunRegistryTest {

    private final ActiveRunRegistry registry = new ActiveRunRegistry(new RunExecutorFactory(true));

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

    @Test
    @DisplayName("activeCount is 0 before any run is registered")
    void activeCountIsZeroInitially() {
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    @DisplayName("activeCount is 1 after a run is registered")
    void activeCountIsOneAfterRegister() {
        registry.register(UUID.randomUUID());

        assertThat(registry.activeCount()).isOne();
    }

    @Test
    @DisplayName("activeCount returns to 0 after the registered run is removed")
    void activeCountIsZeroAfterRemove() {
        UUID runId = UUID.randomUUID();
        registry.register(runId);

        registry.remove(runId);

        assertThat(registry.activeCount()).isZero();
    }

    @Test
    @DisplayName("the registered handle's executor runs tasks on virtual threads")
    void registeredHandleExecutorRunsOnVirtualThreads() throws Exception {
        RunHandle handle = registry.register(UUID.randomUUID());

        boolean isVirtual = CompletableFuture.supplyAsync(
                        () -> Thread.currentThread().isVirtual(), handle.executor())
                .get(5, TimeUnit.SECONDS);

        assertThat(isVirtual).isTrue();
    }
}
