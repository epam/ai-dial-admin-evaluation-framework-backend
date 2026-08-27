package com.epam.aidial.evaluation.web.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityRuleException;
import java.sql.SQLException;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("DefaultExceptionHandler — private-binding guard backstop")
class DefaultExceptionHandlerTest {

    private static final String JOOQ_MESSAGE = "SQL [update \"test_suites\" set \"dataset_id\" = ? where \"id\" = ?]; "
            + "ERROR: PRIVATE_DATASET_ALREADY_BOUND";

    private final DefaultExceptionHandler handler = new DefaultExceptionHandler();

    @Test
    @DisplayName("jOOQ DataAccessException carrying SQL state P0001 becomes 409 PRIVATE_DATASET_ALREADY_BOUND"
            + " with no SQL in the message")
    void jooqPrivateBindingViolationBecomes409() {
        DataAccessException ex = new DataAccessException(
                JOOQ_MESSAGE, new SQLException("ERROR: PRIVATE_DATASET_ALREADY_BOUND", "P0001"));

        ResponseEntity<ErrorView> response = handler.handleGeneralError(request(), ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.PRIVATE_DATASET_ALREADY_BOUND.name());
        assertThat(response.getBody().getMessage())
                .isEqualTo(DatasetVisibilityRuleException.PRIVATE_DATASET_ALREADY_BOUND_MESSAGE)
                .doesNotContain("SQL [", "update \"test_suites\"");
    }

    @Test
    @DisplayName("An unrelated exception still yields 500 INTERNAL_ERROR")
    void unrelatedExceptionStillYields500() {
        ResponseEntity<ErrorView> response = handler.handleGeneralError(request(), new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
        assertThat(response.getBody().getMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("Spring DataAccessException with SQL state P0001 becomes 409 without echoing the DB message")
    void springPrivateBindingViolationBecomes409() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                JOOQ_MESSAGE, new SQLException("ERROR: PRIVATE_DATASET_ALREADY_BOUND", "P0001"));

        ResponseEntity<ErrorView> response = handler.handleDataAccessException(request(), ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo(DatasetVisibilityRuleException.PRIVATE_DATASET_ALREADY_BOUND_MESSAGE);
    }

    @Test
    @DisplayName("A non-P0001 DataAccessException is rethrown so the 23505 → UNIQUE_CONSTRAINT_VIOLATION mapping"
            + " stays untouched")
    void unrelatedDataAccessExceptionIsRethrown() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("dup", new SQLException("duplicate key", "23505"));

        assertThatThrownBy(() -> handler.handleDataAccessException(request(), ex))
                .isSameAs(ex);
    }

    @Test
    @DisplayName("P0001 from an unrelated RAISE (PL/pgSQL's default errcode) is not mislabelled as a binding conflict")
    void unrelatedP0001StillYields500() {
        DataAccessException ex = new DataAccessException(
                "SQL [select 1]; ERROR: something else went wrong",
                new SQLException("ERROR: something else went wrong", "P0001"));

        ResponseEntity<ErrorView> response = handler.handleGeneralError(request(), ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/test-suites/1");
        request.setServletPath("/api/v1/test-suites/1");
        return request;
    }
}
