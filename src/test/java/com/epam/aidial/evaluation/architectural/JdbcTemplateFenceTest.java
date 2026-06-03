package com.epam.aidial.evaluation.architectural;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.epam.aidial.evaluation.Application;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * Enforces that {@code NamedParameterJdbcTemplate} and {@code JdbcTemplate} are only used
 * in the datasource configuration package (bean wiring) and the health indicator package.
 * All repository and service code must use the typed jOOQ DSL instead.
 */
@AnalyzeClasses(packagesOf = Application.class, importOptions = ImportOption.DoNotIncludeTests.class)
public class JdbcTemplateFenceTest {

    private static final String[] ALLOWED_PACKAGES = {
        "..configuration.datasource..", "..service.infrastructure.health.."
    };

    @ArchTest
    public static void namedParameterJdbcTemplateMustNotBeUsedOutsideAllowedPackages(JavaClasses classes) {
        noClasses()
                .that()
                .resideOutsideOfPackages(ALLOWED_PACKAGES)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
                .check(classes);
    }

    @ArchTest
    public static void jdbcTemplateMustNotBeUsedOutsideAllowedPackages(JavaClasses classes) {
        noClasses()
                .that()
                .resideOutsideOfPackages(ALLOWED_PACKAGES)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
                .check(classes);
    }
}
