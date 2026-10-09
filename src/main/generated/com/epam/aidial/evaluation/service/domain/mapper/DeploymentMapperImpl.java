package com.epam.aidial.evaluation.service.domain.mapper;

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
import com.epam.aidial.evaluation.service.domain.dto.deployment.ModelCapabilitiesDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ModelLimitsDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ModelPricingDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.RouteAttachmentPathsDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.RouteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.RouteUpstreamDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolsetInfoDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-09-10T11:52:23+0300",
    comments = "version: 1.6.3, compiler: IncrementalProcessingEnvironment from gradle-java-compiler-worker-9.7.1.jar, environment: Java 25.0.3 (Oracle Corporation)"
)
@Component
public class DeploymentMapperImpl implements DeploymentMapper {

    @Override
    public DialModelInfoDto toDialModelInfoDto(DialCoreModelDto source) {
        if ( source == null ) {
            return null;
        }

        DialModelInfoDto.DialModelInfoDtoBuilder<?, ?> dialModelInfoDto = DialModelInfoDto.builder();

        dialModelInfoDto.deploymentId( source.getId() );
        dialModelInfoDto.version( source.getDisplayVersion() );
        dialModelInfoDto.capabilities( toModelCapabilitiesDto( source.getCapabilities() ) );
        dialModelInfoDto.limits( toModelLimitsDto( source.getLimits() ) );
        dialModelInfoDto.pricing( toModelPricingDto( source.getPricing() ) );
        dialModelInfoDto.displayName( mapMultilingual( source.getDisplayName() ) );
        dialModelInfoDto.description( mapMultilingual( source.getDescription() ) );
        dialModelInfoDto.owner( source.getOwner() );
        dialModelInfoDto.createdAt( source.getCreatedAt() );
        dialModelInfoDto.updatedAt( source.getUpdatedAt() );
        List<String> list = source.getDescriptionKeywords();
        if ( list != null ) {
            dialModelInfoDto.descriptionKeywords( new ArrayList<String>( list ) );
        }
        List<String> list1 = source.getInputAttachmentTypes();
        if ( list1 != null ) {
            dialModelInfoDto.inputAttachmentTypes( new ArrayList<String>( list1 ) );
        }
        dialModelInfoDto.reference( source.getReference() );

        return dialModelInfoDto.build();
    }

    @Override
    public DialApplicationInfoDto toDialApplicationInfoDto(DialCoreApplicationDto source) {
        if ( source == null ) {
            return null;
        }

        DialApplicationInfoDto.DialApplicationInfoDtoBuilder<?, ?> dialApplicationInfoDto = DialApplicationInfoDto.builder();

        dialApplicationInfoDto.deploymentId( source.getId() );
        dialApplicationInfoDto.version( source.getDisplayVersion() );
        dialApplicationInfoDto.applicationTypeSchemaId( source.getApplicationTypeSchemaId() );
        Map<String, Object> map = source.getApplicationProperties();
        if ( map != null ) {
            dialApplicationInfoDto.applicationProperties( new LinkedHashMap<String, Object>( map ) );
        }
        dialApplicationInfoDto.routes( mapRoutes( source.getRoutes() ) );
        dialApplicationInfoDto.displayName( mapMultilingual( source.getDisplayName() ) );
        dialApplicationInfoDto.description( mapMultilingual( source.getDescription() ) );
        dialApplicationInfoDto.owner( source.getOwner() );
        dialApplicationInfoDto.createdAt( source.getCreatedAt() );
        dialApplicationInfoDto.updatedAt( source.getUpdatedAt() );
        List<String> list = source.getDescriptionKeywords();
        if ( list != null ) {
            dialApplicationInfoDto.descriptionKeywords( new ArrayList<String>( list ) );
        }
        List<String> list1 = source.getInputAttachmentTypes();
        if ( list1 != null ) {
            dialApplicationInfoDto.inputAttachmentTypes( new ArrayList<String>( list1 ) );
        }
        dialApplicationInfoDto.reference( source.getReference() );

        return dialApplicationInfoDto.build();
    }

    @Override
    public ApplicationRouteDto toApplicationRouteDto(DialCoreRouteDto source) {
        if ( source == null ) {
            return null;
        }

        ApplicationRouteDto.ApplicationRouteDtoBuilder applicationRouteDto = ApplicationRouteDto.builder();

        applicationRouteDto.name( source.getName() );
        List<String> list = source.getUserRoles();
        if ( list != null ) {
            applicationRouteDto.userRoles( new ArrayList<String>( list ) );
        }
        applicationRouteDto.response( toRouteResponseDto( source.getResponse() ) );
        applicationRouteDto.rewritePath( source.getRewritePath() );
        List<String> list1 = source.getPaths();
        if ( list1 != null ) {
            applicationRouteDto.paths( new ArrayList<String>( list1 ) );
        }
        List<String> list2 = source.getMethods();
        if ( list2 != null ) {
            applicationRouteDto.methods( new ArrayList<String>( list2 ) );
        }
        applicationRouteDto.upstreams( dialCoreRouteUpstreamDtoListToRouteUpstreamDtoList( source.getUpstreams() ) );
        applicationRouteDto.maxRetryAttempts( source.getMaxRetryAttempts() );
        applicationRouteDto.order( source.getOrder() );
        List<String> list4 = source.getPermissions();
        if ( list4 != null ) {
            applicationRouteDto.permissions( new ArrayList<String>( list4 ) );
        }
        applicationRouteDto.attachmentPaths( toRouteAttachmentPathsDto( source.getAttachmentPaths() ) );

        return applicationRouteDto.build();
    }

