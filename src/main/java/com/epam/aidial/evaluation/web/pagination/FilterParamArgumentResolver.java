package com.epam.aidial.evaluation.web.pagination;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link FilterParam}-annotated {@code List<String>} controller parameters by reading
 * {@link HttpServletRequest#getParameterValues(String)} directly, preserving commas inside a
 * single {@code ?filter=...} value. Enforces the {@code max} count limit before the filter
 * parser runs; on violation, throws {@link ValidationException} (HTTP 400).
 */
@Component
@LogExecution
public class FilterParamArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(FilterParam.class)) {
            return false;
        }
        if (!List.class.isAssignableFrom(parameter.getParameterType())) {
            return false;
        }
        ResolvableType resolvable = ResolvableType.forMethodParameter(parameter);
        return resolvable.getGeneric(0).resolve() == String.class;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        FilterParam annotation = parameter.getParameterAnnotation(FilterParam.class);
        String name = annotation.name();
        int max = annotation.max();

        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String[] values = request != null ? request.getParameterValues(name) : null;
        if (values == null || values.length == 0) {
            return List.of();
        }
        if (values.length > max) {
            throw new ValidationException("Parameter [" + name + "]: size must be between 0 and " + max + ".");
        }
        return List.of(values);
    }
}
