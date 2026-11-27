package com.epam.aidial.evaluation.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.TestSuiteScheduleService;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleCronDto;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleRunDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleResponseDto;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("TestSuiteScheduleController")
class TestSuiteScheduleControllerTest {

    private static final String VALID_BODY = """
            {
              "name": "Nightly",
              "cron": {"hour": "2", "minute": "0"},
              "timezone": "Europe/Berlin",
              "runConfig": {"numberOfRuns": 2}
            }\
            """;

    private TestSuiteScheduleService service;
    private PaginationParamResolver paginationParamResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(TestSuiteScheduleService.class);
        paginationParamResolver = mock(PaginationParamResolver.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TestSuiteScheduleController(service, paginationParamResolver))
                // Standalone MockMvc has no Spring Security: without this resolver the @AuthenticationPrincipal Jwt
                // parameter would be bound as a model attribute. With no authentication it resolves to null.
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("POST create returns 201 and hands the suite id and parsed body to the service")
    void createReturns201AndDelegates() throws Exception {
        final UUID suiteId = UUID.randomUUID();
        when(service.create(eq(suiteId), any(TestSuiteScheduleRequestDto.class), any()))
                .thenReturn(TestSuiteScheduleResponseDto.builder()
                        .id("sch-1")
                        .testSuiteId(suiteId)
                        .build());

        mockMvc.perform(post("/api/v1/test-suites/{testSuiteId}/schedules", suiteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("sch-1"))
                .andExpect(jsonPath("$.testSuiteId").value(suiteId.toString()));

        final ArgumentCaptor<TestSuiteScheduleRequestDto> body =
                ArgumentCaptor.forClass(TestSuiteScheduleRequestDto.class);
        verify(service).create(eq(suiteId), body.capture(), any());
        assertThat(body.getValue().getName()).isEqualTo("Nightly");
        assertThat(body.getValue().getTimezone()).isEqualTo("Europe/Berlin");
        assertThat(body.getValue().getCron().getHour()).isEqualTo("2");
        assertThat(body.getValue().getRunConfig().getNumberOfRuns()).isEqualTo(2);
    }

    @Test
    @DisplayName("POST create rejects a body missing name, cron and runConfig with 400 and never reaches the service")
    void createRejectsMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/api/v1/test-suites/{testSuiteId}/schedules", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST create rejects a blank name with 400")
    void createRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/v1/test-suites/{testSuiteId}/schedules", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"Nightly\"", "\"  \"")))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("POST create rejects a run configuration with numberOfRuns below 1 with 400")
    void createRejectsInvalidRunConfig() throws Exception {
        mockMvc.perform(post("/api/v1/test-suites/{testSuiteId}/schedules", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"numberOfRuns\": 2", "\"numberOfRuns\": 0")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST create rejects a cron field longer than 64 characters with 400")
    void createRejectsOversizedCronField() throws Exception {
        mockMvc.perform(post("/api/v1/test-suites/{testSuiteId}/schedules", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"hour\": \"2\"", "\"hour\": \"" + "9".repeat(65) + "\"")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST create rejects a non-UUID suite id with 400")
    void createRejectsMalformedSuiteId() throws Exception {
        mockMvc.perform(post("/api/v1/test-suites/{testSuiteId}/schedules", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET list resolves page and size through the shared resolver and passes the page request on")
    void listResolvesPaginationThroughResolver() throws Exception {
        final UUID suiteId = UUID.randomUUID();
        when(paginationParamResolver.resolvePage(2)).thenReturn(2);
        when(paginationParamResolver.resolveSize(5)).thenReturn(5);
        when(service.listForSuite(suiteId, 2, 5))
                .thenReturn(PageResponseDto.<TestSuiteScheduleResponseDto>builder()
                        .content(List.of(TestSuiteScheduleResponseDto.builder()
                                .id("sch-1")
                                .build()))
                        .page(2)
                        .size(5)
                        .totalElements(11L)
                        .totalPages(3)
                        .build());

        mockMvc.perform(get("/api/v1/test-suites/{testSuiteId}/schedules", suiteId)
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("sch-1"))
                .andExpect(jsonPath("$.totalElements").value(11));

        verify(service).listForSuite(suiteId, 2, 5);
    }

    @Test
    @DisplayName("GET by id, pause and resume delegate with the schedule id from the path")
    void itemEndpointsDelegateWithScheduleId() throws Exception {
        when(service.get("sch-1"))
                .thenReturn(TestSuiteScheduleResponseDto.builder().id("sch-1").build());
        when(service.pause("sch-1"))
                .thenReturn(TestSuiteScheduleResponseDto.builder()
                        .id("sch-1")
                        .paused(true)
                        .build());
        when(service.resume("sch-1"))
                .thenReturn(TestSuiteScheduleResponseDto.builder().id("sch-1").build());

        mockMvc.perform(get("/api/v1/schedules/{scheduleId}", "sch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sch-1"));
        mockMvc.perform(post("/api/v1/schedules/{scheduleId}/pause", "sch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true));
        mockMvc.perform(post("/api/v1/schedules/{scheduleId}/resume", "sch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));
    }

    @Test
    @DisplayName("PUT edit returns 200 and passes the schedule id and body to the service")
    void updateDelegates() throws Exception {
        when(service.update(eq("sch-1"), any(TestSuiteScheduleRequestDto.class)))
                .thenReturn(TestSuiteScheduleResponseDto.builder().id("sch-1").build());

        mockMvc.perform(put("/api/v1/schedules/{scheduleId}", "sch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sch-1"));

        final ArgumentCaptor<TestSuiteScheduleRequestDto> body =
                ArgumentCaptor.forClass(TestSuiteScheduleRequestDto.class);
        verify(service).update(eq("sch-1"), body.capture());
        assertThat(body.getValue().getCron())
                .isEqualTo(ScheduleCronDto.builder().hour("2").minute("0").build());
    }

    @Test
    @DisplayName("PUT edit rejects an invalid body with 400 and never reaches the service")
    void updateRejectsInvalidBody() throws Exception {
        mockMvc.perform(put("/api/v1/schedules/{scheduleId}", "sch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"x\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("DELETE returns 204 with no body and delegates")
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/schedules/{scheduleId}", "sch-1")).andExpect(status().isNoContent());

        verify(service).delete("sch-1");
    }

    @Test
    @DisplayName("GET runs resolves pagination and returns the fire history page")
    void listRunsDelegates() throws Exception {
        when(paginationParamResolver.resolvePage(null)).thenReturn(0);
        when(paginationParamResolver.resolveSize(null)).thenReturn(20);
        when(service.listRuns("sch-1", 0, 20))
                .thenReturn(PageResponseDto.<ScheduleRunDto>builder()
                        .content(List.of(ScheduleRunDto.builder()
                                .id("r1")
                                .status("success")
                                .build()))
                        .page(0)
                        .size(20)
                        .build());

        mockMvc.perform(get("/api/v1/schedules/{scheduleId}/runs", "sch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("success"));

        verify(service).listRuns("sch-1", 0, 20);
    }

    @Test
    @DisplayName("the controller is only registered when dial.scheduler.enabled=true")
    void controllerIsRegisteredOnlyWhenSchedulingIsEnabled() {
        final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .registerSingleton("scheduleService", mock(TestSuiteScheduleService.class)))
                .withBean(PaginationParamResolver.class, () -> mock(PaginationParamResolver.class))
                .withConfiguration(UserConfigurations.of(TestSuiteScheduleController.class));

        runner.run(context -> assertThat(context).doesNotHaveBean(TestSuiteScheduleController.class));
        runner.withPropertyValues("dial.scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TestSuiteScheduleController.class));
        runner.withPropertyValues("dial.scheduler.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(TestSuiteScheduleController.class));
    }
}
