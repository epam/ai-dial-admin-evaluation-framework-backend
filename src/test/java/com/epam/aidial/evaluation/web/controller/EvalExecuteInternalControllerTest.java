package com.epam.aidial.evaluation.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epam.aidial.evaluation.configuration.properties.dialapp.DialAppProperties;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.job.ActiveRunRegistry;
import com.epam.aidial.evaluation.service.domain.job.RunAlreadyActiveException;
import com.epam.aidial.evaluation.service.domain.job.TestSuiteEvaluationJob;
import com.epam.aidial.evaluation.web.handler.DefaultExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DisplayName("EvalExecuteInternalController")
class EvalExecuteInternalControllerTest {

    private TestSuiteRunService testSuiteRunService;
    private TestSuiteEvaluationJob testSuiteEvaluationJob;
    private TestSuiteRunSseService sseService;
    private ActiveRunRegistry activeRunRegistry;
    private DialAppProperties dialAppProperties;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        testSuiteRunService = mock(TestSuiteRunService.class);
        testSuiteEvaluationJob = mock(TestSuiteEvaluationJob.class);
        sseService = mock(TestSuiteRunSseService.class);
        activeRunRegistry = mock(ActiveRunRegistry.class);
        dialAppProperties = mock(DialAppProperties.class);
        when(dialAppProperties.getHeartbeatIntervalMs()).thenReturn(30000L);

        EvalExecuteInternalController controller = new EvalExecuteInternalController(
                testSuiteRunService, testSuiteEvaluationJob, sseService, activeRunRegistry, dialAppProperties);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new DefaultExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        AuthorizationTokenHolder.clearToken();
    }

    @Test
    @DisplayName("returns 404 when the runId is not found")
    void returns404WhenRunNotFound() throws Exception {
        UUID runId = UUID.randomUUID();
        when(testSuiteRunService.getRun(runId)).thenThrow(new EntityNotFoundException("TestSuiteRun not found"));

        mockMvc.perform(post("/api/internal/runs/" + runId + "/execute")).andExpect(status().isNotFound());

        verify(testSuiteEvaluationJob, never()).dispatch(any(), any(), eq(false));
    }

    @Test
    @DisplayName("returns 409 when the runId is already active")
    void returns409WhenRunAlreadyActive() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID testSuiteId = UUID.randomUUID();
        TestSuiteRunResponseDto runDto =
                TestSuiteRunResponseDto.builder().testSuiteId(testSuiteId).build();
        when(testSuiteRunService.getRun(runId)).thenReturn(runDto);
        CallerCredential credential = CallerCredential.apiKey("prk-value");
        AuthorizationTokenHolder.setCredential(credential);
        SseEmitter emitter = mock(SseEmitter.class);
        when(sseService.createEmitterWithHeartbeat(any(), any(), any(), eq(30000L), any()))
                .thenReturn(emitter);
        doThrow(new RunAlreadyActiveException(runId))
                .when(testSuiteEvaluationJob)
                .dispatch(eq(runId), eq(credential), eq(false));

        mockMvc.perform(post("/api/internal/runs/" + runId + "/execute")).andExpect(status().isConflict());

        verify(sseService).discardEmitter(emitter);
    }

    @Test
    @DisplayName("returns 200 with an SseEmitter, creating the emitter before dispatching the job")
    void returns200AndCreatesEmitterBeforeDispatch() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID testSuiteId = UUID.randomUUID();
        TestSuiteRunResponseDto runDto =
                TestSuiteRunResponseDto.builder().testSuiteId(testSuiteId).build();
        when(testSuiteRunService.getRun(runId)).thenReturn(runDto);
        CallerCredential credential = CallerCredential.apiKey("prk-value");
        AuthorizationTokenHolder.setCredential(credential);
        SseEmitter emitter = new SseEmitter();
        when(sseService.createEmitterWithHeartbeat(any(), any(), any(), eq(30000L), any()))
                .thenReturn(emitter);

        mockMvc.perform(post("/api/internal/runs/" + runId + "/execute"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        InOrder inOrder = inOrder(sseService, testSuiteEvaluationJob);
        inOrder.verify(sseService).createEmitterWithHeartbeat(any(), any(), any(), eq(30000L), any());
        inOrder.verify(testSuiteEvaluationJob).dispatch(eq(runId), eq(credential), eq(false));
        verify(sseService, never()).discardEmitter(any());
    }

    @Test
    @DisplayName("dispatches with the API_KEY-kind credential sourced from AuthorizationTokenHolder")
    void dispatchesWithCredentialFromAuthorizationTokenHolder() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID testSuiteId = UUID.randomUUID();
        TestSuiteRunResponseDto runDto =
                TestSuiteRunResponseDto.builder().testSuiteId(testSuiteId).build();
        when(testSuiteRunService.getRun(runId)).thenReturn(runDto);
        CallerCredential credential = CallerCredential.apiKey("prk-value");
        AuthorizationTokenHolder.setCredential(credential);
        SseEmitter emitter = new SseEmitter();
        when(sseService.createEmitterWithHeartbeat(any(), any(), any(), eq(30000L), any()))
                .thenReturn(emitter);

        mockMvc.perform(post("/api/internal/runs/" + runId + "/execute")).andExpect(status().isOk());

        verify(testSuiteEvaluationJob).dispatch(eq(runId), eq(credential), eq(false));
    }

    @Test
    @DisplayName("discards the emitter before propagating when dispatch throws")
    void discardsEmitterWhenDispatchThrows() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID testSuiteId = UUID.randomUUID();
        TestSuiteRunResponseDto runDto =
                TestSuiteRunResponseDto.builder().testSuiteId(testSuiteId).build();
        when(testSuiteRunService.getRun(runId)).thenReturn(runDto);
        CallerCredential credential = CallerCredential.apiKey("prk-value");
        AuthorizationTokenHolder.setCredential(credential);
        SseEmitter emitter = mock(SseEmitter.class);
        when(sseService.createEmitterWithHeartbeat(any(), any(), any(), eq(30000L), any()))
                .thenReturn(emitter);
        doThrow(new RunAlreadyActiveException(runId))
                .when(testSuiteEvaluationJob)
                .dispatch(eq(runId), eq(credential), eq(false));

        mockMvc.perform(post("/api/internal/runs/" + runId + "/execute")).andExpect(status().isConflict());

        InOrder inOrder = inOrder(testSuiteEvaluationJob, sseService);
        inOrder.verify(testSuiteEvaluationJob).dispatch(eq(runId), eq(credential), eq(false));
        inOrder.verify(sseService).discardEmitter(emitter);
    }
}
