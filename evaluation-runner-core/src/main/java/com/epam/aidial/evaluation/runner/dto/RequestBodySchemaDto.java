package com.epam.aidial.evaluation.runner.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "contentType", include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = JsonRequestBodySchemaDto.class, name = "application/json"),
    @JsonSubTypes.Type(value = MultipartFormDataRequestBodySchemaDto.class, name = "multipart/form-data"),
    @JsonSubTypes.Type(value = UrlEncodedFormRequestBodySchemaDto.class, name = "application/x-www-form-urlencoded")
})
public abstract class RequestBodySchemaDto {

    public abstract String getContentType();
}
