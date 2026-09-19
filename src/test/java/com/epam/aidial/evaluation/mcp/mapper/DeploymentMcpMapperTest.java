package com.epam.aidial.evaluation.mcp.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.client.dialcore.dto.InterfaceType;
import com.epam.aidial.evaluation.mcp.model.DeploymentInterface;
import com.epam.aidial.evaluation.mcp.model.DeploymentKind;
import com.epam.aidial.evaluation.mcp.model.DeploymentListMcpDto;
import com.epam.aidial.evaluation.mcp.model.DeploymentMcpDto;
import com.epam.aidial.evaluation.mcp.model.DeploymentSummaryMcpDto;
import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ApplicationRouteDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialApplicationInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialModelInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ModelCapabilitiesDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ModelLimitsDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ModelPricingDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolsetInfoDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("DeploymentMcpMapper")
class DeploymentMcpMapperTest {

    private final DeploymentMcpMapper mapper = Mappers.getMapper(DeploymentMcpMapper.class);

    @Test
    @DisplayName("toSummary() maps a model deployment without a toolsetTransport")
    void toSummaryMapsModelDeploymentWithoutToolsetTransport() {
        DialModelInfoDto model = DialModelInfoDto.builder()
                .deploymentId("gpt-5-mini")
                .displayName("GPT-5 mini")
                .description("desc")
                .interfaces(List.of(InterfaceType.CHAT, InterfaceType.OPEN_AI_CHAT_COMPLETIONS))
                .build();

        DeploymentSummaryMcpDto summary = mapper.toSummary(model);

        assertThat(summary.deploymentId()).isEqualTo("gpt-5-mini");
        assertThat(summary.type()).isEqualTo(DeploymentKind.DIAL_MODEL);
        assertThat(summary.displayName()).isEqualTo("GPT-5 mini");
        assertThat(summary.interfaces())
                .containsExactly(DeploymentInterface.CHAT, DeploymentInterface.OPEN_AI_CHAT_COMPLETIONS);
        assertThat(summary.toolsetTransport()).isNull();
    }

    @Test
    @DisplayName("toSummary() maps an application deployment with its type")
    void toSummaryMapsApplicationDeployment() {
        DialApplicationInfoDto application = DialApplicationInfoDto.builder()
                .deploymentId("EntityExtractor")
                .displayName("Entity Extractor")
                .build();

        DeploymentSummaryMcpDto summary = mapper.toSummary(application);

        assertThat(summary.type()).isEqualTo(DeploymentKind.DIAL_APPLICATION);
        assertThat(summary.toolsetTransport()).isNull();
    }

    @Test
    @DisplayName("toSummary() maps a toolset deployment's transport")
    void toSummaryMapsToolsetDeploymentTransport() {
        ToolsetInfoDto toolset = ToolsetInfoDto.builder()
                .deploymentId("my-toolset")
                .displayName("My Toolset")
                .transport(McpTransport.STREAMABLE_HTTP)
                .build();

        DeploymentSummaryMcpDto summary = mapper.toSummary(toolset);

        assertThat(summary.type()).isEqualTo(DeploymentKind.DIAL_TOOLSET);
        assertThat(summary.toolsetTransport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
    }

    @Test
    @DisplayName("toDetail() for a model deployment populates model only")
    void toDetailForModelDeploymentPopulatesModelOnly() {
        DialModelInfoDto model = DialModelInfoDto.builder()
                .deploymentId("gpt-5-mini")
                .displayName("GPT-5 mini")
                .limits(ModelLimitsDto.builder()
                        .maxTotalTokens(32000)
                        .maxCompletionTokens(4096)
                        .build())
                .capabilities(ModelCapabilitiesDto.builder()
                        .chatCompletion(true)
                        .completion(false)
                        .embeddings(false)
                        .build())
                .pricing(ModelPricingDto.builder().build())
                .build();

        DeploymentMcpDto detail = mapper.toDetail(model);

        assertThat(detail.model()).isNotNull();
        assertThat(detail.model().limits().maxTotalTokens()).isEqualTo(32000);
        assertThat(detail.model().limits().maxCompletionTokens()).isEqualTo(4096);
        assertThat(detail.model().capabilities().chatCompletion()).isTrue();
        assertThat(detail.toolset()).isNull();
        assertThat(detail.application()).isNull();
    }

    @Test
    @DisplayName("toDetail() for a toolset deployment populates toolset only")
    void toDetailForToolsetDeploymentPopulatesToolsetOnly() {
        ToolsetInfoDto toolset = ToolsetInfoDto.builder()
                .deploymentId("my-toolset")
                .displayName("My Toolset")
                .transport(McpTransport.STREAMABLE_HTTP)
                .allowedTools(List.of("search", "calculate"))
                .build();

        DeploymentMcpDto detail = mapper.toDetail(toolset);

        assertThat(detail.toolset()).isNotNull();
        assertThat(detail.toolset().transport()).isEqualTo(McpTransport.STREAMABLE_HTTP);
        assertThat(detail.toolset().allowedTools()).containsExactly("search", "calculate");
        assertThat(detail.model()).isNull();
        assertThat(detail.application()).isNull();
    }

    @Test
    @DisplayName("toDetail() for an application deployment populates application only, without routes or pricing")
    void toDetailForApplicationDeploymentPopulatesApplicationOnlyWithoutRoutesOrPricing() {
        DialApplicationInfoDto application = DialApplicationInfoDto.builder()
                .deploymentId("EntityExtractor")
                .displayName("Entity Extractor")
                .applicationTypeSchemaId("schema-1")
                .routes(Map.of("default", ApplicationRouteDto.builder().build()))
                .build();

        DeploymentMcpDto detail = mapper.toDetail(application);

        assertThat(detail.application()).isNotNull();
        assertThat(detail.application().applicationTypeSchemaId()).isEqualTo("schema-1");
        assertThat(detail.model()).isNull();
        assertThat(detail.toolset()).isNull();
    }

    @Test
    @DisplayName("toList() maps every entry and sets total to the list size")
    void toListMapsEveryEntryAndSetsTotalToListSize() {
        List<DeploymentInfoDto> deployments = List.of(
                DialModelInfoDto.builder()
                        .deploymentId("m1")
                        .displayName("Model 1")
                        .build(),
                ToolsetInfoDto.builder()
                        .deploymentId("t1")
                        .displayName("Toolset 1")
                        .build());

        DeploymentListMcpDto list = mapper.toList(deployments);

        assertThat(list.total()).isEqualTo(2);
        assertThat(list.deployments()).hasSize(2);
        assertThat(list.deployments().get(0).deploymentId()).isEqualTo("m1");
        assertThat(list.deployments().get(1).deploymentId()).isEqualTo("t1");
    }
}
