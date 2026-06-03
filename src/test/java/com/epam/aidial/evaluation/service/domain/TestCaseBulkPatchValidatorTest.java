package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkOperationDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkPatchRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkSelectorDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseItemOperationDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestCaseBulkPatchValidatorTest {

    private TestCaseBulkPatchValidator validator;

    @BeforeEach
    void setUp() {
        TestCaseProperties props = new TestCaseProperties();
        TestCaseProperties.Bulk bulk = new TestCaseProperties.Bulk();
        bulk.setMaxOperations(512);
        bulk.setMaxIdsPerSelector(10000);
        bulk.setMaxItemOperations(500);
        props.setBulk(bulk);
        validator = new TestCaseBulkPatchValidator(props);
    }

    @Test
    @DisplayName("Should accept valid composite request with bulk + item operations")
    void shouldAcceptValidComposite() {
        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .bulkOperations(List.of(bulkOpRename(List.of(UUID.randomUUID()))))
                .itemOperations(List.of(itemOp(UUID.randomUUID(), Map.of("testCaseName", "X"))))
                .build();

        assertThatCode(() -> validator.validate(req)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject when both arrays are empty")
    void shouldRejectWhenBothArraysEmpty() {
        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .bulkOperations(List.of())
                .itemOperations(List.of())
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must be non-empty");
    }

    @Test
    @DisplayName("Should reject when request body is null")
    void shouldRejectWhenRequestNull() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Should reject when combined op count exceeds max-operations")
    void shouldRejectWhenCombinedOpCountExceedsMax() {
        TestCaseProperties.Bulk caps = new TestCaseProperties.Bulk();
        caps.setMaxOperations(3);
        caps.setMaxIdsPerSelector(10000);
        caps.setMaxItemOperations(2);
        TestCaseProperties props = new TestCaseProperties();
        props.setBulk(caps);
        validator = new TestCaseBulkPatchValidator(props);

        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .bulkOperations(
                        List.of(bulkOpRename(List.of(UUID.randomUUID())), bulkOpRename(List.of(UUID.randomUUID()))))
                .itemOperations(List.of(
                        itemOp(UUID.randomUUID(), Map.of("enabled", true)),
                        itemOp(UUID.randomUUID(), Map.of("enabled", true))))
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds maximum 3");
    }

    @Test
    @DisplayName("Should reject when itemOperations exceeds max-item-operations")
    void shouldRejectWhenItemOpsExceedMax() {
        TestCaseProperties.Bulk caps = new TestCaseProperties.Bulk();
        caps.setMaxOperations(512);
        caps.setMaxIdsPerSelector(10000);
        caps.setMaxItemOperations(1);
        TestCaseProperties props = new TestCaseProperties();
        props.setBulk(caps);
        validator = new TestCaseBulkPatchValidator(props);

        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .itemOperations(List.of(
                        itemOp(UUID.randomUUID(), Map.of("enabled", true)),
                        itemOp(UUID.randomUUID(), Map.of("enabled", true))))
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("itemOperations size 2 exceeds maximum 1");
    }

    @Test
    @DisplayName("Should reject selector that declares both ids and filter")
    void shouldRejectSelectorWithBothVariants() {
        TestCaseBulkOperationDto op = TestCaseBulkOperationDto.builder()
                .selector(TestCaseBulkSelectorDto.builder()
                        .ids(List.of(UUID.randomUUID()))
                        .filter(List.of("enabled:eq:true"))
                        .build())
                .patch(Map.of("enabled", false))
                .build();
        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .bulkOperations(List.of(op))
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exactly one of `ids` or `filter`");
    }

    @Test
    @DisplayName("Should reject selector that declares neither ids nor filter")
    void shouldRejectSelectorWithNeitherVariant() {
        TestCaseBulkOperationDto op = TestCaseBulkOperationDto.builder()
                .selector(TestCaseBulkSelectorDto.builder().build())
                .patch(Map.of("enabled", false))
                .build();
        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .bulkOperations(List.of(op))
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exactly one of `ids` or `filter`");
    }

    @Test
    @DisplayName("Should reject when selector.ids exceeds max-ids-per-selector")
    void shouldRejectWhenSelectorIdsExceedMax() {
        TestCaseProperties.Bulk caps = new TestCaseProperties.Bulk();
        caps.setMaxOperations(512);
        caps.setMaxIdsPerSelector(2);
        caps.setMaxItemOperations(500);
        TestCaseProperties props = new TestCaseProperties();
        props.setBulk(caps);
        validator = new TestCaseBulkPatchValidator(props);

        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .bulkOperations(List.of(bulkOpRename(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))))
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds maximum 2");
    }

    @Test
    @DisplayName("Should reject duplicate ids inside a single selector.ids")
    void shouldRejectDuplicateIdsInSelector() {
        UUID id = UUID.randomUUID();
        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .bulkOperations(List.of(bulkOpRename(List.of(id, id))))
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("duplicate id");
    }

    @Test
    @DisplayName("Should reject bulk patch with non-whitelisted field")
    void shouldRejectBulkPatchWithNonWhitelistedField() {
        TestCaseBulkOperationDto op = TestCaseBulkOperationDto.builder()
                .selector(TestCaseBulkSelectorDto.builder().filter(List.of()).build())
                .patch(Map.of("isValid", true))
                .build();
        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .bulkOperations(List.of(op))
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("non-whitelisted field 'isValid'");
    }

    @Test
    @DisplayName("Should reject duplicate id within itemOperations")
    void shouldRejectDuplicateIdInItemOperations() {
        UUID id = UUID.randomUUID();
        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .itemOperations(
                        List.of(itemOp(id, Map.of("testCaseName", "A")), itemOp(id, Map.of("testCaseName", "B"))))
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("duplicate id");
    }

    @Test
    @DisplayName("Should reject empty bulk patch object")
    void shouldRejectEmptyBulkPatchMap() {
        TestCaseBulkOperationDto op = TestCaseBulkOperationDto.builder()
                .selector(TestCaseBulkSelectorDto.builder().filter(List.of()).build())
                .patch(Map.of())
                .build();
        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .bulkOperations(List.of(op))
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("patch must not be empty");
    }

    @Test
    @DisplayName("Should reject item op with null id")
    void shouldRejectItemOpWithNullId() {
        TestCaseItemOperationDto op = TestCaseItemOperationDto.builder()
                .id(null)
                .patch(Map.of("enabled", true))
                .build();
        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .itemOperations(List.of(op))
                .build();

        assertThatThrownBy(() -> validator.validate(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("id is required");
    }

    @Test
    @DisplayName("Should accept same id appearing across bulk selector and itemOperations (last-writer-wins)")
    void shouldAcceptSameIdAcrossBulkAndItem() {
        UUID id = UUID.randomUUID();
        TestCaseBulkPatchRequestDto req = TestCaseBulkPatchRequestDto.builder()
                .bulkOperations(List.of(bulkOpRename(List.of(id))))
                .itemOperations(List.of(itemOp(id, Map.of("enabled", true))))
                .build();

        assertThatCode(() -> validator.validate(req)).doesNotThrowAnyException();
    }

    private static TestCaseBulkOperationDto bulkOpRename(List<UUID> ids) {
        return TestCaseBulkOperationDto.builder()
                .selector(TestCaseBulkSelectorDto.builder().ids(ids).build())
                .patch(Map.of("testCaseName", "renamed"))
                .build();
    }

    private static TestCaseItemOperationDto itemOp(UUID id, Map<String, Object> patch) {
        return TestCaseItemOperationDto.builder().id(id).patch(patch).build();
    }
}
