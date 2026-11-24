package com.epam.aidial.evaluation.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClientException;
import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.service.domain.CostService;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.RunCostsResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TotalCostResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TotalRunCostResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.web.handler.DefaultExceptionHandler;
import com.epam.aidial.evaluation.web.path.WildcardPathResolver;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("CostController")
class CostControllerTest {

    private CostService costService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        costService = mock(CostService.class);
        CostController controller = new CostController(costService, new WildcardPathResolver());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new DefaultExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("getTotalRunCosts delegates to CostService and returns the results")
    void getTotalRunCostsDelegatesToCostService() throws Exception {
        UUID runId1 = UUID.randomUUID();
        UUID runId2 = UUID.randomUUID();
        List<TotalRunCostResponseDto> dto = List.of(
                TotalRunCostResponseDto.builder()
                        .runId(runId1)
                        .totalCost(0.05)
                        .testCaseCost(0.03)
                        .metricEvalCost(0.02)
                        .build(),
                TotalRunCostResponseDto.builder().runId(runId2).totalCost(null).build());
        when(costService.getTotalRunCosts(List.of(runId1, runId2))).thenReturn(dto);

        mockMvc.perform(post("/api/v1/costs/test-suite-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runIds\":[\"" + runId1 + "\",\"" + runId2 + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(content()
                        .json("[{\"runId\":\"" + runId1
                                + "\",\"totalCost\":0.05,\"testCaseCost\":0.03,\"metricEvalCost\":0.02},"
                                + "{\"runId\":\"" + runId2
                                + "\",\"totalCost\":null,\"testCaseCost\":null,\"metricEvalCost\":null}]"));

        verify(costService).getTotalRunCosts(eq(List.of(runId1, runId2)));
    }

    @Test
    @DisplayName("getTotalRunCosts returns 502 when the underlying dial-adas call fails")
    void getTotalRunCostsReturns502OnDialAdasFailure() throws Exception {
        UUID runId = UUID.randomUUID();
        when(costService.getTotalRunCosts(List.of(runId))).thenThrow(new DialAdasClientException(502, "boom"));

        mockMvc.perform(post("/api/v1/costs/test-suite-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runIds\":[\"" + runId + "\"]}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("getTotalRunCosts returns 400 when the request body is missing")
    void getTotalRunCostsReturns400WhenBodyMissing() throws Exception {
        mockMvc.perform(post("/api/v1/costs/test-suite-runs").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(costService, never()).getTotalRunCosts(anyList());
    }

    @Test
    @DisplayName("getTotalRunCosts returns 400 when runIds is empty")
    void getTotalRunCostsReturns400WhenRunIdsEmpty() throws Exception {
        when(costService.getTotalRunCosts(List.of())).thenThrow(new ValidationException("runIds must not be empty"));

        mockMvc.perform(post("/api/v1/costs/test-suite-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("getTotalRunCosts returns 400 when runIds exceeds MAX_BATCH_RUN_IDS")
    void getTotalRunCostsReturns400WhenOverLimit() throws Exception {
        String tooManyIds = IntStream.range(0, ValidationConstants.MAX_BATCH_RUN_IDS + 1)
                .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));
        when(costService.getTotalRunCosts(anyList()))
                .thenThrow(new ValidationException(
                        "runIds must not exceed " + ValidationConstants.MAX_BATCH_RUN_IDS + " entries"));

        mockMvc.perform(post("/api/v1/costs/test-suite-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runIds\":[" + tooManyIds + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("resolves the deployment ID from the wildcard tail and delegates to CostService")
    void delegatesToCostService() throws Exception {
        DeploymentCostsResponseDto dto = DeploymentCostsResponseDto.builder()
                .totalTestCaseCost(0.0007125)
                .totalMetricEvalCost(0.000231)
                .build();
        when(costService.getDeploymentCosts("applications/public/my-app", 1000L, 2000L))
                .thenReturn(dto);

        mockMvc.perform(get("/api/v1/costs/deployment/applications/public/my-app")
                        .param("from", "1000")
                        .param("to", "2000"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"totalTestCaseCost\":0.0007125,\"totalMetricEvalCost\":0.000231}"));

        verify(costService).getDeploymentCosts(eq("applications/public/my-app"), eq(1000L), eq(2000L));
    }

    @Test
    @DisplayName("passes the deployment ID to CostService still percent-encoded")
    void passesDeploymentIdPercentEncoded() throws Exception {
        when(costService.getDeploymentCosts("applications/public/QA/Code%20Apps%20ALS/app__1.0.0", 1000L, 2000L))
                .thenReturn(DeploymentCostsResponseDto.builder()
                        .totalTestCaseCost(0.067)
                        .totalMetricEvalCost(null)
                        .build());

        mockMvc.perform(get(URI.create("/api/v1/costs/deployment/applications/public/QA/Code%20Apps%20ALS/app__1.0.0"))
                        .param("from", "1000")
                        .param("to", "2000"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"totalTestCaseCost\":0.067}"));

        verify(costService)
                .getDeploymentCosts(eq("applications/public/QA/Code%20Apps%20ALS/app__1.0.0"), eq(1000L), eq(2000L));
    }

    @Test
    @DisplayName("returns 400 when the deployment ID is empty")
    void returns400WhenDeploymentIdEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/costs/deployment/").param("from", "1000").param("to", "2000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 400 when from is missing")
    void returns400WhenFromMissing() throws Exception {
        mockMvc.perform(get("/api/v1/costs/deployment/app").param("to", "2000")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 400 when to is missing")
    void returns400WhenToMissing() throws Exception {
        mockMvc.perform(get("/api/v1/costs/deployment/app").param("from", "1000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("getRunCosts delegates to CostService")
    void getRunCostsDelegatesToCostService() throws Exception {
        UUID runId = UUID.randomUUID();
        RunCostsResponseDto dto = RunCostsResponseDto.builder()
                .avgTestCaseCost(0.0007125)
                .avgMetricEvalCost(0.000231)
                .build();
        when(costService.getRunCosts(runId)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/costs/test-suite-run/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"avgTestCaseCost\":0.0007125,\"avgMetricEvalCost\":0.000231}"));

        verify(costService).getRunCosts(eq(runId));
    }

    @Test
    @DisplayName("getTotalCost delegates to CostService with the body values and returns the costs")
    void getTotalCostDelegatesToCostService() throws Exception {
        when(costService.getTotalCosts(1_000L, 2_000L, List.of("gpt-4o", "my%20app")))
                .thenReturn(TotalCostResponseDto.builder()
                        .totalCost(0.0123)
                        .testCaseCost(0.01)
                        .metricEvalCost(0.0023)
                        .build());

        mockMvc.perform(post("/api/v1/costs/total")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":1000,\"to\":2000,\"deploymentIds\":[\"gpt-4o\",\"my%20app\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"totalCost\":0.0123,\"testCaseCost\":0.01,\"metricEvalCost\":0.0023}"));

        verify(costService).getTotalCosts(1_000L, 2_000L, List.of("gpt-4o", "my%20app"));
    }

    @Test
    @DisplayName("getTotalCost passes null deploymentIds to CostService when the field is omitted")
    void getTotalCostPassesNullDeploymentIdsWhenOmitted() throws Exception {
        when(costService.getTotalCosts(1_000L, 2_000L, null))
                .thenReturn(TotalCostResponseDto.builder().build());

        mockMvc.perform(post("/api/v1/costs/total")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":1000,\"to\":2000}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));

        verify(costService).getTotalCosts(1_000L, 2_000L, null);
    }

    @Test
    @DisplayName("getTotalCost passes an empty deploymentIds list through to CostService")
    void getTotalCostPassesEmptyDeploymentIds() throws Exception {
        when(costService.getTotalCosts(1_000L, 2_000L, List.of()))
                .thenReturn(TotalCostResponseDto.builder().build());

        mockMvc.perform(post("/api/v1/costs/total")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":1000,\"to\":2000,\"deploymentIds\":[]}"))
                .andExpect(status().isOk());

        verify(costService).getTotalCosts(1_000L, 2_000L, List.of());
    }

    @Test
    @DisplayName("getTotalCost returns 400 when from is missing")
    void getTotalCostReturns400WhenFromMissing() throws Exception {
        mockMvc.perform(post("/api/v1/costs/total")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":2000}"))
                .andExpect(status().isBadRequest());

        verify(costService, never()).getTotalCosts(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("getTotalCost returns 400 when to is missing")
    void getTotalCostReturns400WhenToMissing() throws Exception {
        mockMvc.perform(post("/api/v1/costs/total")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":1000}"))
                .andExpect(status().isBadRequest());

        verify(costService, never()).getTotalCosts(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("getTotalCost returns 400 when the request body is missing")
    void getTotalCostReturns400WhenBodyMissing() throws Exception {
        mockMvc.perform(post("/api/v1/costs/total").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(costService, never()).getTotalCosts(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("getTotalCost returns 400 when deploymentIds exceeds MAX_COST_DEPLOYMENT_IDS")
    void getTotalCostReturns400WhenTooManyDeploymentIds() throws Exception {
        String tooMany = IntStream.range(0, ValidationConstants.MAX_COST_DEPLOYMENT_IDS + 1)
                .mapToObj(i -> "\"dep-" + i + "\"")
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/api/v1/costs/total")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":1000,\"to\":2000,\"deploymentIds\":[" + tooMany + "]}"))
                .andExpect(status().isBadRequest());

        verify(costService, never()).getTotalCosts(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("getTotalCost returns 400 when deploymentIds contains a blank entry")
    void getTotalCostReturns400WhenBlankDeploymentId() throws Exception {
        mockMvc.perform(post("/api/v1/costs/total")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":1000,\"to\":2000,\"deploymentIds\":[\"gpt-4o\",\"  \"]}"))
                .andExpect(status().isBadRequest());

        verify(costService, never()).getTotalCosts(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("getTotalCost maps a ValidationException from the service (from > to) to 400")
    void getTotalCostReturns400WhenServiceRejectsRange() throws Exception {
        when(costService.getTotalCosts(2_000L, 1_000L, null)).thenThrow(new ValidationException("from must be <= to"));

        mockMvc.perform(post("/api/v1/costs/total")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":2000,\"to\":1000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("getTotalCost maps a DialAdasClientException from the service to 502")
    void getTotalCostReturns502OnDialAdasFailure() throws Exception {
        when(costService.getTotalCosts(1_000L, 2_000L, null)).thenThrow(new DialAdasClientException(502, "boom"));

        mockMvc.perform(post("/api/v1/costs/total")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":1000,\"to\":2000}"))
                .andExpect(status().isBadGateway());
    }
}
