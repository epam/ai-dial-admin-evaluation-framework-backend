package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkDeleteRequestDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestCaseBulkDeleteValidatorTest {

    private TestCaseBulkDeleteValidator validator;

    @BeforeEach
    void setUp() {
        TestCaseProperties props = new TestCaseProperties();
        TestCaseProperties.Bulk bulk = new TestCaseProperties.Bulk();
        bulk.setMaxOperations(512);
        bulk.setMaxIdsPerSelector(10000);
        bulk.setMaxItemOperations(500);
        bulk.setMaxDeleteIds(100);
        props.setBulk(bulk);
        validator = new TestCaseBulkDeleteValidator(props);
    }

    @Test
    @DisplayName("Should accept valid list of IDs")
    void shouldAcceptValidIds() {
        TestCaseBulkDeleteRequestDto req = TestCaseBulkDeleteRequestDto.builder()
                .ids(List.of(UUID.randomUUID(), UUID.randomUUID()))
                .build();

        assertThatCode(() -> validator.validate(req)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject null request")
    void shouldRejectNullRequest() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    @DisplayName("Should reject empty ids list")
    void shouldRejectEmptyIds() {
        TestCaseBulkDeleteRequestDto req =
                TestCaseBulkDeleteRequestDto.builder().ids(List.of()).build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    @DisplayName("Should reject when ids count exceeds configured cap")
    void shouldRejectWhenIdsExceedCap() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            ids.add(UUID.randomUUID());
        }
        TestCaseBulkDeleteRequestDto req =
                TestCaseBulkDeleteRequestDto.builder().ids(ids).build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds maximum 100");
    }

    @Test
    @DisplayName("Should reject null element in ids list")
    void shouldRejectNullElementInIds() {
        List<UUID> ids = new ArrayList<>();
        ids.add(UUID.randomUUID());
        ids.add(null);
        TestCaseBulkDeleteRequestDto req =
                TestCaseBulkDeleteRequestDto.builder().ids(ids).build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    @DisplayName("Should reject duplicate UUIDs in ids list")
    void shouldRejectDuplicateIds() {
        UUID id = UUID.randomUUID();
        TestCaseBulkDeleteRequestDto req = TestCaseBulkDeleteRequestDto.builder()
                .ids(List.of(id, UUID.randomUUID(), id))
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("duplicate id");
    }
}
