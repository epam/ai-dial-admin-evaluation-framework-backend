package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreDeploymentDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreToolsetDto;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Unit tests for the collapse rules of the type-less by-ID deployment lookup.
 */
@DisplayName("DeploymentProbeCollapser")
class DeploymentProbeCollapserTest {

    private static final String DEPLOYMENT_ID = "some-deployment";

    private final DeploymentProbeCollapser collapser = new DeploymentProbeCollapser();

    @Test
    @DisplayName("returns the model when only the model probe hit")
    void returnsModelWhenOnlyModelProbeHit() {
        DialCoreModelDto model = DialCoreModelDto.builder().id(DEPLOYMENT_ID).build();

        DialCoreDeploymentDto winner = collapser.collapse(
                DEPLOYMENT_ID,
                List.of(
                        DeploymentProbe.completed(DeploymentType.DIAL_MODEL, model),
                        notFound(DeploymentType.DIAL_APPLICATION),
                        notFound(DeploymentType.DIAL_TOOLSET)));

        assertThat(winner).isSameAs(model);
    }

    @Test
    @DisplayName("returns the application when only the application probe hit")
    void returnsApplicationWhenOnlyApplicationProbeHit() {
        DialCoreApplicationDto application =
                DialCoreApplicationDto.builder().id(DEPLOYMENT_ID).build();

        DialCoreDeploymentDto winner = collapser.collapse(
                DEPLOYMENT_ID,
                List.of(
                        notFound(DeploymentType.DIAL_MODEL),
                        DeploymentProbe.completed(DeploymentType.DIAL_APPLICATION, application),
                        notFound(DeploymentType.DIAL_TOOLSET)));

        assertThat(winner).isSameAs(application);
    }

    @Test
    @DisplayName("returns the toolset when only the toolset probe hit")
    void returnsToolsetWhenOnlyToolsetProbeHit() {
        DialCoreToolsetDto toolset =
                DialCoreToolsetDto.builder().id(DEPLOYMENT_ID).build();

        DialCoreDeploymentDto winner = collapser.collapse(
                DEPLOYMENT_ID,
                List.of(
                        notFound(DeploymentType.DIAL_MODEL),
                        notFound(DeploymentType.DIAL_APPLICATION),
                        DeploymentProbe.completed(DeploymentType.DIAL_TOOLSET, toolset)));

        assertThat(winner).isSameAs(toolset);
    }

    @Test
    @DisplayName("returns the hit even when another probe failed with an upstream error")
    void returnsHitDespiteOtherProbeFailure() {
        DialCoreToolsetDto toolset =
                DialCoreToolsetDto.builder().id(DEPLOYMENT_ID).build();

        DialCoreDeploymentDto winner = collapser.collapse(
                DEPLOYMENT_ID,
                List.of(
                        failed(DeploymentType.DIAL_MODEL, HttpStatus.INTERNAL_SERVER_ERROR),
                        notFound(DeploymentType.DIAL_APPLICATION),
                        DeploymentProbe.completed(DeploymentType.DIAL_TOOLSET, toolset)));

        assertThat(winner).isSameAs(toolset);
    }

    @Test
    @DisplayName("prefers the model over the application when both probes hit")
    void prefersModelOverApplicationOnMultiHit() {
        DialCoreModelDto model = DialCoreModelDto.builder().id(DEPLOYMENT_ID).build();
        DialCoreApplicationDto application =
                DialCoreApplicationDto.builder().id(DEPLOYMENT_ID).build();

        DialCoreDeploymentDto winner = collapser.collapse(
                DEPLOYMENT_ID,
                List.of(
                        DeploymentProbe.completed(DeploymentType.DIAL_APPLICATION, application),
                        DeploymentProbe.completed(DeploymentType.DIAL_MODEL, model),
                        notFound(DeploymentType.DIAL_TOOLSET)));

        assertThat(winner).isSameAs(model);
    }

    @Test
    @DisplayName("prefers the application over the toolset when both probes hit")
    void prefersApplicationOverToolsetOnMultiHit() {
        DialCoreApplicationDto application =
                DialCoreApplicationDto.builder().id(DEPLOYMENT_ID).build();
        DialCoreToolsetDto toolset =
                DialCoreToolsetDto.builder().id(DEPLOYMENT_ID).build();

        DialCoreDeploymentDto winner = collapser.collapse(
                DEPLOYMENT_ID,
                List.of(
                        notFound(DeploymentType.DIAL_MODEL),
                        DeploymentProbe.completed(DeploymentType.DIAL_TOOLSET, toolset),
                        DeploymentProbe.completed(DeploymentType.DIAL_APPLICATION, application)));

        assertThat(winner).isSameAs(application);
    }

