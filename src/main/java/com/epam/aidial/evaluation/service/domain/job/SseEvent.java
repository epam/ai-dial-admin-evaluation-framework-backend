package com.epam.aidial.evaluation.service.domain.job;

/**
 * A parsed SSE event with its type name and data payload.
 *
 * @param event SSE event type name — defaults to {@code "message"} per SSE spec when no
 *              {@code event:} field is present in the event block
 * @param data  parsed {@link com.fasterxml.jackson.databind.JsonNode} if the data payload is valid
 *              JSON, raw {@link String} otherwise
 */
public record SseEvent(String event, Object data) {}
