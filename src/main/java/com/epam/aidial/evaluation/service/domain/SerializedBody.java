package com.epam.aidial.evaluation.service.domain;

import org.springframework.http.MediaType;

public record SerializedBody(MediaType contentType, Object body) {}
