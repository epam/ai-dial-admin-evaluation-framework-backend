package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.RevalidationStatus;
import com.epam.aidial.evaluation.service.domain.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for the revalidation task flow: a dataset schema change spawns an async task,
 * the task is listable at {@code /datasets/{id}/revalidation-tasks}, and Phase 2 per-suite resilience
 * lets the task complete even when one suite has problematic configuration.
 */
@DisplayName("Revalidation Task Functional Tests")
public abstract class RevalidationTaskFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Test
    @DisplayName("dataset schema change spawns revalidation task and is listable + retrievable")
    void schemaChangeSpawnsTaskAndIsListable() {
        Dataset dataset = metaTestDataHelper.createDataset("Revalidate-" + UUID.randomUUID());

        DatasetRequestDto update = DatasetRequestDto.builder()
                .name(dataset.getName())
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("newField")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch("\"" + (dataset.getVersion() == null ? 0L : dataset.getVersion()) + "\"");

        ResponseEntity<RevalidationTaskDto> updateResp = restTemplate.exchange(
                apiUrl("/datasets/" + dataset.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(update, headers),
                RevalidationTaskDto.class);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(updateResp.getBody()).isNotNull();
        UUID taskId = updateResp.getBody().getTaskId();
        assertThat(taskId).isNotNull();

        // GET by id returns the task
        ResponseEntity<RevalidationTaskDto> getResp = restTemplate.getForEntity(
                apiUrl("/datasets/" + dataset.getId() + "/revalidation-tasks/" + taskId), RevalidationTaskDto.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody()).isNotNull();
        assertThat(getResp.getBody().getTaskId()).isEqualTo(taskId);
        assertThat(getResp.getBody().getDatasetId()).isEqualTo(dataset.getId());

        // GET list returns at least this task
        ResponseEntity<PageResponseDto<RevalidationTaskDto>> listResp = restTemplate.exchange(
                apiUrl("/datasets/" + dataset.getId() + "/revalidation-tasks?page=0&size=10"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).isNotNull();
        assertThat(listResp.getBody().getContent())
                .extracting(RevalidationTaskDto::getTaskId)
                .contains(taskId);

        // The async task reaches a terminal state (COMPLETED) within a reasonable timeout.
        RevalidationTaskDto terminal = awaitTerminal(dataset.getId(), taskId, 15);
        assertThat(terminal.getStatus()).isIn(RevalidationStatus.COMPLETED, RevalidationStatus.FAILED);
    }

    @Test
    @DisplayName(
            "revalidation task ends COMPLETED even when a referencing suite has problematic config (Phase 2 resilience)")
    void taskCompletesEvenWithProblematicSuite() {
        // Dataset with a schema, plus a suite referencing it
        Dataset dataset = metaTestDataHelper.createDataset(
                "Phase2-" + UUID.randomUUID(), "[{\"name\":\"q\",\"type\":\"STRING\"}]");
        metaTestDataHelper.createTestSuite("Suite-" + UUID.randomUUID(), dataset.getId());

        // Change the dataset schema — spawns revalidation
        DatasetRequestDto update = DatasetRequestDto.builder()
                .name(dataset.getName())
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("renamed")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch("\"" + (dataset.getVersion() == null ? 0L : dataset.getVersion()) + "\"");

        ResponseEntity<RevalidationTaskDto> updateResp = restTemplate.exchange(
                apiUrl("/datasets/" + dataset.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(update, headers),
                RevalidationTaskDto.class);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID taskId = updateResp.getBody().getTaskId();

        // Task reaches COMPLETED (not FAILED). Per-suite Phase 2 failures are isolated and logged;
        // they do not propagate to the task-level status.
        RevalidationTaskDto terminal = awaitTerminal(dataset.getId(), taskId, 20);
        assertThat(terminal.getStatus()).isEqualTo(RevalidationStatus.COMPLETED);
    }

    private RevalidationTaskDto awaitTerminal(UUID datasetId, UUID taskId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<RevalidationTaskDto> resp = restTemplate.getForEntity(
                    apiUrl("/datasets/" + datasetId + "/revalidation-tasks/" + taskId), RevalidationTaskDto.class);
            if (resp.getStatusCode() == HttpStatus.OK
                    && resp.getBody() != null
                    && (resp.getBody().getStatus() == RevalidationStatus.COMPLETED
                            || resp.getBody().getStatus() == RevalidationStatus.FAILED
                            || resp.getBody().getStatus() == RevalidationStatus.TIMED_OUT)) {
                return resp.getBody();
            }
            sleep(150);
        }
        throw new AssertionError("Revalidation task did not reach terminal status within " + timeoutSeconds + "s");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }
}
