package com.epam.aidial.evaluation.mcp.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.mcp.model.ModelDetailsMcpDto.ModelCapabilitiesMcpDto;
import com.epam.aidial.evaluation.mcp.model.ModelDetailsMcpDto.ModelLimitsMcpDto;
import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the wire representation of the MCP-owned deployment shapes (D-F8) against the shared
 * mapper configuration: enum wire values on the wire (never Java constant names), explicit JSON
 * nulls omitted, and {@code fromWireValue} accepting every wire value while rejecting constant
 * names.
 */
@DisplayName("Deployment MCP DTOs (JSON contract)")
class DeploymentMcpDtoJsonTest {

    private final JsonMapper objectMapper = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

    @Test
    @DisplayName("DeploymentKind serializes to its wire value, not its constant name")
    void deploymentKindSerializesToWireValue() {
        assertThat(objectMapper.writeValueAsString(DeploymentKind.DIAL_TOOLSET)).isEqualTo("\"dial-toolset\"");
    }

    @Test
    @DisplayName("DeploymentInterface serializes to its wire value, not its constant name")
    void deploymentInterfaceSerializesToWireValue() {
        assertThat(objectMapper.writeValueAsString(DeploymentInterface.OPEN_AI_CHAT_COMPLETIONS))
                .isEqualTo("\"openaiChatCompletions\"");
    }

    @Test
    @DisplayName("DeploymentKind.fromWireValue() accepts every wire value")
    void deploymentKindFromWireValueAcceptsEveryWireValue() {
        for (DeploymentKind kind : DeploymentKind.values()) {
            assertThat(DeploymentKind.fromWireValue(kind.getWireValue())).isEqualTo(kind);
        }
    }

    @Test
    @DisplayName("DeploymentKind.fromWireValue() rejects a Java constant name")
    void deploymentKindFromWireValueRejectsConstantName() {
        assertThatThrownBy(() -> DeploymentKind.fromWireValue("DIAL_MODEL"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("dial-model")
                .hasMessageContaining("dial-application")
                .hasMessageContaining("dial-toolset");
    }

    @Test
    @DisplayName("DeploymentInterface.fromWireValue() accepts every wire value")
    void deploymentInterfaceFromWireValueAcceptsEveryWireValue() {
        for (DeploymentInterface value : DeploymentInterface.values()) {
            assertThat(DeploymentInterface.fromWireValue(value.getWireValue())).isEqualTo(value);
        }
    }

    @Test
    @DisplayName("DeploymentInterface.fromWireValue() rejects a Java constant name")
    void deploymentInterfaceFromWireValueRejectsConstantName() {
        assertThatThrownBy(() -> DeploymentInterface.fromWireValue("OPEN_AI_CHAT_COMPLETIONS"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("openaiChatCompletions");
    }

    @Test
    @DisplayName("DeploymentSummaryMcpDto omits null-valued fields from the JSON")
    void deploymentSummaryMcpDtoOmitsNullFields() {
        DeploymentSummaryMcpDto summary =
                new DeploymentSummaryMcpDto("gpt-5-mini", DeploymentKind.DIAL_MODEL, "GPT-5 mini", null, null, null);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(summary));

        assertThat(node.get("deploymentId").asString()).isEqualTo("gpt-5-mini");
        assertThat(node.get("type").asString()).isEqualTo("dial-model");
        assertThat(node.get("displayName").asString()).isEqualTo("GPT-5 mini");
        assertThat(node.has("description")).isFalse();
        assertThat(node.has("interfaces")).isFalse();
        assertThat(node.has("toolsetTransport")).isFalse();
    }

    @Test
    @DisplayName("DeploymentSummaryMcpDto's toolsetTransport serializes by wire value")
    void deploymentSummaryMcpDtoToolsetTransportSerializesByWireValue() {
        DeploymentSummaryMcpDto summary = new DeploymentSummaryMcpDto(
                "my-toolset", DeploymentKind.DIAL_TOOLSET, "My Toolset", null, null, McpTransport.STREAMABLE_HTTP);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(summary));

        assertThat(node.get("toolsetTransport").asString()).isEqualTo("streamable-http");
    }

    @Test
    @DisplayName("DeploymentMcpDto's model detail nests limits and capabilities and omits toolset/application")
    void deploymentMcpDtoModelDetailNestsLimitsAndCapabilities() {
        ModelDetailsMcpDto model = new ModelDetailsMcpDto(
                new ModelLimitsMcpDto(32000, 4096), new ModelCapabilitiesMcpDto(true, false, false));
        DeploymentMcpDto detail = new DeploymentMcpDto(
                "gpt-5-mini",
                DeploymentKind.DIAL_MODEL,
                "GPT-5 mini",
                "2025-08-07",
                "description",
                "organization-owner",
                List.of(DeploymentInterface.CHAT),
                null,
                1768856213216L,
                1768856213216L,
                model,
                null,
                null);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(detail));

        assertThat(node.get("model").get("limits").get("maxTotalTokens").asInt())
                .isEqualTo(32000);
        assertThat(node.get("model").get("limits").get("maxCompletionTokens").asInt())
                .isEqualTo(4096);
        assertThat(node.get("model").get("capabilities").get("chatCompletion").asBoolean())
                .isTrue();
        assertThat(node.get("interfaces").get(0).asString()).isEqualTo("chat");
        assertThat(node.has("toolset")).isFalse();
        assertThat(node.has("application")).isFalse();
        assertThat(node.has("features")).isFalse();
    }

    @Test
    @DisplayName("DeploymentMcpDto's features map omits a null-valued entry under the shared NON_NULL mapper")
    void deploymentMcpDtoFeaturesMapOmitsNullValuedEntry() {
        Map<String, Object> features = new HashMap<>();
        features.put("rateLimitPerMinute", 60);
        features.put("hiddenFeature", null);
        DeploymentMcpDto detail = new DeploymentMcpDto(
                "gpt-5-mini",
                DeploymentKind.DIAL_MODEL,
                "GPT-5 mini",
                null,
                null,
                null,
                null,
                features,
                null,
                null,
                null,
                null,
                null);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(detail));

        assertThat(node.get("features").has("rateLimitPerMinute")).isTrue();
        assertThat(node.get("features").has("hiddenFeature")).isFalse();
    }

    @Test
    @DisplayName("DeploymentListMcpDto carries the summaries and their count")
    void deploymentListMcpDtoCarriesSummariesAndCount() {
        DeploymentSummaryMcpDto summary =
                new DeploymentSummaryMcpDto("m1", DeploymentKind.DIAL_MODEL, "Model 1", null, null, null);
        DeploymentListMcpDto list = new DeploymentListMcpDto(List.of(summary), 1);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(list));

        assertThat(node.get("total").asInt()).isEqualTo(1);
        assertThat(node.get("deployments")).hasSize(1);
    }
}
