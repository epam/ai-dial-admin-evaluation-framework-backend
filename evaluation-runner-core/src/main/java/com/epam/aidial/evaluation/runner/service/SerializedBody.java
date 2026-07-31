package com.epam.aidial.evaluation.runner.service;

import org.springframework.http.MediaType;

public record SerializedBody(MediaType contentType, Object body) {}
