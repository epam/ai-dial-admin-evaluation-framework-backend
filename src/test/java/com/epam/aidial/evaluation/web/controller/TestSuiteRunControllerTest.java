package com.epam.aidial.evaluation.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.CostService;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunConfigService;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.csv.CsvDelimiterParser;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("TestSuiteRunController createRun scheduleRef")
class TestSuiteRunControllerTest {

    private static final String BODY = "{\"runConfig\":{\"numberOfRuns\":1}}";

    private TestSuiteRunService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(TestSuiteRunService.class);
        final TestSuiteRunController controller = new TestSuiteRunController(
                service,
                mock(TestSuiteRunConfigService.class),
                mock(CostService.class),
                mock(PaginationParamResolver.class),
                mock(CsvDelimiterParser.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("passes the scheduleRef query parameter to the service when the scheduler supplies it")
    void passesScheduleRefToService() throws Exception {
        final UUID suiteId = UUID.randomUUID();
        when(service.createRun(eq(suiteId), any(RunConfigDto.class), eq("ref-1")))
                .thenReturn(TestSuiteRunResponseDto.builder().build());

        mockMvc.perform(post("/api/v1/test-suites/{testSuiteId}/runs", suiteId)
                        .param("scheduleRef", "ref-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted());

        verify(service).createRun(eq(suiteId), any(RunConfigDto.class), eq("ref-1"));
    }

    @Test
    @DisplayName("passes a null scheduleRef to the service for a manually started run")
    void passesNullScheduleRefWhenAbsent() throws Exception {
        final UUID suiteId = UUID.randomUUID();
        when(service.createRun(eq(suiteId), any(RunConfigDto.class), isNull()))
                .thenReturn(TestSuiteRunResponseDto.builder().build());

        mockMvc.perform(post("/api/v1/test-suites/{testSuiteId}/runs", suiteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted());

        verify(service).createRun(eq(suiteId), any(RunConfigDto.class), isNull());
    }
}
