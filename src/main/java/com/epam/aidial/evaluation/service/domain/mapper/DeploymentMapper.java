package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreAttachmentPathsDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreCapabilitiesDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreDeploymentDto;
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
import com.epam.aidial.evaluation.client.dialcore.dto.DialTransport;
import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ApplicationRouteDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialApplicationInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialModelInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ModelCapabilitiesDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ModelLimitsDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ModelPricingDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.RouteAttachmentPathsDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.RouteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.RouteUpstreamDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolsetInfoDto;
import java.util.Map;
import java.util.stream.Collectors;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface DeploymentMapper {

    @Mapping(source = "id", target = "deploymentId")
    @Mapping(source = "displayVersion", target = "version")
    @Mapping(
            target = "capabilities",
            source = "capabilities",
            nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(
            target = "limits",
            source = "limits",
            nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(
            target = "pricing",
            source = "pricing",
            nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    DialModelInfoDto toDialModelInfoDto(DialCoreModelDto source);

    @Mapping(source = "id", target = "deploymentId")
    @Mapping(source = "displayVersion", target = "version")
    @Mapping(
            target = "applicationTypeSchemaId",
            source = "applicationTypeSchemaId",
            nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(
            target = "applicationProperties",
            source = "applicationProperties",
            nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "routes", source = "routes", qualifiedByName = "mapRoutes")
    DialApplicationInfoDto toDialApplicationInfoDto(DialCoreApplicationDto source);

    @Named("mapRoutes")
    default Map<String, ApplicationRouteDto> mapRoutes(Map<String, DialCoreRouteDto> routes) {
        if (routes == null) {
            return null;
        }
        return routes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> toApplicationRouteDto(e.getValue())));
    }

    ApplicationRouteDto toApplicationRouteDto(DialCoreRouteDto source);

    @Mapping(target = "name", ignore = true)
    ApplicationRouteDto toApplicationRouteDto(DialCoreSchemaRouteDto source);

    ModelCapabilitiesDto toModelCapabilitiesDto(DialCoreCapabilitiesDto source);

    ModelLimitsDto toModelLimitsDto(DialCoreLimitsDto source);

    ModelPricingDto toModelPricingDto(DialCorePricingDto source);

    RouteUpstreamDto toRouteUpstreamDto(DialCoreRouteUpstreamDto source);

    RouteUpstreamDto toRouteUpstreamDto(DialCoreSchemaRouteUpstreamDto source);

    RouteResponseDto toRouteResponseDto(DialCoreRouteResponseDto source);

    RouteResponseDto toRouteResponseDto(DialCoreSchemaRouteResponseDto source);

    RouteAttachmentPathsDto toRouteAttachmentPathsDto(DialCoreAttachmentPathsDto source);

    RouteAttachmentPathsDto toRouteAttachmentPathsDto(DialCoreSchemaAttachmentPathsDto source);

    @Mapping(source = "id", target = "deploymentId")
    @Mapping(source = "displayVersion", target = "version")
    @Mapping(source = "transport", target = "transport", qualifiedByName = "dialTransportToMcp")
    ToolsetInfoDto toToolsetInfoDto(DialCoreToolsetDto source);

    @Named("dialTransportToMcp")
    default McpTransport dialTransportToMcp(DialTransport dialTransport) {
        if (dialTransport == null) {
            return null;
        }
        return switch (dialTransport) {
            case HTTP -> McpTransport.STREAMABLE_HTTP;
            case SSE -> McpTransport.SSE;
        };
    }

    /**
     * Fully maps a unified deployment entry from /v1/deployments to the matching info subtype:
     * all base fields (version from displayVersion, owner, timestamps, descriptionKeywords,
     * inputAttachmentTypes) plus subtype-specific ones (model capabilities/limits/pricing,
     * application properties/schemaId/routes, toolset transport/allowedTools).
     */
    default DeploymentInfoDto toDeploymentInfoDto(DialCoreDeploymentDto source) {
        return switch (source) {
            case DialCoreModelDto model -> toDialModelInfoDto(model);
            case DialCoreApplicationDto application -> toDialApplicationInfoDto(application);
            case DialCoreToolsetDto toolset -> toToolsetInfoDto(toolset);
            // Unknown object type (or null entry) — return null so the caller can log and skip
            case null, default -> null;
        };
    }
}
