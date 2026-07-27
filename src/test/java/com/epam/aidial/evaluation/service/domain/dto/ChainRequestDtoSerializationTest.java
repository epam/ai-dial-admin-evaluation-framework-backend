package com.epam.aidial.evaluation.service.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ChainRequestDto polymorphic serialization")
class ChainRequestDtoSerializationTest {

    private static final TypeReference<List<ChainRequestDto>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper = JsonMapperConfiguration.createJsonMapper();

    @Test
    @DisplayName("an HTTP element round-trips with all of its fields preserved")
    void httpElementRoundTrips() {
        HttpChainRequestDto element = new HttpChainRequestDto();
        element.setLabel("invoke");
        element.setEndpointRef(EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern("/chat/completions")
                .build());
        element.setRequestTemplate(
                RequestTemplateDto.builder().urlTemplate("/chat/completions").build());
        element.setInputBindings(List.of(InputBindingDto.builder()
                .templateVariable("session")
                .responseField("session_id")
                .build()));
        element.setResponseColumns(List.of(ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("choices[0].message.content")
                .build()));

        String json = objectMapper.writeValueAsString(element);
        ChainRequestDto parsed = objectMapper.readValue(json, ChainRequestDto.class);

        assertThat(parsed).isInstanceOf(HttpChainRequestDto.class);
        assertThat(parsed.getType()).isEqualTo(ChainRequestType.HTTP);
        assertThat(parsed.getLabel()).isEqualTo("invoke");
        assertThat(parsed.getEndpointRef().getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(parsed.getRequestTemplate().getUrlTemplate()).isEqualTo("/chat/completions");
        assertThat(parsed.getInputBindings())
                .singleElement()
                .satisfies(binding -> assertThat(binding.getResponseField()).isEqualTo("session_id"));
        assertThat(parsed.getResponseColumns())
                .extracting(ResponseColumnDefinitionDto::getName)
                .containsExactly("answer");
    }

    @Test
    @DisplayName("the type discriminator is written out so a persisted chain deserializes to the same subtype")
    void typeDiscriminatorIsSerialized() {
        HttpChainRequestDto element = new HttpChainRequestDto();
        element.setLabel("a");

        assertThat(objectMapper.writeValueAsString(element)).contains("\"type\":\"HTTP\"");
    }

    @Test
    @DisplayName("an absent type deserializes as HTTP, so the common case needs no discriminator")
    void absentTypeDefaultsToHttp() {
        ChainRequestDto parsed = objectMapper.readValue("{\"label\":\"invoke\"}", ChainRequestDto.class);

        assertThat(parsed).isInstanceOf(HttpChainRequestDto.class);
        assertThat(parsed.getType()).isEqualTo(ChainRequestType.HTTP);
    }

    @Test
    @DisplayName("an MCP_TOOL element deserializes to its own subtype so save-time validation can reject it")
    void mcpToolElementDeserializes() {
        ChainRequestDto parsed =
                objectMapper.readValue("{\"type\":\"MCP_TOOL\",\"label\":\"tool\"}", ChainRequestDto.class);

        assertThat(parsed).isInstanceOf(McpToolChainRequestDto.class);
        assertThat(parsed.getType()).isEqualTo(ChainRequestType.MCP_TOOL);
    }

    @Test
    @DisplayName("a mixed array round-trips preserving per-element types and order")
    void mixedArrayRoundTrips() {
        HttpChainRequestDto http = new HttpChainRequestDto();
        http.setLabel("http");
        McpToolChainRequestDto mcp = new McpToolChainRequestDto();
        mcp.setLabel("mcp");

        String json = objectMapper.writeValueAsString(List.of(http, mcp));
        List<ChainRequestDto> parsed = objectMapper.readValue(json, LIST_TYPE);

        assertThat(parsed)
                .extracting(ChainRequestDto::getType)
                .containsExactly(ChainRequestType.HTTP, ChainRequestType.MCP_TOOL);
        assertThat(parsed).extracting(ChainRequestDto::getLabel).containsExactly("http", "mcp");
    }
}
