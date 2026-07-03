package com.epam.aidial.evaluation.service.domain.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "$type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TestCaseBindingSourceDto.class, name = "TestCase"),
    @JsonSubTypes.Type(value = ResponseBindingSourceDto.class, name = "Response"),
    @JsonSubTypes.Type(value = ConstantBindingSourceDto.class, name = "Constant")
})
@Schema(
        description = "Polymorphic binding source. Discriminated by $type.",
        discriminatorProperty = "$type",
        subTypes = {TestCaseBindingSourceDto.class, ResponseBindingSourceDto.class, ConstantBindingSourceDto.class})
public sealed interface MetricBindingSourceDto
        permits ConstantBindingSourceDto, ResponseBindingSourceDto, TestCaseBindingSourceDto {}
