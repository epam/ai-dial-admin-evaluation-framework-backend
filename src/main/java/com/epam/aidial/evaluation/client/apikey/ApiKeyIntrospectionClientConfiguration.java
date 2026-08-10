package com.epam.aidial.evaluation.client.apikey;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration;
import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
@LogExecution
@ConditionalOnProperty(value = "config.rest.security.api-key.enabled", havingValue = "true")
public class ApiKeyIntrospectionClientConfiguration {

    @Bean("apiKeyIntrospectionRestClient")
    public RestClient apiKeyIntrospectionRestClient(ApiKeyProperties properties, OpenTelemetry openTelemetry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getRequestTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getRequestTimeoutMs()));

        return RestClient.builder()
                .baseUrl(properties.getCoreUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(openTelemetry))
                .configureMessageConverters(
                        configurer -> configurer.withJsonConverter(jsonConverterAcceptingOctetStream()))
                .build();
    }

    /**
     * DIAL Core's {@code /v1/user/info} returns a JSON body but labels it {@code application/octet-stream},
     * so the default JSON converter refuses to parse it. Widen its supported media types to accept that
     * content type too, mirroring the same workaround used for DIAL Core's file endpoints elsewhere.
     */
    private static JacksonJsonHttpMessageConverter jsonConverterAcceptingOctetStream() {
        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter();
        List<MediaType> types = new ArrayList<>(converter.getSupportedMediaTypes());
        types.add(MediaType.APPLICATION_OCTET_STREAM);
        converter.setSupportedMediaTypes(types);
        return converter;
    }
}
