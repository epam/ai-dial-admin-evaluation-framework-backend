package com.epam.aidial.evaluation.functional.tests;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Base class for functional tests providing common utilities.
 */
public abstract class BaseFunctionalTest {

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    protected String baseUrl() {
        // Spring Boot 4: Retrieve local.server.port dynamically from Environment instead of @LocalServerPort
        // field injection, which fails in doubly-nested test classes with
        // "Failed to convert '${local.server.port}' to int".
        String portStr = environment.getProperty("local.server.port");
        int port = portStr != null ? Integer.parseInt(portStr) : 8080;
        return "http://localhost:" + port;
    }

    protected String apiUrl(String path) {
        return baseUrl() + "/api/v1" + path;
    }

    protected <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
