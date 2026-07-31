package com.epam.aidial.evaluation.runner;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Enforces the shared module's boundary contract (see Decision 8 in the
 * {@code evaluation-runner-core-module} change's {@code design.md}): DB-free, one-way dependency on the
 * EF backend, `client` does not depend on `job`, and every Spring component carries {@code @LogExecution}.
 */
class RunnerModuleConstraintsTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.epam.aidial.evaluation.runner");

    @Test
    void mustNotDependOnJdbcJooqOrFlyway() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.jooq..", "org.flywaydb..")
                .check(CLASSES);
    }

    @Test
    void mustNotDependOnTheEfBackend() {
        noClasses()
                .that()
                .resideInAPackage("com.epam.aidial.evaluation.runner..")
                .should()
                .dependOnClassesThat(resideInAPackage("com.epam.aidial.evaluation..")
                        .and(not(resideInAPackage("com.epam.aidial.evaluation.runner.."))))
                .check(CLASSES);
    }

    @Test
    void clientMustNotDependOnJob() {
        noClasses()
                .that()
                .resideInAPackage("com.epam.aidial.evaluation.runner.client..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.epam.aidial.evaluation.runner.job..")
                .check(CLASSES);
    }

    @Test
    void everySpringComponentMustBeAnnotatedWithLogExecution() {
        classes()
                .that()
                .areAnnotatedWith(Component.class)
                .or()
                .areAnnotatedWith(Service.class)
                .or()
                .areAnnotatedWith(Configuration.class)
                .should()
                .beAnnotatedWith(LogExecution.class)
                .check(CLASSES);
    }
}
