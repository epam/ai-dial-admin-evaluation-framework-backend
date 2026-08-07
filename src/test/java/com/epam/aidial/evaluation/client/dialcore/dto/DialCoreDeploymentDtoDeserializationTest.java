package com.epam.aidial.evaluation.client.dialcore.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DialCoreDeploymentDto polymorphic deserialization")
class DialCoreDeploymentDtoDeserializationTest {

    private static final TypeReference<List<DialCoreDeploymentDto>> DEPLOYMENT_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("GET /v1/deployments array dispatches on the object discriminator to the matching subtype")
    void deploymentsArrayDispatchesToMatchingSubtype() {
        String json = """
                [
                  {
                    "object": "model",
                    "id": "m1",
                    "display_name": "Model 1",
                    "created_at": 1000,
                    "description_keywords": ["fast", "cheap"],
                    "icon_url": "https://icons.example/model.png",
                    "max_retry_attempts": 3,
                    "input_attachment_types": ["image/png"],
                    "interfaces": ["chat", "mcp"]
                  },
                  {
                    "object": "application",
                    "id": "a1",
                    "display_name": "App 1",
                    "created_at": 2000,
                    "description_keywords": ["nlp"],
                    "icon_url": "https://icons.example/app.png",
                    "max_retry_attempts": 2,
                    "input_attachment_types": ["text/plain"],
                    "interfaces": ["chat"]
                  },
                  {
                    "object": "toolset",
                    "id": "t1",
                    "display_name": "Toolset 1",
                    "created_at": 3000,
                    "description_keywords": ["search"],
                    "icon_url": "https://icons.example/toolset.png",
                    "max_retry_attempts": 1,
                    "input_attachment_types": [],
                    "interfaces": ["mcp"]
                  },
                  {
                    "object": "something-new",
                    "id": "u1",
                    "display_name": "Unknown 1",
                    "created_at": 4000,
                    "description_keywords": ["mystery"],
                    "icon_url": "https://icons.example/unknown.png",
                    "max_retry_attempts": 0,
                    "input_attachment_types": ["application/pdf"],
                    "interfaces": ["chat"]
                  }
                ]
                """;

        List<DialCoreDeploymentDto> deployments = objectMapper.readValue(json, DEPLOYMENT_LIST_TYPE);

        assertThat(deployments).hasSize(4);
        assertThat(deployments.get(0)).isInstanceOf(DialCoreModelDto.class);
        assertThat(deployments.get(1)).isInstanceOf(DialCoreApplicationDto.class);
        assertThat(deployments.get(2)).isInstanceOf(DialCoreToolsetDto.class);
        assertThat(deployments.get(3)).isInstanceOf(DialCoreUnknownDeploymentDto.class);

        DialCoreDeploymentDto model = deployments.get(0);
        assertThat(model.getId()).isEqualTo("m1");
        assertThat(model.getDisplayName()).isEqualTo("Model 1");
        assertThat(model.getCreatedAt()).isEqualTo(1000L);
        assertThat(model.getDescriptionKeywords()).containsExactly("fast", "cheap");
        assertThat(model.getIconUrl()).isEqualTo("https://icons.example/model.png");
        assertThat(model.getMaxRetryAttempts()).isEqualTo(3);
        assertThat(model.getInputAttachmentTypes()).containsExactly("image/png");
        assertThat(model.getInterfaces()).containsExactly(InterfaceType.CHAT, InterfaceType.MCP);
        assertThat(model.getObject()).isEqualTo("model");

        DialCoreDeploymentDto unknown = deployments.get(3);
        assertThat(unknown.getObject()).isEqualTo("something-new");
        assertThat(unknown.getId()).isEqualTo("u1");
    }

    @Test
    @DisplayName("subtype-specific fields bind for model, application, and toolset entries")
    void subtypeSpecificFieldsBind() {
        String json = """
                [
                  {
                    "object": "model",
                    "id": "m1",
                    "lifecycle_status": "GA",
                    "capabilities": {
                      "completion": true,
                      "chat_completion": true,
                      "embeddings": false
                    },
                    "limits": {
                      "max_total_tokens": 8192,
                      "max_completion_tokens": 4096
                    },
                    "pricing": {
                      "unit": "token",
                      "prompt": "0.01",
                      "completion": "0.02"
                    }
                  },
                  {
                    "object": "application",
                    "id": "a1",
                    "application_properties": {
                      "temperature": 0.5
                    },
                    "application_type_schema_id": "https://schema.example/app-type"
                  },
                  {
                    "object": "toolset",
                    "id": "t1",
                    "transport": "HTTP",
                    "allowed_tools": ["search", "calculate"]
                  }
                ]
                """;

        List<DialCoreDeploymentDto> deployments = objectMapper.readValue(json, DEPLOYMENT_LIST_TYPE);

        DialCoreModelDto model = (DialCoreModelDto) deployments.get(0);
        assertThat(model.getLifecycleStatus()).isEqualTo("GA");
        assertThat(model.getCapabilities().getCompletion()).isTrue();
        assertThat(model.getCapabilities().getChatCompletion()).isTrue();
        assertThat(model.getCapabilities().getEmbeddings()).isFalse();
        assertThat(model.getLimits().getMaxTotalTokens()).isEqualTo(8192);
        assertThat(model.getLimits().getMaxCompletionTokens()).isEqualTo(4096);
        assertThat(model.getPricing().getUnit()).isEqualTo("token");
        assertThat(model.getPricing().getPrompt()).isEqualTo("0.01");
        assertThat(model.getPricing().getCompletion()).isEqualTo("0.02");

        DialCoreApplicationDto application = (DialCoreApplicationDto) deployments.get(1);
        assertThat(application.getApplicationProperties()).containsEntry("temperature", 0.5);
        assertThat(application.getApplicationTypeSchemaId()).isEqualTo("https://schema.example/app-type");

        DialCoreToolsetDto toolset = (DialCoreToolsetDto) deployments.get(2);
        assertThat(toolset.getTransport()).isEqualTo(DialTransport.HTTP);
        assertThat(toolset.getAllowedTools()).containsExactly("search", "calculate");
    }

    @Test
    @DisplayName("single-object payload deserializes directly to the concrete subtype named by the caller")
    void singleObjectPayloadDeserializesToConcreteSubtype() {
        String json = """
                {
                  "object": "model",
                  "id": "gpt-5",
                  "display_name": "GPT-5",
                  "display_version": "2025-08-07",
                  "owner": "organization-owner"
                }
                """;

        DialCoreModelDto model = objectMapper.readValue(json, DialCoreModelDto.class);

        assertThat(model.getId()).isEqualTo("gpt-5");
        assertThat(model.getDisplayName()).isEqualTo("GPT-5");
        assertThat(model.getDisplayVersion()).isEqualTo("2025-08-07");
        assertThat(model.getOwner()).isEqualTo("organization-owner");
        assertThat(model.getObject()).isEqualTo("model");
    }
}
