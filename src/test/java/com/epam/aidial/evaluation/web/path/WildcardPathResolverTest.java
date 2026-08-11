package com.epam.aidial.evaluation.web.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

@DisplayName("WildcardPathResolver — deployment ID extraction from the /** path tail")
class WildcardPathResolverTest {

    private static final String PATTERN = "/api/v1/deployments/{deploymentType}/**";

    private final WildcardPathResolver resolver = new WildcardPathResolver();

    @Test
    @DisplayName("single-segment ID is returned as-is")
    void singleSegmentId() {
        assertThat(resolveFor("/api/v1/deployments/dial-model/gpt-5")).isEqualTo("gpt-5");
    }

    @Test
    @DisplayName("slash-containing ID keeps all its segments")
    void slashContainingId() {
        assertThat(resolveFor("/api/v1/deployments/dial-application/applications/public/my-app__0.0.1"))
                .isEqualTo("applications/public/my-app__0.0.1");
    }

    @Test
    @DisplayName("percent-encoded spaces are decoded once")
    void percentEncodedSpacesDecodedOnce() {
        assertThat(resolveFor(
                        "/api/v1/deployments/dial-application/applications/public/Quick%20App%20with%20RAG__0.0.1"))
                .isEqualTo("applications/public/Quick App with RAG__0.0.1");
    }

    @Test
    @DisplayName("double-encoded input decodes to a single-encoded value, not to the raw one")
    void doubleEncodedInputDecodedOnlyOnce() {
        assertThat(resolveFor("/api/v1/deployments/dial-application/Quick%2520App__0.0.1"))
                .isEqualTo("Quick%20App__0.0.1");
    }

    @Test
    @DisplayName("intra-segment %2F is decoded to a slash")
    void encodedSlashDecoded() {
        assertThat(resolveFor("/api/v1/deployments/dial-toolset/toolsets%2Fpublic%2Fmy-toolset"))
                .isEqualTo("toolsets/public/my-toolset");
    }

    @Test
    @DisplayName("plus sign is preserved literally, not turned into a space")
    void plusSignPreserved() {
        assertThat(resolveFor("/api/v1/deployments/dial-model/gpt-5+preview")).isEqualTo("gpt-5+preview");
    }

    @Test
    @DisplayName("non-ASCII percent-encoding is decoded as UTF-8")
    void nonAsciiDecodedAsUtf8() {
        assertThat(resolveFor("/api/v1/deployments/dial-model/%D0%BC%D0%BE%D0%B4%D0%B5%D0%BB%D1%8C"))
                .isEqualTo("модель");
    }

    @Test
    @DisplayName("parentheses and other sub-delims survive untouched")
    void subDelimsPreserved() {
        assertThat(resolveFor("/api/v1/deployments/dial-toolset/toolsets/public/3DMolVisualizer_(copy)__0.0.2"))
                .isEqualTo("toolsets/public/3DMolVisualizer_(copy)__0.0.2");
    }

    @Test
    @DisplayName("missing tail resolves to an empty string")
    void missingTailIsEmpty() {
        assertThat(resolveFor("/api/v1/deployments/dial-model")).isEmpty();
    }

    @Test
    @DisplayName("trailing slash with no ID resolves to an empty string")
    void trailingSlashOnlyIsEmpty() {
        assertThat(resolveFor("/api/v1/deployments/dial-model/")).isEmpty();
    }

    @Test
    @DisplayName("query string is not part of the resolved ID")
    void queryStringExcluded() {
        MockHttpServletRequest request = request("/api/v1/deployments/dial-model/gpt-5");
        request.setQueryString("foo=bar");

        assertThat(resolver.resolveTail(request)).isEqualTo("gpt-5");
    }

    @Test
    @DisplayName("context path is stripped when the handler-mapping lookup path attribute is absent")
    void contextPathStrippedInFallback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/ef");
        request.setRequestURI("/ef/api/v1/deployments/dial-application/applications/public/my-app");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, PATTERN);

        assertThat(resolver.resolveTail(request)).isEqualTo("applications/public/my-app");
    }

    @Test
    @DisplayName("malformed percent-encoding is rejected with a validation error")
    void malformedEncodingRejected() {
        assertThatThrownBy(() -> resolveFor("/api/v1/deployments/dial-model/broken%2"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Malformed percent-encoded sequence");
    }

    @Test
    @DisplayName("request without a matched mapping pattern fails fast")
    void missingPatternAttributeFailsFast() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/deployments/dial-model/gpt-5");

        assertThatThrownBy(() -> resolver.resolveTail(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No best matching pattern");
    }

    private String resolveFor(String rawPath) {
        return resolver.resolveTail(request(rawPath));
    }

    private MockHttpServletRequest request(String rawPath) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(rawPath);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, PATTERN);
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, rawPath);
        return request;
    }
}
