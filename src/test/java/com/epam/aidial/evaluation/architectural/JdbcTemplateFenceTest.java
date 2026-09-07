package com.epam.aidial.evaluation.architectural;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.epam.aidial.evaluation.Application;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Enforces that {@code NamedParameterJdbcTemplate} and {@code JdbcTemplate} are only used
 * in the datasource configuration package (bean wiring), the health indicator package, and the
 * narrow analytics partition DDL repository package. All other repository and service code must
 * use the typed jOOQ DSL instead.
 *
 * <p>{@code data.db.analytics.repository.partition} is exempted because jOOQ has no partition-DDL
 * API (CREATE/DROP/ATTACH/DETACH PARTITION) — see
 * {@code openspec/changes/partition-analytics-tables/design.md} D5. The exemption is scoped to
 * that sub-package only, not the whole {@code data.db.analytics.repository} tree, so every other
 * analytics repository still must use the typed DSL.
 */
public class JdbcTemplateFenceTest {

    private static final String[] ALLOWED_PACKAGES = {
        "..configuration.datasource..",
        "..service.infrastructure.health..",
        "..data.db.analytics.repository.partition.."
    };

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackagesOf(Application.class);

    @Test
    void namedParameterJdbcTemplateMustNotBeUsedOutsideAllowedPackages() {
        noClasses()
                .that()
                .resideOutsideOfPackages(ALLOWED_PACKAGES)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
                .check(CLASSES);
    }

    @Test
    void jdbcTemplateMustNotBeUsedOutsideAllowedPackages() {
        noClasses()
                .that()
                .resideOutsideOfPackages(ALLOWED_PACKAGES)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
                .check(CLASSES);
    }
}
