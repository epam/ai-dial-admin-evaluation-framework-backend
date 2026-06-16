package com.epam.aidial.evaluation.web.handler;

import com.epam.aidial.evaluation.utils.TraceContextUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.Data;
import org.springframework.http.HttpStatus;

/**
 * Standardized API error response (ErrorResponseDto contract).
 * Includes path, method, status, machine-readable code, message, and optional details.
 */
@Data
public class ErrorView {

    private String path;
    private String method;
    private Integer status;
    private String code;
    private String error;
    private String message;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> details;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String traceparent;

    public ErrorView(HttpServletRequest request, HttpStatus status, String errorMessage) {
        this(request, status, null, errorMessage, null);
    }

    public ErrorView(HttpServletRequest request, HttpStatus status, String errorMessage, Map<String, Object> details) {
        this(request, status, null, errorMessage, details);
    }

    public ErrorView(HttpServletRequest request, HttpStatus status, ErrorCode code, String errorMessage) {
        this(request, status, code, errorMessage, null);
    }

    public ErrorView(
            HttpServletRequest request,
            HttpStatus status,
            ErrorCode code,
            String errorMessage,
            Map<String, Object> details) {
        this.path = request.getServletPath();
        this.method = request.getMethod();
        this.status = status.value();
        this.code = code != null ? code.name() : null;
        this.error = status.getReasonPhrase();
        this.message = errorMessage;
        this.details = details;
        this.traceparent = TraceContextUtils.formatTraceParent();
    }
}
