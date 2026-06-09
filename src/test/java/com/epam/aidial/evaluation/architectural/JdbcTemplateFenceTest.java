package com.epam.aidial.evaluation.architectural;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.epam.aidial.evaluation.Application;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Enforces that {@code NamedParameterJdbcTemplate} and {@code JdbcTemplate} are only used
 * in the datasource configuration package (bean wiring) and the health indicator package.
 * All repository and service code must use the typed jOOQ DSL instead.
 */
public class JdbcTemplateFenceTest {

    private static final String[] ALLOWED_PACKAGES = {
        "..configuration.datasource..", "..service.infrastructure.health.."
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
