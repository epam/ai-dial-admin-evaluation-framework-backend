package com.epam.aidial.evaluation.architectural;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.epam.aidial.evaluation.Application;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

public class LayeredArchitectureTest {

    private static final String WEB_PACKAGE = "com.epam.aidial.evaluation.web..";
    private static final String SERVICE_PACKAGE = "com.epam.aidial.evaluation.service..";
    private static final String DATA_PACKAGE = "com.epam.aidial.evaluation.data..";
    private static final String CONFIG_PACKAGE = "com.epam.aidial.evaluation.configuration..";
    private static final String QUERY_WEB_PACKAGE = "com.epam.aidial.evaluation.query.web..";
    private static final String QUERY_SERVICE_PACKAGE = "com.epam.aidial.evaluation.query.service..";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackagesOf(Application.class);

    @Test
    void testLayeredArchitecture() {
        // The Query DSL (`query.web` / `query.service`) is folded into the standard web/service layers
        // rather than modeled as its own layer: query.web classes are controllers (part of `web`), and
        // query.service classes are called from both `web` and `service` and themselves call
        // into `service`/`data` — the same access pattern `web`/`service` already have with each other.
        // `query.model` is deliberately layer-neutral: it is a pure carrier package (the typed query AST)
        // with no behaviour, reused as an outbound payload by clients such as `client.dialadas`.
        layeredArchitecture()
                .consideringAllDependencies()
                .layer("web")
                .definedBy(WEB_PACKAGE, QUERY_WEB_PACKAGE)
                .layer("service")
                .definedBy(SERVICE_PACKAGE, QUERY_SERVICE_PACKAGE)
                .layer("data")
                .definedBy(DATA_PACKAGE)
                .layer("configuration")
                .definedBy(CONFIG_PACKAGE)
                .whereLayer("web")
                .mayOnlyBeAccessedByLayers("configuration")
                .whereLayer("service")
                .mayOnlyBeAccessedByLayers("web", "configuration")
                .whereLayer("data")
                .mayOnlyBeAccessedByLayers("service", "configuration")
                .check(CLASSES);
    }
}
