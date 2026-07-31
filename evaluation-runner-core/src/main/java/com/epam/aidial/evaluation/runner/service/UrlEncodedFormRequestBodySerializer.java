package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedUrlEncodedBodyDto;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Component
@LogExecution
public class UrlEncodedFormRequestBodySerializer implements RequestBodySerializer {

    @Override
    public boolean supports(ResolvedBodyDto body) {
        return body instanceof ResolvedUrlEncodedBodyDto;
    }

    @Override
    public SerializedBody serialize(ResolvedBodyDto body) {
        ResolvedUrlEncodedBodyDto urlEncodedBody = (ResolvedUrlEncodedBodyDto) body;
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();

        if (urlEncodedBody.getEntries() != null) {
            for (KeyValueTemplateDto entry : urlEncodedBody.getEntries()) {
                String value = entry.getValue() != null ? entry.getValue() : "";
                formData.add(entry.getKey(), value);
            }
        }

        return new SerializedBody(MediaType.APPLICATION_FORM_URLENCODED, formData);
    }
}
