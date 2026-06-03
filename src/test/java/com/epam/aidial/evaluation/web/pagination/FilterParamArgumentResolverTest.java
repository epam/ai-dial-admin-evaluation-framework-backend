package com.epam.aidial.evaluation.web.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

@SuppressWarnings("unchecked")
class FilterParamArgumentResolverTest {

    private FilterParamArgumentResolver resolver;
    private MethodParameter parameter;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        resolver = new FilterParamArgumentResolver();
        Method method = TargetController.class.getDeclaredMethod("list", List.class);
        parameter = new MethodParameter(method, 0);
    }

    @Test
    void shouldSupportFilterParamOnListOfString() {
        assertThat(resolver.supportsParameter(parameter)).isTrue();
    }

    @Test
    void shouldNotSupportUnannotatedParameter() throws NoSuchMethodException {
        Method method = TargetController.class.getDeclaredMethod("unannotated", List.class);
        MethodParameter unannotated = new MethodParameter(method, 0);

        assertThat(resolver.supportsParameter(unannotated)).isFalse();
    }

    @Test
    void shouldNotSupportNonStringListType() throws NoSuchMethodException {
        Method method = TargetController.class.getDeclaredMethod("wrongGeneric", List.class);
        MethodParameter wrong = new MethodParameter(method, 0);

        assertThat(resolver.supportsParameter(wrong)).isFalse();
    }

    @Test
    void shouldPreserveCommasInSingleValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("filter", "testCaseName:in:Delete1,Delete2");

        Object result = resolver.resolveArgument(parameter, null, new ServletWebRequest(request), null);

        assertThat(result).isInstanceOf(List.class);
        assertThat((List<String>) result).containsExactly("testCaseName:in:Delete1,Delete2");
    }

    @Test
    void shouldReturnOrderedListForRepeatedParameters() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("filter", "name:eq:a", "status:eq:active");

        Object result = resolver.resolveArgument(parameter, null, new ServletWebRequest(request), null);

        assertThat((List<String>) result).containsExactly("name:eq:a", "status:eq:active");
    }

    @Test
    void shouldReturnEmptyListWhenParameterAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        Object result = resolver.resolveArgument(parameter, null, new ServletWebRequest(request), null);

        assertThat(result).isEqualTo(List.of());
    }

    @Test
    void shouldRejectWhenCountExceedsMax() throws NoSuchMethodException {
        Method method = TargetController.class.getDeclaredMethod("listSmallMax", List.class);
        MethodParameter smallMaxParam = new MethodParameter(method, 0);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("filter", "a:eq:1", "b:eq:2", "c:eq:3");

        assertThatThrownBy(() -> resolver.resolveArgument(smallMaxParam, null, new ServletWebRequest(request), null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("filter")
                .hasMessageContaining("size must be between 0 and 2");
    }

    @SuppressWarnings("unused")
    private static final class TargetController {
        void list(@FilterParam List<String> filter) {}

        void listSmallMax(@FilterParam(max = 2) List<String> filter) {}

        void unannotated(List<String> filter) {}

        void wrongGeneric(@FilterParam List<Integer> filter) {}
    }
}
