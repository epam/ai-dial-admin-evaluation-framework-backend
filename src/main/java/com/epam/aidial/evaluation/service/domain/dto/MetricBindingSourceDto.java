package com.epam.aidial.evaluation.service.domain.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "$type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TestCaseBindingSourceDto.class, name = "TestCase"),
    @JsonSubTypes.Type(value = ResponseBindingSourceDto.class, name = "Response"),
    @JsonSubTypes.Type(value = ConstantBindingSourceDto.class, name = "Constant")
})
public abstract class MetricBindingSourceDto {}
