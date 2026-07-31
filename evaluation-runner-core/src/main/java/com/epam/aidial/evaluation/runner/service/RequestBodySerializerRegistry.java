package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ResolvedBodyDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@LogExecution
@RequiredArgsConstructor
public class RequestBodySerializerRegistry {

    private final List<RequestBodySerializer> serializers;

    public SerializedBody serialize(ResolvedBodyDto body) {
        if (body == null) {
            return null;
        }
        for (RequestBodySerializer serializer : serializers) {
            if (serializer.supports(body)) {
                return serializer.serialize(body);
            }
        }
        throw new IllegalStateException(
                "No serializer found for body type: " + body.getClass().getSimpleName());
    }
}
