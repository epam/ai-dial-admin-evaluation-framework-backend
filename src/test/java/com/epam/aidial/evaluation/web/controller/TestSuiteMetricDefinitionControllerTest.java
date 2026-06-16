package com.epam.aidial.evaluation.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epam.aidial.evaluation.service.domain.TestSuiteMetricDefinitionService;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("TestSuiteMetricDefinitionController create")
class TestSuiteMetricDefinitionControllerTest {

    private TestSuiteMetricDefinitionService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(TestSuiteMetricDefinitionService.class);
        PaginationParamResolver paginationParamResolver = mock(PaginationParamResolver.class);
        TestSuiteMetricDefinitionController controller =
                new TestSuiteMetricDefinitionController(service, paginationParamResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("create with body omitting 'enabled' passes a dto with enabled=true (default) to the service")
    void create_bodyWithoutEnabled_dtoDefaultsEnabledToTrue() throws Exception {
        UUID testSuiteId = UUID.randomUUID();
        String body = """
                {
                  "name": "Accuracy Check",
                  "metricDeclarationId": "550e8400-e29b-41d4-a716-446655440000",
                  "metricDeclarationVersionId": "660e8400-e29b-41d4-a716-446655440001"
                }""";
        // Guard the test premise: the request JSON must NOT carry an 'enabled' field.
        assertThat(body).doesNotContain("enabled");

        when(service.create(eq(testSuiteId), any())).thenReturn(new TestSuiteMetricDefinitionResponseDto());

        mockMvc.perform(post("/api/v1/test-suites/{testSuiteId}/metric-definitions", testSuiteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<TestSuiteMetricDefinitionRequestDto> captor =
                ArgumentCaptor.forClass(TestSuiteMetricDefinitionRequestDto.class);
        verify(service).create(eq(testSuiteId), captor.capture());

        TestSuiteMetricDefinitionRequestDto captured = captor.getValue();
        assertThat(captured.isEnabled())
                .as("'enabled' must default to true when the field is absent from the request body")
                .isTrue();
    }
}
