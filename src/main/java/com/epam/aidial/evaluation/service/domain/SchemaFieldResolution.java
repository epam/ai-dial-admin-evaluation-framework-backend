package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import java.util.List;
import java.util.Map;

/**
 * Result of resolving ids for an incoming dataset schema.
 *
 * @param schema incoming fields (copies), each carrying an id
 * @param renames old field name to new field name
 * @param removedNames names of current fields that no incoming entry resolved to
 */
public record SchemaFieldResolution(
        List<FieldDefinitionDto> schema, Map<String, String> renames, List<String> removedNames) {}
