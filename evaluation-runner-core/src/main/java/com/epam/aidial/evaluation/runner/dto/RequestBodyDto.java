package com.epam.aidial.evaluation.runner.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "contentType", include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = JsonRequestBodyDto.class, name = "application/json"),
    @JsonSubTypes.Type(value = MultipartFormDataRequestBodyDto.class, name = "multipart/form-data"),
    @JsonSubTypes.Type(value = UrlEncodedFormRequestBodyDto.class, name = "application/x-www-form-urlencoded")
})
public abstract class RequestBodyDto {

    public abstract String getContentType();
}
