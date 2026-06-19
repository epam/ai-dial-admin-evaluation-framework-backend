package com.epam.aidial.evaluation.web.filter;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.PayloadTooLargeException;
import com.epam.aidial.evaluation.web.handler.ErrorCode;
import com.epam.aidial.evaluation.web.handler.ErrorView;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@LogExecution
@RequiredArgsConstructor
public class MaxRequestBodyFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // Phase 1: Eager Content-Length check
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBytes) {
            log.warn("Rejecting request: Content-Length {} exceeds limit {}", contentLength, maxBytes);
            writePayloadTooLargeResponse(request, response);
            return;
        }

        // Phase 2: Wrap stream for chunked encoding (no Content-Length)
        if (contentLength < 0) {
            HttpServletRequest wrappedRequest = new ByteCountingRequestWrapper(request, maxBytes);
            filterChain.doFilter(wrappedRequest, response);
        } else {
            filterChain.doFilter(request, response);
        }
    }

    private void writePayloadTooLargeResponse(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ErrorView errorView = new ErrorView(
                request,
                HttpStatus.CONTENT_TOO_LARGE,
                ErrorCode.PAYLOAD_TOO_LARGE,
                "Request body exceeds maximum size of " + maxBytes + " bytes");

        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(errorView));
    }

    private static class ByteCountingRequestWrapper extends HttpServletRequestWrapper {

        private final long maxBytes;

        ByteCountingRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new ByteCountingInputStream(super.getInputStream(), maxBytes);
        }
    }

    private static class ByteCountingInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long bytesRead;

        ByteCountingInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                bytesRead++;
                checkLimit();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = delegate.read(b, off, len);
            if (count > 0) {
                bytesRead += count;
                checkLimit();
            }
            return count;
        }

        private void checkLimit() throws PayloadTooLargeException {
            if (bytesRead > maxBytes) {
                throw new PayloadTooLargeException("Request body exceeds maximum size of " + maxBytes + " bytes");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
