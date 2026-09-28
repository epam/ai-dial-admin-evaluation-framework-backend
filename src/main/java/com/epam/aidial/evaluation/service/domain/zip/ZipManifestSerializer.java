package com.epam.aidial.evaluation.service.domain.zip;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes and reads {@link ZipManifest} to/from {@code manifest.json}.
 *
 * <p>Writing is fail-fast (throws on failure): export must never produce a truncated or missing manifest.
 * Reading rejects anything the import cannot safely act on — invalid JSON, a structure that does not match
 * {@link ZipManifest}, or an unsupported {@link ZipManifest#formatVersion()} — with a {@link
 * ValidationException} (mapped to HTTP 400), since a caller-supplied manifest is untrusted input, unlike the
 * regenerable data {@code ValidationWarningsSerializer} degrades gracefully on. Each {@code testCaseSchema}
 * entry is further checked with the injected {@link Validator} against {@link FieldDefinitionDto}'s own
 * {@code @NotBlank}/{@code @Pattern}/{@code @Size}/{@code @NotNull} constraints — reusing them here (per
 * AGENTS.md) rather than duplicating field-shape checks — plus a null-entry check and a duplicate-name
 * check the annotations alone can't express.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ZipManifestSerializer {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    /**
     * Serializes the manifest to JSON bytes.
     *
     * @throws IllegalStateException if serialization fails
     */
    public byte[] write(ZipManifest manifest) {
        try {
            return objectMapper.writeValueAsBytes(manifest);
        } catch (JacksonException e) {
            log.error("Failed to serialize ZIP manifest: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to serialize ZIP manifest", e);
        }
    }

    /**
     * Reads the manifest from JSON bytes.
     *
     * @throws ValidationException if the content is not valid JSON, does not match the manifest structure,
     *     declares an unsupported {@code formatVersion}, {@code testCaseSchema} holds a null entry, a field
     *     violating {@link FieldDefinitionDto}'s own constraints, or a duplicate field name, or {@code files}
     *     holds a null entry or one with a null/blank {@code path}
     */
    public ZipManifest read(byte[] json) {
        final ZipManifest manifest;
        try {
            manifest = objectMapper.readValue(json, ZipManifest.class);
        } catch (JacksonException e) {
            throw new ValidationException("Invalid manifest.json: " + e.getMessage());
        }
        if (manifest == null) {
            throw new ValidationException("Invalid manifest.json: content is empty");
        }
        if (manifest.testCaseSchema() == null) {
            throw new ValidationException("Invalid manifest.json: testCaseSchema is required");
        }
        if (manifest.formatVersion() != ZipManifest.CURRENT_FORMAT_VERSION) {
            throw new ValidationException("Unsupported manifest.json formatVersion: " + manifest.formatVersion());
        }
        validateFields(manifest.testCaseSchema());
        validateFiles(manifest.files());
        return manifest;
    }

    private void validateFields(List<FieldDefinitionDto> fields) {
        Set<String> seenNames = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            FieldDefinitionDto field = fields.get(i);
            if (field == null) {
                throw new ValidationException("Invalid manifest.json: testCaseSchema[" + i + "] must not be null");
            }
            Set<ConstraintViolation<FieldDefinitionDto>> violations = validator.validate(field);
            if (!violations.isEmpty()) {
                ConstraintViolation<FieldDefinitionDto> violation =
                        violations.iterator().next();
                String fieldLabel = field.getName() != null ? field.getName() : "testCaseSchema[" + i + "]";
                throw new ValidationException("Invalid manifest.json field '" + fieldLabel + "': "
                        + violation.getPropertyPath() + " " + violation.getMessage());
            }
            if (!seenNames.add(field.getName())) {
                throw new ValidationException(
                        "Invalid manifest.json: duplicate testCaseSchema field name '" + field.getName() + "'");
            }
        }
    }

    /**
     * Rejects a null {@code files[]} entry, or one with a null/blank {@code path} — either would otherwise
     * reach {@code ZipImportService.manifestSourceRefByPath} (which reads {@code entry.path()} without a
     * null check) as a null-pointer failure (HTTP 500) instead of a 400 naming the bad input.
     */
    private void validateFiles(List<ZipManifest.FileEntry> files) {
        for (int i = 0; i < files.size(); i++) {
            ZipManifest.FileEntry entry = files.get(i);
            if (entry == null) {
                throw new ValidationException("Invalid manifest.json: files[" + i + "] must not be null");
            }
            if (entry.path() == null || entry.path().isBlank()) {
                throw new ValidationException("Invalid manifest.json: files[" + i + "].path must not be blank");
            }
        }
    }
}
