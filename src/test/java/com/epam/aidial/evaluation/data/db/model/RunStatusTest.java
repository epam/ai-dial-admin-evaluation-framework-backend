package com.epam.aidial.evaluation.data.db.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RunStatus")
class RunStatusTest {

    @Test
    @DisplayName("CANCELLING is not terminal")
    void cancellingIsNotTerminal() {
        assertThat(RunStatus.isTerminal(RunStatus.CANCELLING.name())).isFalse();
    }

    @Test
    @DisplayName("Terminal statuses are unchanged: COMPLETED, FAILED, CANCELLED")
    void terminalStatusesUnchanged() {
        assertThat(RunStatus.isTerminal(RunStatus.COMPLETED.name())).isTrue();
        assertThat(RunStatus.isTerminal(RunStatus.FAILED.name())).isTrue();
        assertThat(RunStatus.isTerminal(RunStatus.CANCELLED.name())).isTrue();
        assertThat(RunStatus.isTerminal(RunStatus.PENDING.name())).isFalse();
        assertThat(RunStatus.isTerminal(RunStatus.RUNNING.name())).isFalse();
    }

    @Test
    @DisplayName("ACTIVE_STATUSES contains exactly PENDING, RUNNING, CANCELLING")
    void activeStatusesContents() {
        assertThat(RunStatus.ACTIVE_STATUSES)
                .containsExactlyInAnyOrder(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.CANCELLING);
    }

    @Test
    @DisplayName("activeStatusNames returns the ACTIVE_STATUSES names")
    void activeStatusNamesContents() {
        assertThat(RunStatus.activeStatusNames())
                .containsExactlyInAnyOrder(
                        RunStatus.PENDING.name(), RunStatus.RUNNING.name(), RunStatus.CANCELLING.name());
        assertThat(RunStatus.activeStatusNames()).isInstanceOf(List.class);
    }
}
