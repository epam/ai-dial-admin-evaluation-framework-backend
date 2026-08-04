package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Functional tests for the flat CSV multiplication of multi-turn cases: export emits one row per turn
 * (turnIndex {@code 0..N-1}; blank for single-turn), import assembles a contiguous run of same-named rows
 * carrying a turnIndex into one {@code multiTurnData} case, round-trips losslessly, and reports
 * non-contiguous names / duplicate turnIndex as conflicts.
 */
@DisplayName("Multi-turn CSV Functional Tests")
public abstract class MultiTurnCsvFunctionalTests extends AbstractMultiTurnFunctionalTest {

    private UUID promptDataset() {
        return newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("prompt")
                .type(SchemaFieldType.STRING)
                .required(true)
                .perTurn(true)
                .build()));
    }

    @Test
    @DisplayName("Export emits one row per turn (turnIndex 0..N-1) and a blank turnIndex for single-turn")
    void exportMultipliesTurns() throws IOException {
        UUID datasetId = promptDataset();
        createMultiTurnCase(datasetId, "mt", List.of(Map.of("prompt", "a"), Map.of("prompt", "b")));
        createSingleTurnCase(datasetId, "st", Map.of("prompt", "c"));

        String csv = exportCsv(datasetId);
        List<CSVRecord> records = parseCsv(csv);

        // 3 data rows: 2 for the multi-turn case + 1 for the single-turn case.
        assertThat(records).hasSize(3);
        assertThat(records.get(0).isMapped("turnIndex")).isTrue();

        List<CSVRecord> mtRows =
                records.stream().filter(r -> "mt".equals(r.get("testCaseName"))).toList();
        assertThat(mtRows).hasSize(2);
        assertThat(mtRows.stream().map(r -> r.get("turnIndex"))).containsExactlyInAnyOrder("0", "1");
        assertThat(mtRows.stream().map(r -> r.get("prompt"))).containsExactlyInAnyOrder("a", "b");

        CSVRecord stRow = records.stream()
                .filter(r -> "st".equals(r.get("testCaseName")))
                .findFirst()
                .orElseThrow();
        assertThat(stRow.get("turnIndex")).isEmpty();
        assertThat(stRow.get("prompt")).isEqualTo("c");
    }

    @Test
    @DisplayName("Import assembles a contiguous run of same-named rows with turnIndex into one multi-turn case")
    void importAssemblesMultiTurn() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt\nconv,0,hello\nconv,1,world";

        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(2);
        assertThat(response.getBody().getValidCount()).isEqualTo(1);
        assertThat(response.getBody().getInvalidCount()).isEqualTo(0);

        List<TestCaseResponseDto> cases = listTestCases(datasetId);
        assertThat(cases).hasSize(1);
        TestCaseResponseDto conv = cases.get(0);
        assertThat(conv.getTestCaseName()).isEqualTo("conv");
        assertThat(conv.getMultiTurnData()).hasSize(2);
        assertThat(conv.getMultiTurnData().get(0).get("prompt")).isEqualTo("hello");
        assertThat(conv.getMultiTurnData().get(1).get("prompt")).isEqualTo("world");
        assertThat(conv.getData()).isNullOrEmpty();
    }

    @Test
    @DisplayName("Export then import round-trips a multi-turn case preserving its turns")
    void roundTripPreservesTurns() {
        UUID sourceDataset = promptDataset();
        createMultiTurnCase(sourceDataset, "conv", List.of(Map.of("prompt", "first"), Map.of("prompt", "second")));

        String csv = exportCsv(sourceDataset);

        UUID targetDataset = promptDataset();
        ResponseEntity<CsvImportResultDto> importResponse = importCsv(targetDataset, csv, null);
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(importResponse.getBody().getTotalRows()).isEqualTo(2);
        assertThat(importResponse.getBody().getValidCount()).isEqualTo(1);

        List<TestCaseResponseDto> cases = listTestCases(targetDataset);
        assertThat(cases).hasSize(1);
        TestCaseResponseDto conv = cases.get(0);
        assertThat(conv.getMultiTurnData()).hasSize(2);
        assertThat(conv.getMultiTurnData().stream().map(t -> t.get("prompt"))).containsExactly("first", "second");
    }

    @Test
    @DisplayName("Duplicate turnIndex within a case is marked invalid with a conflict warning")
    void duplicateTurnIndexRejected() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt\ndup,0,a\ndup,0,b";

        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getInvalidCount()).isEqualTo(1);
        assertThat(response.getBody().getWarnings().stream().map(w -> w.getMessage()))
                .anyMatch(msg -> msg.contains("Duplicate turnIndex"));
    }

    @Test
    @DisplayName("A name reappearing non-contiguously is reported as a conflict warning")
    void nonContiguousNameRejected() {
        UUID datasetId = promptDataset();
        // "conv" turn 0, then a different case, then "conv" turn 1 — the second "conv" run is non-contiguous.
        String csv = "testCaseName,turnIndex,prompt\nconv,0,a\nother,0,x\nconv,1,b";

        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, "SKIP");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getWarnings().stream().map(w -> w.getMessage()))
                .anyMatch(msg -> msg.contains("non-contiguously"));
    }

    // -------------------- Helpers --------------------

    private String exportCsv(UUID datasetId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.parseMediaType("text/csv; charset=UTF-8")));
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases/export.csv"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ResponseEntity<CsvImportResultDto> importCsv(UUID datasetId, String csv, String conflictStrategy) {
        String url = "/datasets/" + datasetId + "/test-cases/import?delimiter=,"
                + (conflictStrategy != null ? "&conflictStrategy=" + conflictStrategy : "");
        return restTemplate.postForEntity(apiUrl(url), multipartFileEntity(csv), CsvImportResultDto.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartFileEntity(String csvContent) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csvContent.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "multi-turn.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    private List<TestCaseResponseDto> listTestCases(UUID datasetId) {
        ResponseEntity<PageResponseDto<TestCaseResponseDto>> list = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(list.getBody()).isNotNull();
        return list.getBody().getContent();
    }

    private List<CSVRecord> parseCsv(String csvContent) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get();
        try (CSVParser parser = CSVParser.builder()
                .setReader(new StringReader(csvContent))
                .setFormat(format)
                .get()) {
            return parser.getRecords();
        } catch (UncheckedIOException e) {
            throw new IOException(e.getCause());
        }
    }
}
