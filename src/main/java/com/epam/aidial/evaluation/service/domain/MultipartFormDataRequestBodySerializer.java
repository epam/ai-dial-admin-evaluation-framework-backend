package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.FormPartType;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedFormPartDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedMultipartBodyDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

@Component
@LogExecution
@RequiredArgsConstructor
public class MultipartFormDataRequestBodySerializer implements RequestBodySerializer {

    private final DialFileClient dialFileClient;
    private final DialFileRefResolver dialFileRefResolver;

    @Override
    public boolean supports(ResolvedBodyDto body) {
        return body instanceof ResolvedMultipartBodyDto;
    }

    @Override
    public SerializedBody serialize(ResolvedBodyDto body) {
        ResolvedMultipartBodyDto multipartBody = (ResolvedMultipartBodyDto) body;
        MultipartBodyBuilder builder = new MultipartBodyBuilder();

        if (multipartBody.getParts() != null) {
            for (ResolvedFormPartDto part : multipartBody.getParts()) {
                if (part.getType() == FormPartType.FILE) {
                    addFilePart(builder, part);
                } else {
                    addTextPart(builder, part);
                }
            }
        }

        MultiValueMap<String, HttpEntity<?>> multipartMap = builder.build();
        return new SerializedBody(MediaType.MULTIPART_FORM_DATA, multipartMap);
    }

    private void addTextPart(MultipartBodyBuilder builder, ResolvedFormPartDto part) {
        String value = part.getResolvedValue() != null ? part.getResolvedValue().toString() : "";
        builder.part(part.getName(), value);
    }

    private void addFilePart(MultipartBodyBuilder builder, ResolvedFormPartDto part) {
        String fileRef =
                part.getResolvedValue() != null ? part.getResolvedValue().toString() : null;
        if (fileRef == null || fileRef.isBlank()) {
            return;
        }

        String realPath = dialFileRefResolver.resolveToRealPath(fileRef);
        byte[] fileBytes = dialFileClient.download(realPath);
        String filename =
                part.getFilename() != null ? part.getFilename() : dialFileRefResolver.extractFilename(fileRef);

        ByteArrayResource resource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        builder.part(part.getName(), resource)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "form-data; name=\"" + part.getName() + "\"; filename=\"" + filename + "\"");
    }
}
