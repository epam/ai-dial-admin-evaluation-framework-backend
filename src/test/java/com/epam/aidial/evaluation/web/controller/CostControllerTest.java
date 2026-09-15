package com.epam.aidial.evaluation.web.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epam.aidial.evaluation.service.domain.CostService;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentCostsResponseDto;
import com.epam.aidial.evaluation.web.handler.DefaultExceptionHandler;
import com.epam.aidial.evaluation.web.path.WildcardPathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("CostController.getDeploymentCosts")
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
}
