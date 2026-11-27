package com.epam.aidial.evaluation.configuration.properties.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SchedulerProperties")
class SchedulerPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("shouldRejectBlankCallbackUrlWhenEnabled")
    void shouldRejectBlankCallbackUrlWhenEnabled() {
        final SchedulerProperties properties = validProperties();
        properties.setEnabled(true);
        properties.setCallbackBaseUrl(" ");

        final Set<ConstraintViolation<SchedulerProperties>> violations = validator.validate(properties);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("dial.scheduler.callback-base-url must be set when dial.scheduler.enabled=true");
    }

    @Test
    @DisplayName("shouldAcceptBlankCallbackUrlWhenDisabled")
    void shouldAcceptBlankCallbackUrlWhenDisabled() {
        final SchedulerProperties properties = validProperties();
        properties.setEnabled(false);
        properties.setCallbackBaseUrl("");

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("shouldAcceptConfiguredCallbackUrlWhenEnabled")
    void shouldAcceptConfiguredCallbackUrlWhenEnabled() {
        final SchedulerProperties properties = validProperties();
        properties.setEnabled(true);
        properties.setCallbackBaseUrl("http://ef-backend:8080");

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("shouldRejectNonPositiveTimeoutAndBlankIds")
    void shouldRejectNonPositiveTimeoutAndBlankIds() {
        final SchedulerProperties properties = validProperties();
        properties.setTimeoutSeconds(0);
        properties.setAppId("");
        properties.setServiceId(" ");

        final Set<ConstraintViolation<SchedulerProperties>> violations = validator.validate(properties);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("timeoutSeconds", "appId", "serviceId");
    }

    private static SchedulerProperties validProperties() {
        final SchedulerProperties properties = new SchedulerProperties();
        properties.setAppId("public/dial_scheduler__1.0.0");
        properties.setServiceId("dial-native");
        properties.setTimeoutSeconds(120);
        return properties;
    }
}
