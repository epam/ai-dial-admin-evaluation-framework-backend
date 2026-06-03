package com.epam.aidial.evaluation.web.pagination;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.pagination.PaginationProperties;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves and validates pagination query parameters (page, size) with configurable defaults.
 * Shared by list controllers to avoid duplicated resolution logic.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class PaginationParamResolver {

    private final PaginationProperties paginationProperties;

    /**
     * Resolves page number. Null is treated as 0. Validates page >= 0.
     *
     * @param page request page (null → 0)
     * @return validated page index
     * @throws ValidationException if page is negative
     */
    public int resolvePage(Integer page) {
        int resolved = page != null ? page : 0;
        if (resolved < 0) {
            throw new ValidationException("Page must be >= 0, but was: " + resolved);
        }
        return resolved;
    }

    /**
     * Resolves page size. Null uses default from config. Validates size in [1, maxSize].
     *
     * @param size request size (null → default from PaginationProperties)
     * @return validated page size
     * @throws ValidationException if size is &lt; 1 or &gt; maxSize
     */
    public int resolveSize(Integer size) {
        int resolved = size != null ? size : paginationProperties.getDefaultSize();
        int maxSize = paginationProperties.getMaxSize();
        if (resolved < 1 || resolved > maxSize) {
            throw new ValidationException("Size must be in range [1.." + maxSize + "], but was: " + resolved);
        }
        return resolved;
    }
}
