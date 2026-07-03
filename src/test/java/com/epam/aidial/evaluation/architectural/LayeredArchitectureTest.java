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
    private static final String EXPERIMENTAL_WEB_PACKAGE = "com.epam.aidial.evaluation.experimental.query.web..";
    private static final String EXPERIMENTAL_SERVICE_PACKAGE =
            "com.epam.aidial.evaluation.experimental.query.service..";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackagesOf(Application.class);

    @Test
    void testLayeredArchitecture() {
        // The experimental query packages mirror the standard layering under `experimental.query`:
        // experimentalWeb -> experimentalService -> (service, data). They may consume the stable
        // service/data layers but nothing outside `experimental.query` may depend on them.
        layeredArchitecture()
                .consideringAllDependencies()
                .layer("web")
                .definedBy(WEB_PACKAGE)
                .layer("service")
                .definedBy(SERVICE_PACKAGE)
                .layer("data")
                .definedBy(DATA_PACKAGE)
                .layer("configuration")
                .definedBy(CONFIG_PACKAGE)
                .layer("experimentalWeb")
                .definedBy(EXPERIMENTAL_WEB_PACKAGE)
                .layer("experimentalService")
                .definedBy(EXPERIMENTAL_SERVICE_PACKAGE)
                .whereLayer("web")
                .mayOnlyBeAccessedByLayers("configuration")
                .whereLayer("service")
                .mayOnlyBeAccessedByLayers("web", "configuration", "experimentalService")
                .whereLayer("data")
                .mayOnlyBeAccessedByLayers("service", "configuration", "experimentalService")
                .whereLayer("experimentalWeb")
                .mayOnlyBeAccessedByLayers("configuration")
                .whereLayer("experimentalService")
                .mayOnlyBeAccessedByLayers("experimentalWeb", "configuration")
                .check(CLASSES);
    }
}