    @Override
    public ApplicationRouteDto toApplicationRouteDto(DialCoreSchemaRouteDto source) {
        if ( source == null ) {
            return null;
        }

        ApplicationRouteDto.ApplicationRouteDtoBuilder applicationRouteDto = ApplicationRouteDto.builder();

        List<String> list = source.getUserRoles();
        if ( list != null ) {
            applicationRouteDto.userRoles( new ArrayList<String>( list ) );
        }
        applicationRouteDto.response( toRouteResponseDto( source.getResponse() ) );
        applicationRouteDto.rewritePath( source.getRewritePath() );
        List<String> list1 = source.getPaths();
        if ( list1 != null ) {
            applicationRouteDto.paths( new ArrayList<String>( list1 ) );
        }
        List<String> list2 = source.getMethods();
        if ( list2 != null ) {
            applicationRouteDto.methods( new ArrayList<String>( list2 ) );
        }
        applicationRouteDto.upstreams( dialCoreSchemaRouteUpstreamDtoListToRouteUpstreamDtoList( source.getUpstreams() ) );
        applicationRouteDto.maxRetryAttempts( source.getMaxRetryAttempts() );
        applicationRouteDto.order( source.getOrder() );
        List<String> list4 = source.getPermissions();
        if ( list4 != null ) {
            applicationRouteDto.permissions( new ArrayList<String>( list4 ) );
        }
        applicationRouteDto.attachmentPaths( toRouteAttachmentPathsDto( source.getAttachmentPaths() ) );

        return applicationRouteDto.build();
    }

    @Override
    public ModelCapabilitiesDto toModelCapabilitiesDto(DialCoreCapabilitiesDto source) {
        if ( source == null ) {
            return null;
        }

        ModelCapabilitiesDto.ModelCapabilitiesDtoBuilder modelCapabilitiesDto = ModelCapabilitiesDto.builder();

        List<String> list = source.getScaleTypes();
        if ( list != null ) {
            modelCapabilitiesDto.scaleTypes( new ArrayList<String>( list ) );
        }
        modelCapabilitiesDto.completion( source.getCompletion() );
        modelCapabilitiesDto.chatCompletion( source.getChatCompletion() );
        modelCapabilitiesDto.embeddings( source.getEmbeddings() );
        modelCapabilitiesDto.fineTune( source.getFineTune() );
        modelCapabilitiesDto.inference( source.getInference() );

        return modelCapabilitiesDto.build();
    }

    @Override
    public ModelLimitsDto toModelLimitsDto(DialCoreLimitsDto source) {
        if ( source == null ) {
            return null;
        }

        ModelLimitsDto.ModelLimitsDtoBuilder modelLimitsDto = ModelLimitsDto.builder();

        modelLimitsDto.maxTotalTokens( source.getMaxTotalTokens() );
        modelLimitsDto.maxCompletionTokens( source.getMaxCompletionTokens() );

        return modelLimitsDto.build();
    }

    @Override
    public ModelPricingDto toModelPricingDto(DialCorePricingDto source) {
        if ( source == null ) {
            return null;
        }

        ModelPricingDto.ModelPricingDtoBuilder modelPricingDto = ModelPricingDto.builder();

        modelPricingDto.unit( source.getUnit() );
        modelPricingDto.prompt( source.getPrompt() );
        modelPricingDto.completion( source.getCompletion() );

        return modelPricingDto.build();
    }

    @Override
    public RouteUpstreamDto toRouteUpstreamDto(DialCoreRouteUpstreamDto source) {
        if ( source == null ) {
            return null;
        }

        RouteUpstreamDto.RouteUpstreamDtoBuilder routeUpstreamDto = RouteUpstreamDto.builder();

        routeUpstreamDto.endpoint( source.getEndpoint() );
        routeUpstreamDto.extraData( source.getExtraData() );
        routeUpstreamDto.weight( source.getWeight() );
        routeUpstreamDto.tier( source.getTier() );

        return routeUpstreamDto.build();
    }

    @Override
    public RouteUpstreamDto toRouteUpstreamDto(DialCoreSchemaRouteUpstreamDto source) {
        if ( source == null ) {
            return null;
        }

        RouteUpstreamDto.RouteUpstreamDtoBuilder routeUpstreamDto = RouteUpstreamDto.builder();

        routeUpstreamDto.endpoint( source.getEndpoint() );
        routeUpstreamDto.extraData( source.getExtraData() );
        routeUpstreamDto.weight( source.getWeight() );
        routeUpstreamDto.tier( source.getTier() );

        return routeUpstreamDto.build();
    }

