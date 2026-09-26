package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Golden-master guard for plain CSV import: every corpus CSV × import mode × starting schema is previewed
 * and imported, and the observable outcome (preview body, import body, persisted test cases, dataset
 * schema) must equal the snapshot recorded from the code before ZIP schema hints existed.
 *
 * <p>Re-record with {@code GOLDEN_RECORD=true ./gradlew :test --rerun --tests '*PostgresFunctionalTests$CsvImportPlainGoldenTests'}.
 * Only re-record for a deliberate plain-CSV behaviour change.
 */
@DisplayName("CSV Import Plain Golden Functional Tests")
public abstract class CsvImportPlainGoldenFunctionalTests extends BaseFunctionalTest {

    private static final String GOLDEN_DIR = "golden/csv-import/";
    private static final Path RECORD_DIR = Path.of("src/test/resources", GOLDEN_DIR);
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final List<String> VOLATILE_KEYS =
            List.of("createdAt", "updatedAt", "createdBy", "updatedBy", "version", "timestamp");
    private static final List<String> MODES = List.of("APPEND", "MERGE", "OVERRIDE");
    private static final String SEED_CSV =
            "testCaseName,prompt,code,count,meta\nSeed,seed prompt,001,5,\"{\"\"a\"\":1}\"";

    private static final Map<String, String> CORPUS = corpus();

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @TestFactory
    @DisplayName("Plain CSV preview/import outcome matches the recorded golden snapshot")
    Stream<DynamicTest> plainCsvImportMatchesGolden() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, String> csv : CORPUS.entrySet()) {
            for (String mode : MODES) {
                for (boolean typed : List.of(false, true)) {
                    String name = csv.getKey() + "-" + mode + "-" + (typed ? "typed" : "empty");
                    tests.add(DynamicTest.dynamicTest(name, () -> runCase(name, csv.getValue(), mode, typed)));
                }
            }
        }
        return tests.stream();
    }

    private void runCase(String name, String csv, String mode, boolean typed) {
        UUID datasetId = typed ? createTypedDatasetWithSeedRow() : createEmptyDataset();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("preview", post(datasetId, "/test-cases/import/preview", csv, mode));
        snapshot.put("import", post(datasetId, "/test-cases/import", csv, mode));
        snapshot.put("testCaseSchema", datasetSchema(datasetId));
        snapshot.put("testCases", testCases(datasetId));

        String actual = normalise(snapshot);
        if (Boolean.parseBoolean(System.getenv("GOLDEN_RECORD"))) {
            record(name, actual);
            return;
        }
        assertThat(actual).as("golden %s", name).isEqualTo(readGolden(name));
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static Map<String, String> corpus() {
        Map<String, String> corpus = new LinkedHashMap<>();
        corpus.put(
                "scalars",
                "testCaseName,prompt,code,count,flag,newNum\n" + "A,hi,007,12,true,3.5\n" + "B,,0042,,false,\n");
        corpus.put(
                "json",
                "testCaseName,meta,tags,newObj,newArr\n"
                        + "A,\"{\"\"k\"\":1}\",\"[1,2]\",\"{\"\"x\"\":\"\"y\"\"}\",\"[\"\"a\"\"]\"\n"
                        + "B,not json,\"[\",{},[]\n");
        corpus.put(
                "multiturn",
                "testCaseName,turnIndex,prompt,reply,note\n" + "Conv,0,hello,hi there,n0\n"
                        + "Conv,1,hello,how are you,n1\n" + "Solo,,one-shot,,\n");
        corpus.put(
                "fileref",
                "testCaseName,attachment,other\n" + "A,files/1/a.png,files/2/b.txt\n"
                        + "B,public/shared/x.png,@ef/datasets/abc/y.txt\n");
        corpus.put("conflict", "testCaseName,prompt,code\n" + "Seed,replaced,010\n" + "New,fresh,\n" + "New,dup,9\n");
        corpus.put("newzeros", "testCaseName,zip,ratio\n" + "A,00123,0.50\n" + "B,98,1\n");
        return corpus;
    }

    private UUID createEmptyDataset() {
        return metaTestDataHelper
                .createDataset("golden-empty-" + UUID.randomUUID())
                .getId();
    }

    private UUID createTypedDatasetWithSeedRow() {
        List<FieldDefinitionDto> schema = List.of(
                field("prompt", SchemaFieldType.STRING, true, null),
                field("code", SchemaFieldType.STRING, false, null),
                field("count", SchemaFieldType.INTEGER, false, null),
                field("meta", SchemaFieldType.OBJECT, false, null),
                field("tags", SchemaFieldType.ARRAY, false, null),
                field("attachment", SchemaFieldType.FILE, false, null),
                field("reply", SchemaFieldType.STRING, false, true));
        Dataset dataset = metaTestDataHelper.createDataset(
                "golden-typed-" + UUID.randomUUID(), objectMapper.writeValueAsString(schema));
        post(dataset.getId(), "/test-cases/import", SEED_CSV, "APPEND");
        return dataset.getId();
    }

    private static FieldDefinitionDto field(String name, SchemaFieldType type, boolean required, Boolean perTurn) {
        return FieldDefinitionDto.builder()
                .name(name)
                .type(type)
                .required(required)
                .perTurn(perTurn)
                .build();
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    private Map<String, Object> post(UUID datasetId, String path, String csv, String mode) {
        URI uri = UriComponentsBuilder.fromUriString(apiUrl("/datasets/" + datasetId + path))
                .queryParam("importMode", mode)
                .queryParam("conflictStrategy", "OVERRIDE")
                .build()
                .toUri();
        ResponseEntity<String> response = restTemplate.postForEntity(uri, multipart(csv), String.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", response.getStatusCode().value());
        result.put("body", parse(response.getBody()));
        return result;
    }

    private Object datasetSchema(UUID datasetId) {
        ResponseEntity<String> response = restTemplate.getForEntity(apiUrl("/datasets/" + datasetId), String.class);
        Object body = parse(response.getBody());
        return body instanceof Map<?, ?> map ? map.get("testCaseSchema") : body;
    }

    @SuppressWarnings("unchecked")
    private Object testCases(UUID datasetId) {
        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId + "/test-cases?size=100"), String.class);
        Object body = parse(response.getBody());
        if (!(body instanceof Map<?, ?> page) || !(page.get("content") instanceof List<?> content)) {
            return body;
        }
        List<Object> sorted = new ArrayList<>(content);
        sorted.sort(Comparator.comparing(tc -> String.valueOf(((Map<String, Object>) tc).get("testCaseName"))));
        return sorted;
    }

    private static HttpEntity<MultiValueMap<String, Object>> multipart(String csv) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "golden.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    // -------------------------------------------------------------------------
    // Snapshot normalisation and storage
    // -------------------------------------------------------------------------

    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return objectMapper.readValue(json, Object.class);
    }

    private String normalise(Object snapshot) {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(stripVolatile(snapshot)) + "\n";
    }

    private static Object stripVolatile(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> {
                if (!VOLATILE_KEYS.contains(String.valueOf(k))) {
                    sorted.put(String.valueOf(k), stripVolatile(v));
                }
            });
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(CsvImportPlainGoldenFunctionalTests::stripVolatile)
                    .toList();
        }
        if (value instanceof String s) {
            return UUID_PATTERN.matcher(s).replaceAll("<uuid>");
        }
        return value;
    }

    private static void record(String name, String content) {
        try {
            Files.createDirectories(RECORD_DIR);
            Files.writeString(RECORD_DIR.resolve(name + ".json"), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to record golden " + name, e);
        }
    }

    private static String readGolden(String name) {
        try (InputStream in = CsvImportPlainGoldenFunctionalTests.class
                .getClassLoader()
                .getResourceAsStream(GOLDEN_DIR + name + ".json")) {
            assertThat(in).as("golden file %s exists", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read golden " + name, e);
        }
    }
}
