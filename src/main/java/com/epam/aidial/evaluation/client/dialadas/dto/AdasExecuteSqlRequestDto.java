package com.epam.aidial.evaluation.client.dialadas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for dial-adas {@code POST /v1/queries/execute-sql} — a single raw SQL statement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdasExecuteSqlRequestDto {

    @JsonProperty("sql")
    private String sql;
}
