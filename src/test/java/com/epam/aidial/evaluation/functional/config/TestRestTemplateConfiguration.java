package com.epam.aidial.evaluation.functional.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

/**
 * Test configuration that ensures {@link StringHttpMessageConverter} handles
 * {@code String.class} responses even when the content-type is {@code application/json}.
 *
 * <p>Jackson 3 / Spring Framework 7 changed the priority/matching logic such that
 * {@code JacksonJsonHttpMessageConverter} now attempts to deserialize {@code application/json}
 * responses to {@code String.class} target types, which fails with {@code MismatchedInputException}
 * when the JSON is an object (not a JSON string value). This configuration restores Jackson 2
 * behaviour where {@code StringHttpMessageConverter} reads the raw response body as a string
 * regardless of content-type.
 *
 * <p>Applied globally to all functional tests via {@code @Import} in {@code PostgresFunctionalTests}.
 */
@TestConfiguration
public class TestRestTemplateConfiguration {

    /**
     * BeanPostProcessor that customizes {@link TestRestTemplate} beans to inject a
     * {@link StringHttpMessageConverter} that accepts {@code application/json}.
     */
    @Bean
    public BeanPostProcessor testRestTemplateCustomizer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof TestRestTemplate testRestTemplate) {
                    RestTemplate restTemplate = testRestTemplate.getRestTemplate();
                    List<HttpMessageConverter<?>> converters = new ArrayList<>(restTemplate.getMessageConverters());

                    // Insert a StringHttpMessageConverter that handles application/json at the front
                    StringHttpMessageConverter stringConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
                    List<MediaType> supportedMediaTypes = new ArrayList<>(stringConverter.getSupportedMediaTypes());
                    supportedMediaTypes.add(MediaType.APPLICATION_JSON);
                    stringConverter.setSupportedMediaTypes(supportedMediaTypes);

                    converters.add(0, stringConverter);
                    restTemplate.setMessageConverters(converters);
                }
                return bean;
            }
        };
    }
}
