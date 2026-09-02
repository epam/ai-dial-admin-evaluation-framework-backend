package com.epam.aidial.evaluation.functional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClient;
import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.client.dialcore.dto.DialFileMetadataDto;
import com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Outbound-integration mocking shared by every vendor-specific functional test entry point
 * ({@link PostgresFunctionalTests}, {@link ClickHouseFunctionalTests}). Holds the {@code @MockitoBean}
 * replacements for the DIAL Core / metric-provider / MCP clients plus an in-memory {@link DialFileClient}
 * fake, so that the vendor classes differ only in datasource wiring.
 */
abstract class DialClientMockingFunctionalTests extends FunctionalTests {

    @MockitoBean
    private DialCoreClient dialCoreClient;

    @MockitoBean
    private DialCoreDeploymentInvoker dialCoreDeploymentInvoker;

    @MockitoBean
    private McpToolInvoker mcpToolInvoker;

    @MockitoBean
    private MetricProviderClient metricProviderClient;

    @MockitoBean
    private DialAdasClient dialAdasClient;

    @MockitoBean
    private DialFileClient dialFileClient;

    private final Map<String, byte[]> dialFileStore = new ConcurrentHashMap<>();
    private final Map<String, DialFileMetadataDto> dialMetadataStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUpDialFileClientMock() {
        dialFileStore.clear();
        dialMetadataStore.clear();
        reset(dialFileClient);

        when(dialFileClient.getBucket()).thenReturn("test-bucket");

        when(dialFileClient.upload(anyString(), any(InputStream.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String path = inv.getArgument(0);
                    InputStream content = inv.getArgument(1);
                    String filename = inv.getArgument(2);
                    String contentType = inv.getArgument(3);
                    byte[] bytes = content.readAllBytes();
                    dialFileStore.put(path, bytes);
                    DialFileMetadataDto meta = DialFileMetadataDto.builder()
                            .name(filename)
                            .contentLength((long) bytes.length)
                            .contentType(contentType)
                            .build();
                    dialMetadataStore.put(path, meta);
                    return meta;
                });

        when(dialFileClient.download(anyString())).thenAnswer(inv -> {
            String path = inv.getArgument(0);
            byte[] bytes = dialFileStore.get(path);
            if (bytes == null) {
                throw new DialCoreClientException(HttpStatusCode.valueOf(404), "Not found", "File not found");
            }
            return bytes;
        });

        doAnswer(inv -> {
                    String path = inv.getArgument(0);
                    OutputStream target = inv.getArgument(1);
                    byte[] bytes = dialFileStore.get(path);
                    if (bytes == null) {
                        throw new DialCoreClientException(HttpStatusCode.valueOf(404), "Not found", "File not found");
                    }
                    target.write(bytes);
                    return null;
                })
                .when(dialFileClient)
                .downloadTo(anyString(), any(OutputStream.class));

        doAnswer(inv -> {
                    String path = inv.getArgument(0);
                    if (!dialFileStore.containsKey(path)) {
                        throw new DialCoreClientException(HttpStatusCode.valueOf(404), "Not found", "File not found");
                    }
                    dialFileStore.remove(path);
                    dialMetadataStore.remove(path);
                    return null;
                })
                .when(dialFileClient)
                .delete(anyString());

        when(dialFileClient.metadata(anyString())).thenAnswer(inv -> {
            String path = inv.getArgument(0);
            DialFileMetadataDto meta = dialMetadataStore.get(path);
            if (meta == null) {
                throw new DialCoreClientException(HttpStatusCode.valueOf(404), "Not found", "File not found");
            }
            return meta;
        });

        when(dialFileClient.exists(anyString())).thenAnswer(inv -> dialFileStore.containsKey(inv.getArgument(0)));

        when(dialFileClient.list(anyString())).thenAnswer(inv -> {
            String folderPath = inv.getArgument(0);
            return dialMetadataStore.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(folderPath))
                    .map(Map.Entry::getValue)
                    .toList();
        });
    }
}
