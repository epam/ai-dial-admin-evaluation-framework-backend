package com.epam.aidial.evaluation.client.dialcore;

import com.epam.aidial.evaluation.client.dialcore.dto.DialBucketResponseDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialFileFolderResponseDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialFileMetadataDto;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

@Slf4j
@Component
@LogExecution
public class DialFileClient {

    private static final String FILES_PATH = "/v1/files/";
    private static final String METADATA_PATH = "/v1/metadata/files/";
    private static final String BUCKET_PATH = "/v1/bucket";

    @Qualifier("dialFileRestClient")
    private final RestClient restClient;

    private final AtomicReference<String> cachedBucket = new AtomicReference<>();

    public DialFileClient(@Qualifier("dialFileRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public DialFileMetadataDto upload(String path, InputStream content, String filename, String contentType) {
        try {
            byte[] bytes = content.readAllBytes();
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
            MediaType mediaType =
                    contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM;
            bodyBuilder.part("file", resource, mediaType);

            return restClient
                    .put()
                    .uri(FILES_PATH + encodePath(path))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(bodyBuilder.build())
                    .retrieve()
                    .body(DialFileMetadataDto.class);
        } catch (IOException e) {
            throw new DialCoreClientException(HttpStatusCode.valueOf(500), "Failed to read file content for upload", e);
        } catch (RestClientResponseException e) {
            throw mapException(e);
        }
    }

    public void downloadTo(String path, OutputStream target) {
        try {
            restClient.get().uri(FILES_PATH + encodePath(path)).exchange((request, response) -> {
                if (response.getStatusCode().isError()) {
                    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new DialCoreClientException(response.getStatusCode(), "Download failed", body);
                }
                response.getBody().transferTo(target);
                return null;
            });
        } catch (DialCoreClientException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw mapException(e);
        }
    }

    public byte[] download(String path) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        downloadTo(path, baos);
        return baos.toByteArray();
    }

    public void delete(String path) {
        try {
            restClient.delete().uri(FILES_PATH + encodePath(path)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw mapException(e);
        }
    }

    public DialFileMetadataDto metadata(String path) {
        try {
            return restClient
                    .get()
                    .uri(METADATA_PATH + encodePath(path))
                    .retrieve()
                    .body(DialFileMetadataDto.class);
        } catch (RestClientResponseException e) {
            throw mapException(e);
        }
    }

    public List<DialFileMetadataDto> list(String folderPath) {
        try {
            DialFileFolderResponseDto folder = restClient
                    .get()
                    .uri(METADATA_PATH + encodePath(folderPath))
                    .retrieve()
                    .body(DialFileFolderResponseDto.class);
            return folder != null && folder.getItems() != null ? folder.getItems() : List.of();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return List.of();
            }
            throw mapException(e);
        }
    }

    public boolean exists(String path) {
        try {
            metadata(path);
            return true;
        } catch (DialCoreClientException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw e;
        }
    }

    public String getBucket() {
        String bucket = cachedBucket.get();
        if (bucket != null) {
            return bucket;
        }
        try {
            DialBucketResponseDto response =
                    restClient.get().uri(BUCKET_PATH).retrieve().body(DialBucketResponseDto.class);
            if (response == null
                    || response.getBucket() == null
                    || response.getBucket().isBlank()) {
                throw new DialCoreClientException(HttpStatusCode.valueOf(502), "DIAL Core returned empty bucket name");
            }
            cachedBucket.set(response.getBucket());
            log.info("Discovered EF bucket: {}", response.getBucket());
            return response.getBucket();
        } catch (RestClientResponseException e) {
            throw new DialCoreClientException(
                    e.getStatusCode(), "Failed to discover EF bucket from DIAL Core", e.getResponseBodyAsString());
        }
    }

    private static String encodePath(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }

    private static DialCoreClientException mapException(RestClientResponseException e) {
        return new DialCoreClientException(e.getStatusCode(), e.getMessage(), e.getResponseBodyAsString());
    }
}
