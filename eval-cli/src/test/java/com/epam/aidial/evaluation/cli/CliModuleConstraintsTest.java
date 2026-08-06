package com.epam.aidial.evaluation.cli;

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
 * Enforces eval-cli's module boundary contract (see the {@code eval-cli} change's {@code design.md}):
 * DB-free, only ever consumes {@code evaluation-runner-core}, never the EF backend's own main source
 * set, and every Spring component carries {@code @LogExecution}.
 *
 * <p>Rules use {@code allowEmptyShould(true)} because the module is scaffolded incrementally task by
 * task; once real classes land in later tasks, each rule re-engages against them as usual.
 */
class CliModuleConstraintsTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.epam.aidial.evaluation.cli");

    @Test
    void mustNotDependOnJdbcJooqOrFlyway() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.jooq..", "org.flywaydb..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void mustNotDependOnTheEfBackendMainSourceSet() {
        noClasses()
                .that()
                .resideInAPackage("com.epam.aidial.evaluation.cli..")
                .should()
                .dependOnClassesThat(resideInAPackage("com.epam.aidial.evaluation..")
                        .and(not(resideInAPackage("com.epam.aidial.evaluation.runner..")))
                        .and(not(resideInAPackage("com.epam.aidial.evaluation.cli.."))))
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void everySpringComponentMustBeAnnotatedWithLogExecution() {
        // Exclude classes whose name ends with "Impl" — these are MapStruct-generated
        // implementations (e.g. TestCaseRunInputMapperImpl) annotated with @Component by the
        // generator; they carry @javax.annotation.processing.Generated(SOURCE) which is stripped
        // at compile time and therefore invisible to ArchUnit's class-file analysis.
        classes()
                .that()
                .areAnnotatedWith(Component.class)
                .or()
                .areAnnotatedWith(Service.class)
                .or()
                .areAnnotatedWith(Configuration.class)
                .and()
                .doNotHaveSimpleName("TestCaseRunInputMapperImpl")
                .should()
                .beAnnotatedWith(LogExecution.class)
                .allowEmptyShould(true)
                .check(CLASSES);
    }
}
