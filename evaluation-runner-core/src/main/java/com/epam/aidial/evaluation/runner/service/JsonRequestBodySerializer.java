package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class JsonRequestBodySerializer implements RequestBodySerializer {

    @Override
    public boolean supports(ResolvedBodyDto body) {
        return body instanceof ResolvedJsonBodyDto;
    }

    @Override
    public SerializedBody serialize(ResolvedBodyDto body) {
        ResolvedJsonBodyDto jsonBody = (ResolvedJsonBodyDto) body;
        return new SerializedBody(MediaType.APPLICATION_JSON, jsonBody.getContent());
    }
}
