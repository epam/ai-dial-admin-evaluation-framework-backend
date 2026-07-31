package com.epam.aidial.evaluation.runner.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "contentType", include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ResolvedJsonBodyDto.class, name = "application/json"),
    @JsonSubTypes.Type(value = ResolvedMultipartBodyDto.class, name = "multipart/form-data"),
    @JsonSubTypes.Type(value = ResolvedUrlEncodedBodyDto.class, name = "application/x-www-form-urlencoded")
})
public abstract class ResolvedBodyDto {

    public abstract String getContentType();
}
