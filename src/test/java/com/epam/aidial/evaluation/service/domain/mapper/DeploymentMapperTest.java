package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreAttachmentPathsDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreCapabilitiesDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreLimitsDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCorePricingDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreRouteDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreRouteResponseDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreRouteUpstreamDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreSchemaAttachmentPathsDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreSchemaRouteDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreSchemaRouteResponseDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreSchemaRouteUpstreamDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreToolsetDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ApplicationRouteDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialApplicationInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialModelInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolsetInfoDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeploymentMapper")
class DeploymentMapperTest {

    private DeploymentMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new DeploymentMapperImpl();
    }

    @Test
    @DisplayName("maps DialCoreModelDto to DialModelInfoDto")
    void mapsModelToDialModelInfoDto() {
        DialCoreModelDto source = DialCoreModelDto.builder()
                .id("gpt-5-mini")
                .displayName("GPT-5 mini")
                .displayVersion("2025-08-07")
                .description("A model")
                .owner("org-owner")
                .createdAt(1768856213216L)
                .updatedAt(1768856213216L)
                .descriptionKeywords(List.of("Text", "Gen"))
                .inputAttachmentTypes(List.of("*/*"))
                .capabilities(DialCoreCapabilitiesDto.builder()
                        .scaleTypes(List.of("standard"))
                        .chatCompletion(true)
                        .build())
                .limits(DialCoreLimitsDto.builder()
                        .maxTotalTokens(128000)
                        .maxCompletionTokens(4096)
                        .build())
                .pricing(DialCorePricingDto.builder()
                        .unit("token")
                        .prompt("0.15")
                        .completion("0.60")
                        .build())
                .build();

        DialModelInfoDto result = mapper.toDialModelInfoDto(source);

        assertThat(result).isNotNull();
        assertThat(result.getDeploymentId()).isEqualTo("gpt-5-mini");
        assertThat(result.getDisplayName()).isEqualTo("GPT-5 mini");
        assertThat(result.getVersion()).isEqualTo("2025-08-07");
        assertThat(result.getOwner()).isEqualTo("org-owner");
        assertThat(result.getCreatedAt()).isEqualTo(1768856213216L);
        assertThat(result.getUpdatedAt()).isEqualTo(1768856213216L);
        assertThat(result.getDescriptionKeywords()).containsExactly("Text", "Gen");
        assertThat(result.getInputAttachmentTypes()).containsExactly("*/*");
        assertThat(result.getCapabilities()).isNotNull();
        assertThat(result.getCapabilities().getScaleTypes()).containsExactly("standard");
        assertThat(result.getCapabilities().getChatCompletion()).isTrue();
        assertThat(result.getLimits().getMaxTotalTokens()).isEqualTo(128000);
        assertThat(result.getPricing().getUnit()).isEqualTo("token");
    }

    @Test
    @DisplayName("maps DialCoreModelDto to DialModelInfoDto with object display name")
    void mapsModelToDialModelInfoDtoWithObjectDisplayName() {
        DialCoreModelDto source = DialCoreModelDto.builder()
                .id("gpt-5-mini")
                .displayName(Map.of("en", "hello", "fr", "bonjur"))
                .displayVersion("2025-08-07")
                .description(Map.of("en", "description", "fr", "le description"))
                .owner("org-owner")
                .createdAt(1768856213216L)
                .updatedAt(1768856213216L)
                .descriptionKeywords(List.of("Text", "Gen"))
                .inputAttachmentTypes(List.of("*/*"))
                .capabilities(DialCoreCapabilitiesDto.builder()
                        .scaleTypes(List.of("standard"))
                        .chatCompletion(true)
                        .build())
                .limits(DialCoreLimitsDto.builder()
                        .maxTotalTokens(128000)
                        .maxCompletionTokens(4096)
                        .build())
                .pricing(DialCorePricingDto.builder()
                        .unit("token")
                        .prompt("0.15")
                        .completion("0.60")
                        .build())
                .build();

        DialModelInfoDto result = mapper.toDialModelInfoDto(source);

        assertThat(result).isNotNull();
        assertThat(result.getDeploymentId()).isEqualTo("gpt-5-mini");
        assertThat(result.getDisplayName()).isEqualTo("hello");
        assertThat(result.getDescription()).isEqualTo("description");
        assertThat(result.getVersion()).isEqualTo("2025-08-07");
        assertThat(result.getOwner()).isEqualTo("org-owner");
        assertThat(result.getCreatedAt()).isEqualTo(1768856213216L);
        assertThat(result.getUpdatedAt()).isEqualTo(1768856213216L);
        assertThat(result.getDescriptionKeywords()).containsExactly("Text", "Gen");
        assertThat(result.getInputAttachmentTypes()).containsExactly("*/*");
        assertThat(result.getCapabilities()).isNotNull();
        assertThat(result.getCapabilities().getScaleTypes()).containsExactly("standard");
        assertThat(result.getCapabilities().getChatCompletion()).isTrue();
        assertThat(result.getLimits().getMaxTotalTokens()).isEqualTo(128000);
        assertThat(result.getPricing().getUnit()).isEqualTo("token");
    }

    @Test
    @DisplayName("maps DialCoreApplicationDto to DialApplicationInfoDto with routes")
    void mapsApplicationToDialApplicationInfoDtoWithRoutes() {
        DialCoreRouteDto route = DialCoreRouteDto.builder()
                .name("v1")
                .rewritePath(true)
                .paths(List.of("/v1/.*"))
                .methods(List.of("GET"))
                .upstreams(List.of(DialCoreRouteUpstreamDto.builder()
                        .endpoint("http://upstream")
                        .weight(1)
                        .tier(0)
                        .build()))
                .attachmentPaths(DialCoreAttachmentPathsDto.builder()
                        .requestBody(List.of())
                        .responseBody(List.of())
                        .build())
                .build();
        DialCoreApplicationDto source = DialCoreApplicationDto.builder()
                .id("EntityExtractor")
                .displayName("Entity Extractor")
                .owner("org-owner")
                .createdAt(1769192823293L)
                .updatedAt(1769194293867L)
                .applicationTypeSchemaId("https://schema.example/")
                .applicationProperties(Map.of())
                .routes(Map.of("v1", route))
                .build();

        DialApplicationInfoDto result = mapper.toDialApplicationInfoDto(source);

        assertThat(result).isNotNull();
        assertThat(result.getDeploymentId()).isEqualTo("EntityExtractor");
        assertThat(result.getDisplayName()).isEqualTo("Entity Extractor");
        assertThat(result.getVersion()).isNull();
        assertThat(result.getInputAttachmentTypes()).isNull();
        assertThat(result.getApplicationTypeSchemaId()).isEqualTo("https://schema.example/");
        assertThat(result.getRoutes()).containsOnlyKeys("v1");
        ApplicationRouteDto mappedRoute = result.getRoutes().get("v1");
        assertThat(mappedRoute.getName()).isEqualTo("v1");
        assertThat(mappedRoute.getRewritePath()).isTrue();
        assertThat(mappedRoute.getPaths()).containsExactly("/v1/.*");
        assertThat(mappedRoute.getUpstreams()).hasSize(1);
        assertThat(mappedRoute.getUpstreams().get(0).getEndpoint()).isEqualTo("http://upstream");
        assertThat(mappedRoute.getAttachmentPaths().getRequestBody()).isEmpty();
    }

    @Test
    @DisplayName("maps reference onto every deployment info subtype")
    void mapsReferenceOntoEverySubtype() {
        // The mapping is implicit name-matching, so a rename on DialCoreDeploymentDto would silently
        // null this API field with nothing else failing.
        DialModelInfoDto model = mapper.toDialModelInfoDto(DialCoreModelDto.builder()
                .id("gpt-5-mini")
                .reference("model-ref")
                .build());
        DialApplicationInfoDto application = mapper.toDialApplicationInfoDto(
                DialCoreApplicationDto.builder().id("app").reference("app-ref").build());
        ToolsetInfoDto toolset = mapper.toToolsetInfoDto(DialCoreToolsetDto.builder()
                .id("toolset")
                .reference("toolset-ref")
                .build());

        assertThat(model.getReference()).isEqualTo("model-ref");
        assertThat(application.getReference()).isEqualTo("app-ref");
        assertThat(toolset.getReference()).isEqualTo("toolset-ref");
    }

    @Test
    @DisplayName("maps route response and attachment paths")
    void mapsRouteResponseAndAttachmentPaths() {
        DialCoreRouteDto source = DialCoreRouteDto.builder()
                .response(DialCoreRouteResponseDto.builder()
                        .status(200)
                        .body("OK")
                        .build())
                .attachmentPaths(DialCoreAttachmentPathsDto.builder()
                        .requestBody(List.of("@.attachments"))
                        .responseBody(List.of("@.result"))
                        .build())
                .build();

        ApplicationRouteDto result = mapper.toApplicationRouteDto(source);

        assertThat(result.getResponse()).isNotNull();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getBody()).isEqualTo("OK");
        assertThat(result.getAttachmentPaths().getRequestBody()).containsExactly("@.attachments");
        assertThat(result.getAttachmentPaths().getResponseBody()).containsExactly("@.result");
    }

    @Test
    @DisplayName("maps DialCoreSchemaRouteDto to ApplicationRouteDto with all fields")
    void mapsSchemaRouteDtoToApplicationRouteDto() {
        DialCoreSchemaRouteDto source = DialCoreSchemaRouteDto.builder()
                .paths(List.of("/v1/.*"))
                .methods(List.of("GET", "POST"))
                .upstreams(List.of(DialCoreSchemaRouteUpstreamDto.builder()
                        .endpoint("http://upstream-svc")
                        .weight(1)
                        .tier(0)
                        .extraData(Map.of("key", "value"))
                        .build()))
                .userRoles(List.of("admin"))
                .rewritePath(true)
                .order(100)
                .maxRetryAttempts(3)
                .permissions(List.of("read"))
                .attachmentPaths(DialCoreSchemaAttachmentPathsDto.builder()
                        .requestBody(List.of("@.files"))
                        .responseBody(List.of("@.output"))
                        .build())
                .response(DialCoreSchemaRouteResponseDto.builder()
                        .status(200)
                        .body("{\"ok\":true}")
                        .build())
                .build();

        ApplicationRouteDto result = mapper.toApplicationRouteDto(source);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isNull();
        assertThat(result.getPaths()).containsExactly("/v1/.*");
        assertThat(result.getMethods()).containsExactly("GET", "POST");
        assertThat(result.getUpstreams()).hasSize(1);
        assertThat(result.getUpstreams().get(0).getEndpoint()).isEqualTo("http://upstream-svc");
        assertThat(result.getUpstreams().get(0).getWeight()).isEqualTo(1);
        assertThat(result.getUpstreams().get(0).getTier()).isZero();
        assertThat(result.getUserRoles()).containsExactly("admin");
        assertThat(result.getRewritePath()).isTrue();
        assertThat(result.getOrder()).isEqualTo(100);
        assertThat(result.getMaxRetryAttempts()).isEqualTo(3);
        assertThat(result.getPermissions()).containsExactly("read");
        assertThat(result.getAttachmentPaths().getRequestBody()).containsExactly("@.files");
        assertThat(result.getAttachmentPaths().getResponseBody()).containsExactly("@.output");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getBody()).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("maps schema route with null nested objects")
    void mapsSchemaRouteWithNullNestedObjects() {
        DialCoreSchemaRouteDto source = DialCoreSchemaRouteDto.builder()
                .paths(List.of("/v1/.*"))
                .methods(List.of("GET"))
                .upstreams(List.of())
                .attachmentPaths(null)
                .response(null)
                .build();

        ApplicationRouteDto result = mapper.toApplicationRouteDto(source);

        assertThat(result).isNotNull();
        assertThat(result.getAttachmentPaths()).isNull();
        assertThat(result.getResponse()).isNull();
        assertThat(result.getUpstreams()).isEmpty();
    }

    @Test
    @DisplayName("handles null nested fields")
    void handlesNullNestedFields() {
        DialCoreModelDto source = DialCoreModelDto.builder()
                .id("id1")
                .displayName("Name")
                .createdAt(1L)
                .updatedAt(2L)
                .build();

        DialModelInfoDto result = mapper.toDialModelInfoDto(source);

        assertThat(result.getDeploymentId()).isEqualTo("id1");
        assertThat(result.getCapabilities()).isNull();
        assertThat(result.getLimits()).isNull();
        assertThat(result.getPricing()).isNull();
    }
}
