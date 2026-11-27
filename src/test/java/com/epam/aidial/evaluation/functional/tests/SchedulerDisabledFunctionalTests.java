package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.client.scheduler.DialSchedulerClient;
import com.epam.aidial.evaluation.service.domain.TestSuiteScheduleService;
import com.epam.aidial.evaluation.web.controller.TestSuiteScheduleController;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Scheduling is off unless {@code dial.scheduler.enabled=true}. This runs in the default functional context, so it
 * also proves the application boots without scheduling and that the schedule routes are simply absent.
 */
@DisplayName("Test Suite Schedules Disabled Functional Tests")
public abstract class SchedulerDisabledFunctionalTests extends BaseFunctionalTest {

    private static final String BODY = """
            {"name":"Nightly","cron":{"hour":"2"},"runConfig":{"numberOfRuns":1}}\
            """;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Should not register the scheduler client, service or controller beans")
    void shouldNotRegisterSchedulingBeans() {
        assertThat(context.getBeanNamesForType(DialSchedulerClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TestSuiteScheduleService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TestSuiteScheduleController.class))
                .isEmpty();
    }

    @Test
    @DisplayName("Should answer 404 for every schedule route")
    void shouldAnswerNotFoundForScheduleRoutes() {
        final UUID suiteId = UUID.randomUUID();

        assertThat(restTemplate
                        .exchange(apiUrl("/schedules/any-id"), HttpMethod.GET, null, String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate
                        .exchange(
                                apiUrl("/test-suites/" + suiteId + "/schedules"),
                                HttpMethod.POST,
                                jsonEntity(BODY),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate
                        .exchange(apiUrl("/test-suites/" + suiteId + "/schedules"), HttpMethod.GET, null, String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
