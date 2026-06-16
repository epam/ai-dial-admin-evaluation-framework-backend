package com.epam.aidial.evaluation.configuration.properties.logging;

import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.customizable-trace-interceptor")
@RequiredArgsConstructor
@Setter
@Getter
public class CustomizableTraceInterceptorProperties {

    private Map<MessageType, String> messages;

    @RequiredArgsConstructor
    public enum MessageType {
        ENTER,
        EXIT,
        EXCEPTION
    }
}
