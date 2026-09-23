package com.epam.aidial.evaluation.mcp.mapper;

import com.epam.aidial.evaluation.client.dialcore.dto.InterfaceType;
import com.epam.aidial.evaluation.mcp.model.ApplicationDetailsMcpDto;
import com.epam.aidial.evaluation.mcp.model.DeploymentInterface;
import com.epam.aidial.evaluation.mcp.model.DeploymentKind;
import com.epam.aidial.evaluation.mcp.model.DeploymentListMcpDto;
import com.epam.aidial.evaluation.mcp.model.DeploymentMcpDto;
import com.epam.aidial.evaluation.mcp.model.DeploymentSummaryMcpDto;
import com.epam.aidial.evaluation.mcp.model.ModelDetailsMcpDto;
import com.epam.aidial.evaluation.mcp.model.ModelDetailsMcpDto.ModelCapabilitiesMcpDto;
import com.epam.aidial.evaluation.mcp.model.ModelDetailsMcpDto.ModelLimitsMcpDto;
import com.epam.aidial.evaluation.mcp.model.ToolsetDetailsMcpDto;
import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialApplicationInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialModelInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ModelCapabilitiesDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ModelLimitsDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolsetInfoDto;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/**
 * Maps the service layer's {@link DeploymentInfoDto} hierarchy to the MCP-owned deployment shapes
 * (D-F8). Summaries returned by {@code list_deployments} are lean by construction of
 * {@code DeploymentService.getAllDeployments} (short projections already); detail fields are only
 * populated by {@code get_deployment}. No routes, pricing, icon URLs or input attachment types are
 * ever mapped.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface DeploymentMcpMapper {

    ModelLimitsMcpDto toLimits(@Nullable ModelLimitsDto source);

    ModelCapabilitiesMcpDto toCapabilities(@Nullable ModelCapabilitiesDto source);

    ModelDetailsMcpDto toModelDetails(DialModelInfoDto source);

    ToolsetDetailsMcpDto toToolsetDetails(ToolsetInfoDto source);

    ApplicationDetailsMcpDto toApplicationDetails(DialApplicationInfoDto source);

    /**
     * MapStruct cannot derive {@code type} from {@link DeploymentInfoDto}'s Jackson
     * {@code @JsonTypeInfo} discriminator (it is not a Java field), so the kind is resolved
     * explicitly from the runtime subclass.
     */
    default DeploymentKind kindOf(DeploymentInfoDto source) {
        return switch (source) {
            case DialModelInfoDto _ -> DeploymentKind.DIAL_MODEL;
            case DialApplicationInfoDto _ -> DeploymentKind.DIAL_APPLICATION;
            case ToolsetInfoDto _ -> DeploymentKind.DIAL_TOOLSET;
            case null, default ->
                throw new IllegalStateException(
                        "Unknown deployment info type: " + (source == null ? "null" : source.getClass()));
        };
    }

    /**
     * {@code toolsetTransport} is populated only for {@link ToolsetInfoDto} entries; every other
     * field is common to {@link DeploymentInfoDto}, so this stays a hand-written composition
     * rather than a generated method.
     */
    default DeploymentSummaryMcpDto toSummary(DeploymentInfoDto source) {
        McpTransport toolsetTransport = source instanceof ToolsetInfoDto toolset ? toolset.getTransport() : null;
        return new DeploymentSummaryMcpDto(
                source.getDeploymentId(),
                kindOf(source),
                source.getDisplayName(),
                source.getDescription(),
                toInterfaces(source.getInterfaces()),
                toolsetTransport);
    }

    default DeploymentListMcpDto toList(List<DeploymentInfoDto> source) {
        List<DeploymentSummaryMcpDto> summaries =
                source.stream().map(this::toSummary).toList();
        return new DeploymentListMcpDto(summaries, summaries.size());
    }

    /**
     * Exactly one of {@code model}, {@code toolset} and {@code application} is populated,
     * matching the runtime subclass of {@code source}.
     */
    default DeploymentMcpDto toDetail(DeploymentInfoDto source) {
        ModelDetailsMcpDto model = source instanceof DialModelInfoDto m ? toModelDetails(m) : null;
        ToolsetDetailsMcpDto toolset = source instanceof ToolsetInfoDto t ? toToolsetDetails(t) : null;
        ApplicationDetailsMcpDto application =
                source instanceof DialApplicationInfoDto a ? toApplicationDetails(a) : null;
        return new DeploymentMcpDto(
                source.getDeploymentId(),
                kindOf(source),
                source.getDisplayName(),
                source.getVersion(),
                source.getDescription(),
                source.getOwner(),
                toInterfaces(source.getInterfaces()),
                source.getFeatures(),
                source.getCreatedAt(),
                source.getUpdatedAt(),
                model,
                toolset,
                application);
    }

    /**
     * Converts by wire value (via {@link DeploymentInterface#fromWireValue}), not by enum
     * constant name, so the MCP-owned enum stays decoupled from
     * {@link InterfaceType}'s constant names.
     */
    default @Nullable List<DeploymentInterface> toInterfaces(@Nullable List<InterfaceType> source) {
        if (source == null) {
            return null;
        }
        return source.stream()
                .map(interfaceType -> DeploymentInterface.fromWireValue(interfaceType.getValue()))
                .toList();
    }
}