    @Override
    public RouteResponseDto toRouteResponseDto(DialCoreRouteResponseDto source) {
        if ( source == null ) {
            return null;
        }

        RouteResponseDto.RouteResponseDtoBuilder routeResponseDto = RouteResponseDto.builder();

        routeResponseDto.status( source.getStatus() );
        routeResponseDto.body( source.getBody() );

        return routeResponseDto.build();
    }

    @Override
    public RouteResponseDto toRouteResponseDto(DialCoreSchemaRouteResponseDto source) {
        if ( source == null ) {
            return null;
        }

        RouteResponseDto.RouteResponseDtoBuilder routeResponseDto = RouteResponseDto.builder();

        routeResponseDto.status( source.getStatus() );
        routeResponseDto.body( source.getBody() );

        return routeResponseDto.build();
    }

    @Override
    public RouteAttachmentPathsDto toRouteAttachmentPathsDto(DialCoreAttachmentPathsDto source) {
        if ( source == null ) {
            return null;
        }

        RouteAttachmentPathsDto.RouteAttachmentPathsDtoBuilder routeAttachmentPathsDto = RouteAttachmentPathsDto.builder();

        List<String> list = source.getRequestBody();
        if ( list != null ) {
            routeAttachmentPathsDto.requestBody( new ArrayList<String>( list ) );
        }
        List<String> list1 = source.getResponseBody();
        if ( list1 != null ) {
            routeAttachmentPathsDto.responseBody( new ArrayList<String>( list1 ) );
        }

        return routeAttachmentPathsDto.build();
    }

    @Override
    public RouteAttachmentPathsDto toRouteAttachmentPathsDto(DialCoreSchemaAttachmentPathsDto source) {
        if ( source == null ) {
            return null;
        }

        RouteAttachmentPathsDto.RouteAttachmentPathsDtoBuilder routeAttachmentPathsDto = RouteAttachmentPathsDto.builder();

        List<String> list = source.getRequestBody();
        if ( list != null ) {
            routeAttachmentPathsDto.requestBody( new ArrayList<String>( list ) );
        }
        List<String> list1 = source.getResponseBody();
        if ( list1 != null ) {
            routeAttachmentPathsDto.responseBody( new ArrayList<String>( list1 ) );
        }

        return routeAttachmentPathsDto.build();
    }

    @Override
    public ToolsetInfoDto toToolsetInfoDto(DialCoreToolsetDto source) {
        if ( source == null ) {
            return null;
        }

        ToolsetInfoDto.ToolsetInfoDtoBuilder<?, ?> toolsetInfoDto = ToolsetInfoDto.builder();

        toolsetInfoDto.deploymentId( source.getId() );
        toolsetInfoDto.version( source.getDisplayVersion() );
        toolsetInfoDto.transport( dialTransportToMcp( source.getTransport() ) );
        toolsetInfoDto.displayName( mapMultilingual( source.getDisplayName() ) );
        toolsetInfoDto.description( mapMultilingual( source.getDescription() ) );
        toolsetInfoDto.owner( source.getOwner() );
        toolsetInfoDto.createdAt( source.getCreatedAt() );
        toolsetInfoDto.updatedAt( source.getUpdatedAt() );
        List<String> list = source.getDescriptionKeywords();
        if ( list != null ) {
            toolsetInfoDto.descriptionKeywords( new ArrayList<String>( list ) );
        }
        List<String> list1 = source.getInputAttachmentTypes();
        if ( list1 != null ) {
            toolsetInfoDto.inputAttachmentTypes( new ArrayList<String>( list1 ) );
        }
        toolsetInfoDto.reference( source.getReference() );
        List<String> list2 = source.getAllowedTools();
        if ( list2 != null ) {
            toolsetInfoDto.allowedTools( new ArrayList<String>( list2 ) );
        }

        return toolsetInfoDto.build();
    }

    protected List<RouteUpstreamDto> dialCoreRouteUpstreamDtoListToRouteUpstreamDtoList(List<DialCoreRouteUpstreamDto> list) {
        if ( list == null ) {
            return null;
        }

        List<RouteUpstreamDto> list1 = new ArrayList<RouteUpstreamDto>( list.size() );
        for ( DialCoreRouteUpstreamDto dialCoreRouteUpstreamDto : list ) {
            list1.add( toRouteUpstreamDto( dialCoreRouteUpstreamDto ) );
        }

        return list1;
    }

    protected List<RouteUpstreamDto> dialCoreSchemaRouteUpstreamDtoListToRouteUpstreamDtoList(List<DialCoreSchemaRouteUpstreamDto> list) {
        if ( list == null ) {
            return null;
        }

        List<RouteUpstreamDto> list1 = new ArrayList<RouteUpstreamDto>( list.size() );
        for ( DialCoreSchemaRouteUpstreamDto dialCoreSchemaRouteUpstreamDto : list ) {
            list1.add( toRouteUpstreamDto( dialCoreSchemaRouteUpstreamDto ) );
        }

        return list1;
    }
}
