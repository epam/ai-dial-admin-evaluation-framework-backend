package com.epam.aidial.evaluation.client.dialcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.client.dialcore.dto.DialFileMetadataDto;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("DialFileClient")
class DialFileClientTest {

    private MockRestServiceServer server;
    private DialFileClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DialFileClient(builder.build());
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("should upload file and return metadata")
        void shouldUploadFileAndReturnMetadata() {
            String json = """
                    {"name":"data.csv","parentPath":"bucket/suites/abc",\
                    "bucket":"bucket","url":"/v1/files/bucket/suites/abc/data.csv",\
                    "contentLength":100,"contentType":"text/csv","createdAt":1000,"updatedAt":2000}
                    """;
            server.expect(requestTo("/v1/files/bucket/suites/abc/data.csv"))
                    .andExpect(method(HttpMethod.PUT))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            byte[] content = "file content".getBytes(StandardCharsets.UTF_8);
            DialFileMetadataDto result = client.upload(
                    "bucket/suites/abc/data.csv", new ByteArrayInputStream(content), "data.csv", "text/csv");

            assertThat(result.getName()).isEqualTo("data.csv");
            assertThat(result.getContentLength()).isEqualTo(100);
            assertThat(result.getContentType()).isEqualTo("text/csv");
            server.verify();
        }

        @Test
        @DisplayName("should throw DialCoreClientException on upload error")
        void shouldThrowOnUploadError() {
            server.expect(requestTo("/v1/files/bucket/suites/abc/data.csv"))
                    .andExpect(method(HttpMethod.PUT))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("Server error"));

