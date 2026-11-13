package com.epam.aidial.evaluation.web.controller;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epam.aidial.evaluation.service.domain.analytics.SuitePassRateService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateSummaryDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.web.handler.DefaultExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("SuitePassRateController")
class SuitePassRateControllerTest {

    private static final String PATH = "/api/v1/analytics/test-suites/pass-rate";

    private SuitePassRateService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(SuitePassRateService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SuitePassRateController(service))
                .setControllerAdvice(new DefaultExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("returns 200 with the summary from the service for the requested suite ids")
    void returnsSummary() throws Exception {
        UUID suiteA = UUID.randomUUID();
        UUID suiteB = UUID.randomUUID();
        when(service.summarize(List.of(suiteA, suiteB)))
                .thenReturn(SuitePassRateSummaryDto.builder()
                        .passRate(0.75)
                        .suiteCount(2)
                        .previousPassRate(0.0)
                        .previousSuiteCount(1)
                        .build());

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suiteIds\":[\"" + suiteA + "\",\"" + suiteB + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(content()
                        .json("{\"passRate\":0.75,\"suiteCount\":2,\"previousPassRate\":0.0,"
                                + "\"previousSuiteCount\":1}"));
    }

    @Test
    @DisplayName("returns 400 when the service rejects an empty suiteIds list")
    void returns400ForEmptyList() throws Exception {
        when(service.summarize(List.of())).thenThrow(new ValidationException("suiteIds must not be empty"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"suiteIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 400 when the service rejects a list over the cap")
    void returns400ForOverCap() throws Exception {
        when(service.summarize(anyList())).thenThrow(new ValidationException("suiteIds must not exceed 100"));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suiteIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 400 when a suite id is not a UUID and does not call the service")
    void returns400ForNonUuidId() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suiteIds\":[\"not-a-uuid\"]}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).summarize(anyList());
    }

    @Test
    @DisplayName("returns 400 when the request body is missing and does not call the service")
    void returns400ForMissingBody() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());

        verify(service, never()).summarize(anyList());
    }
}