    @Test
    @DisplayName("all probes 404 yields a unified 404 naming every probe outcome")
    void allNotFoundYieldsUnified404() {
        assertThatThrownBy(() -> collapser.collapse(
                        DEPLOYMENT_ID,
                        List.of(
                                notFound(DeploymentType.DIAL_MODEL),
                                notFound(DeploymentType.DIAL_APPLICATION),
                                notFound(DeploymentType.DIAL_TOOLSET))))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(thrown -> assertThat(((DialCoreClientException) thrown).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining(DEPLOYMENT_ID)
                .hasMessageContaining("dial-model=404")
                .hasMessageContaining("dial-application=404")
                .hasMessageContaining("dial-toolset=404");
    }

    @Test
    @DisplayName("401 on any probe outranks 404 on the others")
    void authFailureOutranksNotFound() {
        assertThatThrownBy(() -> collapser.collapse(
                        DEPLOYMENT_ID,
                        List.of(
                                failed(DeploymentType.DIAL_MODEL, HttpStatus.UNAUTHORIZED),
                                notFound(DeploymentType.DIAL_APPLICATION),
                                notFound(DeploymentType.DIAL_TOOLSET))))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(thrown -> assertThat(((DialCoreClientException) thrown).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED))
                .hasMessageContaining("dial-model=401");
    }

    @Test
    @DisplayName("401 outranks 403 and 500 too")
    void authFailureOutranksEveryOtherStatus() {
        assertThatThrownBy(() -> collapser.collapse(
                        DEPLOYMENT_ID,
                        List.of(
                                failed(DeploymentType.DIAL_MODEL, HttpStatus.INTERNAL_SERVER_ERROR),
                                failed(DeploymentType.DIAL_APPLICATION, HttpStatus.FORBIDDEN),
                                failed(DeploymentType.DIAL_TOOLSET, HttpStatus.UNAUTHORIZED))))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(thrown -> assertThat(((DialCoreClientException) thrown).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("403 on any probe outranks 404 on the others")
    void accessDeniedOutranksNotFound() {
        assertThatThrownBy(() -> collapser.collapse(
                        DEPLOYMENT_ID,
                        List.of(
                                notFound(DeploymentType.DIAL_MODEL),
                                failed(DeploymentType.DIAL_APPLICATION, HttpStatus.FORBIDDEN),
                                notFound(DeploymentType.DIAL_TOOLSET))))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(thrown -> assertThat(((DialCoreClientException) thrown).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("403 outranks 500")
    void accessDeniedOutranksUpstreamError() {
        assertThatThrownBy(() -> collapser.collapse(
                        DEPLOYMENT_ID,
                        List.of(
                                failed(DeploymentType.DIAL_MODEL, HttpStatus.INTERNAL_SERVER_ERROR),
                                failed(DeploymentType.DIAL_APPLICATION, HttpStatus.FORBIDDEN),
                                notFound(DeploymentType.DIAL_TOOLSET))))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(thrown -> assertThat(((DialCoreClientException) thrown).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("an upstream 500 on any probe outranks 404 on the others")
    void upstreamErrorOutranksNotFound() {
        assertThatThrownBy(() -> collapser.collapse(
                        DEPLOYMENT_ID,
                        List.of(
                                notFound(DeploymentType.DIAL_MODEL),
                                notFound(DeploymentType.DIAL_APPLICATION),
                                failed(DeploymentType.DIAL_TOOLSET, HttpStatus.INTERNAL_SERVER_ERROR))))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(thrown -> assertThat(((DialCoreClientException) thrown).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR))
                .hasMessageContaining("dial-toolset=500");
    }

    @Test
    @DisplayName("all probes empty-bodied and error-free is treated as a 404")
    void allEmptyBodiesYield404() {
        assertThatThrownBy(() -> collapser.collapse(
                        DEPLOYMENT_ID,
                        List.of(
                                DeploymentProbe.completed(DeploymentType.DIAL_MODEL, null),
                                DeploymentProbe.completed(DeploymentType.DIAL_APPLICATION, null),
                                DeploymentProbe.completed(DeploymentType.DIAL_TOOLSET, null))))
                .isInstanceOf(DialCoreClientException.class)
                .satisfies(thrown -> assertThat(((DialCoreClientException) thrown).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("dial-model=empty");
    }

    private static DeploymentProbe notFound(DeploymentType type) {
        return failed(type, HttpStatus.NOT_FOUND);
    }

    private static DeploymentProbe failed(DeploymentType type, HttpStatusCode status) {
        return DeploymentProbe.failed(type, new DialCoreClientException(status, "probe failed with " + status.value()));
    }
}
