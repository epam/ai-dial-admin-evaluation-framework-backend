package com.epam.aidial.evaluation.service.domain.sort;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.model.pagination.SortKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Parses repeatable {@code sort} query parameters in the form {@code <field>[,<asc|desc>]} into
 * {@link SortKey} in the order provided by the client.
 *
 * <p>
 * Note: Spring MVC may split comma-separated values into collections, so {@code sort=name,asc}
 * can arrive as {@code ["name", "asc"]}. This parser detects that shape and recombines it before parsing.
 */
@Component
@LogExecution
public class SortParser {

    /**
     * @throws IllegalArgumentException for malformed values (blank field, invalid direction, too many parts).
     */
    public List<SortKey> parse(List<String> sortParams) {
        if (sortParams == null || sortParams.isEmpty()) {
            return List.of();
        }

        List<String> normalizedParams = recombineIfSpringTokenized(sortParams);

        List<SortKey> result = new ArrayList<>(normalizedParams.size());
        for (String raw : normalizedParams) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("Sort parameter must not be blank");
            }
            String[] parts = raw.split(",", -1);
            if (parts.length < 1 || parts.length > 2) {
                throw new IllegalArgumentException("Invalid sort parameter: " + raw);
            }

            String field = parts[0].trim();
            if (field.isEmpty()) {
                throw new IllegalArgumentException("Sort field must not be blank");
            }

            PageRequest.SortDirection direction = PageRequest.SortDirection.ASC;
            if (parts.length == 2) {
                String dirRaw = parts[1].trim();
                if (dirRaw.isEmpty()) {
                    throw new IllegalArgumentException("Sort direction must not be blank");
                }
                String dirNormalized = dirRaw.toUpperCase(Locale.ROOT);
                try {
                    direction = PageRequest.SortDirection.valueOf(dirNormalized);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Invalid sort direction: " + dirRaw);
                }
            }

            result.add(SortKey.builder().field(field).direction(direction).build());
        }
        return List.copyOf(result);
    }

    private static List<String> recombineIfSpringTokenized(List<String> sortParams) {
        // If all values are comma-free and count is even, it might be the tokenized shape:
        // ["field","asc","other","desc"] -> ["field,asc","other,desc"].
        if (sortParams.size() < 2
                || sortParams.size() % 2 != 0
                || sortParams.stream().anyMatch(s -> s == null || s.contains(","))) {
            return sortParams;
        }

        for (int i = 1; i < sortParams.size(); i += 2) {
            String dir = sortParams.get(i);
            String dirNormalized = dir.trim().toLowerCase(Locale.ROOT);
            if (!dirNormalized.equals("asc") && !dirNormalized.equals("desc")) {
                return sortParams;
            }
        }

        List<String> recombined = new ArrayList<>(sortParams.size() / 2);
        for (int i = 0; i < sortParams.size(); i += 2) {
            recombined.add(sortParams.get(i) + "," + sortParams.get(i + 1));
        }
        return recombined;
    }
}
