package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FormPartDto;
import com.epam.aidial.evaluation.runner.dto.FormPartType;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("Suite validation — file ref format validation at save time")
public abstract class SuiteValidationFileRefFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Test
    @DisplayName("Suite with valid FormPartDto FILE value produces no file-ref warning")
    void suiteWithValidFileFormPartValue_noWarning() {
        UUID suiteId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        String validRef = "@ef/suites/" + suiteId + "/document.pdf";

        TestSuiteRequestDto request = buildSuiteWithMultipartFilePart(validRef);

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        List<ValidationWarningDto> fileRefWarnings = response.getBody().getValidationWarnings() == null
                ? List.of()
                : response.getBody().getValidationWarnings().stream()
                        .filter(w -> w.getMessage() != null
                                && (w.getMessage().contains("FILE form part")
                                        || w.getMessage().contains("file ref")))
                        .toList();
        assertThat(fileRefWarnings).isEmpty();
    }

    @Test
    @DisplayName("Suite with invalid FormPartDto FILE value produces TYPE warning")
    void suiteWithInvalidFileFormPartValue_producesWarning() {
        String invalidRef = "files/@ef/suites/abc/old-format.pdf";

        TestSuiteRequestDto request = buildSuiteWithMultipartFilePart(invalidRef);

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isValid()).isFalse();
        List<ValidationWarningDto> fileRefWarnings = response.getBody().getValidationWarnings().stream()
                .filter(w -> w.getMessage() != null && w.getMessage().contains("FILE form part"))
                .toList();
        assertThat(fileRefWarnings).isNotEmpty();
    }

    @Test
    @DisplayName("Suite with path traversal in FormPartDto FILE value produces TYPE warning")
    void suiteWithPathTraversalInFileFormPartValue_producesWarning() {
        String invalidRef = "public/../etc/passwd";

        TestSuiteRequestDto request = buildSuiteWithMultipartFilePart(invalidRef);

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isValid()).isFalse();
        List<ValidationWarningDto> fileRefWarnings = response.getBody().getValidationWarnings().stream()
                .filter(w -> w.getMessage() != null && w.getMessage().contains("FILE form part"))
                .toList();
        assertThat(fileRefWarnings).isNotEmpty();
    }

    @Test
    @DisplayName("Suite with valid |file constant binding produces no file-ref warning")
    void suiteWithValidFileConstantBinding_noWarning() {
        String validRef = "public/shared/input.csv";

        TestSuiteRequestDto request = buildSuiteWithFileConstantBinding(validRef);

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        List<ValidationWarningDto> fileRefWarnings = response.getBody().getValidationWarnings() == null
                ? List.of()
                : response.getBody().getValidationWarnings().stream()
                        .filter(w -> w.getMessage() != null && w.getMessage().contains("Constant binding"))
                        .toList();
        assertThat(fileRefWarnings).isEmpty();
    }

    @Test
    @DisplayName("Suite with old-format |file constant binding produces TYPE warning")
    void suiteWithOldFormatFileConstantBinding_producesWarning() {
        String invalidRef = "files/@ef/suites/abc/old-format.pdf";

        TestSuiteRequestDto request = buildSuiteWithFileConstantBinding(invalidRef);

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isValid()).isFalse();
        List<ValidationWarningDto> fileRefWarnings = response.getBody().getValidationWarnings().stream()
                .filter(w -> w.getMessage() != null && w.getMessage().contains("Constant binding"))
                .toList();
        assertThat(fileRefWarnings).isNotEmpty();
    }

    private UUID newDataset() {
        return metaTestDataHelper
                .createDataset("ds-fileref-" + UUID.randomUUID())
                .getId();
    }

    private TestSuiteRequestDto buildSuiteWithMultipartFilePart(String fileValue) {
        return TestSuiteRequestDto.builder()
                .name("Suite-file-part-" + UUID.randomUUID())
                .datasetId(newDataset())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/upload")
                        .requestBodySchema(
                                MultipartFormDataRequestBodySchemaDto.builder().build())
                        .build())
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/upload")
                        .body(MultipartFormDataRequestBodyDto.builder()
                                .content(List.of(FormPartDto.builder()
                                        .name("attachment")
                                        .type(FormPartType.FILE)
                                        .value(fileValue)
                                        .build()))
                                .build())
                        .build())
                .build();
    }

    private TestSuiteRequestDto buildSuiteWithFileConstantBinding(String constantValue) {
        return TestSuiteRequestDto.builder()
                .name("Suite-file-binding-" + UUID.randomUUID())
                .datasetId(newDataset())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(java.util.Map.of("type", "object"))
                                .build())
                        .build())
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content(java.util.Map.of("attachment", "${{doc|file}}"))
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("doc")
                        .constantValue(constantValue)
                        .build()))
                .build();
    }
}
