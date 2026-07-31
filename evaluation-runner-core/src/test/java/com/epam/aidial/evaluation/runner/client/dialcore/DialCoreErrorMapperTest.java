package com.epam.aidial.evaluation.runner.client.dialcore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@DisplayName("DialCoreErrorMapper")
class DialCoreErrorMapperTest {

    @Test
    @DisplayName("maps 401 to BAD_GATEWAY and UPSTREAM_AUTH_ERROR")
    void maps401() {
        assertThat(DialCoreErrorMapper.toHttpStatus(HttpStatusCode.valueOf(401)))
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(DialCoreErrorMapper.toDialCoreErrorCode(HttpStatusCode.valueOf(401)))
                .isEqualTo(DialCoreErrorCode.UPSTREAM_AUTH_ERROR);
    }

    @Test
    @DisplayName("maps 403 to FORBIDDEN and ACCESS_DENIED")
    void maps403() {
        assertThat(DialCoreErrorMapper.toHttpStatus(HttpStatusCode.valueOf(403)))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(DialCoreErrorMapper.toDialCoreErrorCode(HttpStatusCode.valueOf(403)))
                .isEqualTo(DialCoreErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("maps 404 to BAD_GATEWAY and UPSTREAM_NOT_FOUND")
    void maps404() {
        assertThat(DialCoreErrorMapper.toHttpStatus(HttpStatusCode.valueOf(404)))
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(DialCoreErrorMapper.toDialCoreErrorCode(HttpStatusCode.valueOf(404)))
                .isEqualTo(DialCoreErrorCode.UPSTREAM_NOT_FOUND);
    }

    @Test
    @DisplayName("maps other 4xx to BAD_REQUEST and VALIDATION_ERROR")
    void maps4xx() {
        assertThat(DialCoreErrorMapper.toHttpStatus(HttpStatusCode.valueOf(400)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(DialCoreErrorMapper.toDialCoreErrorCode(HttpStatusCode.valueOf(400)))
                .isEqualTo(DialCoreErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("maps 504 to GATEWAY_TIMEOUT and UPSTREAM_TIMEOUT")
    void maps504() {
        assertThat(DialCoreErrorMapper.toHttpStatus(HttpStatusCode.valueOf(504)))
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(DialCoreErrorMapper.toDialCoreErrorCode(HttpStatusCode.valueOf(504)))
                .isEqualTo(DialCoreErrorCode.UPSTREAM_TIMEOUT);
    }

    @Test
    @DisplayName("maps 5xx to BAD_GATEWAY and UPSTREAM_ERROR")
    void maps5xx() {
        assertThat(DialCoreErrorMapper.toHttpStatus(HttpStatusCode.valueOf(502)))
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(DialCoreErrorMapper.toDialCoreErrorCode(HttpStatusCode.valueOf(502)))
                .isEqualTo(DialCoreErrorCode.UPSTREAM_ERROR);
        assertThat(DialCoreErrorMapper.toDialCoreErrorCode(HttpStatusCode.valueOf(503)))
                .isEqualTo(DialCoreErrorCode.UPSTREAM_ERROR);
    }

    @Test
    @DisplayName("maps null status to BAD_GATEWAY and UPSTREAM_ERROR")
    void mapsNull() {
        assertThat(DialCoreErrorMapper.toHttpStatus(null)).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(DialCoreErrorMapper.toDialCoreErrorCode(null)).isEqualTo(DialCoreErrorCode.UPSTREAM_ERROR);
    }
}
