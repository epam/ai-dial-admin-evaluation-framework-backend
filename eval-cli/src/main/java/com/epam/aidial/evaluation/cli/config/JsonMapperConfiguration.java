package com.epam.aidial.evaluation.cli.config;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.jackson.HttpMethodDeserializer;
import com.epam.aidial.evaluation.runner.util.jackson.HttpMethodSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Provides this module's own {@code ObjectMapper}/{@code JsonMapper} bean.
 *
 * <p>Spring Boot's Jackson autoconfiguration does not activate in a non-web context
 * ({@code spring.main.web-application-type=none}), so this module — unlike the EF backend, a web
 * application — must declare its own {@link JsonMapper} bean rather than relying on autoconfiguration.
 * Mirrors the EF backend's {@code JsonMapperConfiguration} settings (case-insensitive enums, tolerant
 * deserialization, {@code NON_NULL} inclusion, the shared {@code HttpMethod} (de)serializer required by
 * {@code evaluation-runner-core}'s {@code EndpointContractDto}) for parity, since eval-cli consumes the
 * same request/response JSON contracts.
 */
@Configuration
@LogExecution
public class JsonMapperConfiguration {

    @Bean
    @Primary
    public JsonMapper objectMapper() {
        final SimpleModule httpMethodModule = new SimpleModule();
        httpMethodModule.addSerializer(HttpMethod.class, new HttpMethodSerializer());
        httpMethodModule.addDeserializer(HttpMethod.class, new HttpMethodDeserializer());

        return JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .changeDefaultPropertyInclusion(
                        v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .addModule(httpMethodModule)
                .build();
    }
}