            byte[] content = "data".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> client.upload(
                            "bucket/suites/abc/data.csv", new ByteArrayInputStream(content), "data.csv", "text/csv"))
                    .isInstanceOf(DialCoreClientException.class);
            server.verify();
        }
    }

    @Nested
    @DisplayName("downloadTo")
    class DownloadTo {

        @Test
        @DisplayName("should stream file content to output stream")
        void shouldStreamFileContent() {
            byte[] expected = "file bytes here".getBytes(StandardCharsets.UTF_8);
            server.expect(requestTo("/v1/files/bucket/suites/abc/data.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(expected, MediaType.APPLICATION_OCTET_STREAM));

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            client.downloadTo("bucket/suites/abc/data.csv", output);

            assertThat(output.toByteArray()).isEqualTo(expected);
            server.verify();
        }

        @Test
        @DisplayName("should throw on 404 download")
        void shouldThrowOn404() {
            server.expect(requestTo("/v1/files/bucket/suites/abc/missing.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).body("Not found"));

            ByteArrayOutputStream output = new ByteArrayOutputStream();

            assertThatThrownBy(() -> client.downloadTo("bucket/suites/abc/missing.csv", output))
                    .isInstanceOf(DialCoreClientException.class);
            server.verify();
        }
    }

    @Nested
    @DisplayName("download")
    class Download {

        @Test
        @DisplayName("should return file bytes")
        void shouldReturnFileBytes() {
            byte[] expected = "binary data".getBytes(StandardCharsets.UTF_8);
            server.expect(requestTo("/v1/files/bucket/suites/abc/file.bin"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(expected, MediaType.APPLICATION_OCTET_STREAM));

            byte[] result = client.download("bucket/suites/abc/file.bin");

            assertThat(result).isEqualTo(expected);
            server.verify();
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("should delete file successfully")
        void shouldDeleteFile() {
            server.expect(requestTo("/v1/files/bucket/suites/abc/data.csv"))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withSuccess());

            client.delete("bucket/suites/abc/data.csv");

            server.verify();
        }

        @Test
        @DisplayName("should throw on delete error")
        void shouldThrowOnDeleteError() {
            server.expect(requestTo("/v1/files/bucket/suites/abc/missing.csv"))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).body("Not found"));

            assertThatThrownBy(() -> client.delete("bucket/suites/abc/missing.csv"))
                    .isInstanceOf(DialCoreClientException.class);
            server.verify();
        }
    }

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Test
        @DisplayName("should return file metadata")
        void shouldReturnMetadata() {
            String json = """
                    {"name":"data.csv","parentPath":"bucket/suites/abc",\
                    "bucket":"bucket","url":"/v1/files/bucket/suites/abc/data.csv",\
                    "contentLength":500,"contentType":"text/csv","createdAt":1000,"updatedAt":2000}
                    """;
            server.expect(requestTo("/v1/metadata/files/bucket/suites/abc/data.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            DialFileMetadataDto result = client.metadata("bucket/suites/abc/data.csv");

            assertThat(result.getName()).isEqualTo("data.csv");
            assertThat(result.getContentLength()).isEqualTo(500);
            server.verify();
        }

        @Test
        @DisplayName("should throw on 404 metadata")
        void shouldThrowOn404() {
            server.expect(requestTo("/v1/metadata/files/bucket/suites/abc/missing.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).body("Not found"));

            assertThatThrownBy(() -> client.metadata("bucket/suites/abc/missing.csv"))
                    .isInstanceOf(DialCoreClientException.class);
            server.verify();
        }
    }

    @Nested
    @DisplayName("list")
    class ListFiles {

        @Test
        @DisplayName("should return folder items")
        void shouldReturnFolderItems() {
            String json = """
                    {"name":"abc","parentPath":"bucket/suites","bucket":"bucket",\
                    "url":"/v1/files/bucket/suites/abc/",\
                    "items":[{"name":"data.csv","parentPath":"bucket/suites/abc",\
                    "bucket":"bucket","url":"/v1/files/bucket/suites/abc/data.csv",\
                    "contentLength":100,"contentType":"text/csv","createdAt":1000,"updatedAt":2000}]}
                    """;
            server.expect(requestTo("/v1/metadata/files/bucket/suites/abc/"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            var result = client.list("bucket/suites/abc/");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("data.csv");
            server.verify();
        }

        @Test
        @DisplayName("should return empty list on 404")
        void shouldReturnEmptyListOn404() {
            server.expect(requestTo("/v1/metadata/files/bucket/suites/abc/"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).body("Not found"));

            var result = client.list("bucket/suites/abc/");

            assertThat(result).isEmpty();
            server.verify();
        }

        @Test
        @DisplayName("should return empty list when items is null")
        void shouldReturnEmptyListWhenItemsNull() {
            String json = """
                    {"name":"abc","parentPath":"bucket/suites","bucket":"bucket",\
                    "url":"/v1/files/bucket/suites/abc/"}
                    """;
            server.expect(requestTo("/v1/metadata/files/bucket/suites/abc/"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            var result = client.list("bucket/suites/abc/");

            assertThat(result).isEmpty();
            server.verify();
        }
    }

    @Nested
    @DisplayName("exists")
    class Exists {

        @Test
        @DisplayName("should return true when file exists")
        void shouldReturnTrueWhenExists() {
            String json = """
                    {"name":"data.csv","parentPath":"bucket/suites/abc",\
                    "bucket":"bucket","url":"/v1/files/bucket/suites/abc/data.csv",\
                    "contentLength":100,"contentType":"text/csv","createdAt":1000,"updatedAt":2000}
                    """;
            server.expect(requestTo("/v1/metadata/files/bucket/suites/abc/data.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            boolean result = client.exists("bucket/suites/abc/data.csv");

            assertThat(result).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("should return false on 404")
        void shouldReturnFalseOn404() {
            server.expect(requestTo("/v1/metadata/files/bucket/suites/abc/missing.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).body("Not found"));

            boolean result = client.exists("bucket/suites/abc/missing.csv");

            assertThat(result).isFalse();
            server.verify();
        }

        @Test
        @DisplayName("should throw on non-404 error")
        void shouldThrowOnNon404Error() {
            server.expect(requestTo("/v1/metadata/files/bucket/suites/abc/data.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("Server error"));

            assertThatThrownBy(() -> client.exists("bucket/suites/abc/data.csv"))
                    .isInstanceOf(DialCoreClientException.class);
            server.verify();
        }
    }

    @Nested
    @DisplayName("getBucket")
    class GetBucket {

        @Test
        @DisplayName("should discover and cache bucket")
        void shouldDiscoverAndCacheBucket() {
            String json = """
                    {"bucket":"ef-bucket-123"}
                    """;
            server.expect(requestTo("/v1/bucket"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            String bucket = client.getBucket();

            assertThat(bucket).isEqualTo("ef-bucket-123");
            server.verify();
        }

        @Test
        @DisplayName("should return cached bucket on subsequent calls")
        void shouldReturnCachedBucket() {
            String json = """
                    {"bucket":"ef-bucket-123"}
                    """;
            server.expect(requestTo("/v1/bucket"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            String first = client.getBucket();
            String second = client.getBucket();

            assertThat(first).isEqualTo("ef-bucket-123");
            assertThat(second).isEqualTo("ef-bucket-123");
            server.verify();
        }

        @Test
        @DisplayName("should throw on empty bucket response")
        void shouldThrowOnEmptyBucket() {
            String json = """
                    {"bucket":""}
                    """;
            server.expect(requestTo("/v1/bucket"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.getBucket())
                    .isInstanceOf(DialCoreClientException.class)
                    .hasMessageContaining("empty bucket name");
            server.verify();
        }

        @Test
        @DisplayName("should throw on DIAL Core unavailable and not cache failure")
        void shouldThrowOnUnavailableAndNotCacheFailure() {
            server.expect(requestTo("/v1/bucket"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("Unavailable"));

            assertThatThrownBy(() -> client.getBucket()).isInstanceOf(DialCoreClientException.class);

            // Second call should retry (not return cached failure)
            String json = """
                    {"bucket":"ef-bucket-456"}
                    """;
            server.reset();
            server.expect(requestTo("/v1/bucket"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            String bucket = client.getBucket();
            assertThat(bucket).isEqualTo("ef-bucket-456");
            server.verify();
        }
    }
}
