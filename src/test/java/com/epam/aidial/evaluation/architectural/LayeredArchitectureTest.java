package com.epam.aidial.evaluation.architectural;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.epam.aidial.evaluation.Application;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

@AnalyzeClasses(packagesOf = Application.class, importOptions = ImportOption.DoNotIncludeTests.class)
public class LayeredArchitectureTest {

    private static final String WEB_PACKAGE = "com.epam.aidial.evaluation.web..";
    private static final String SERVICE_PACKAGE = "com.epam.aidial.evaluation.service..";
    private static final String DATA_PACKAGE = "com.epam.aidial.evaluation.data..";
    private static final String CONFIG_PACKAGE = "com.epam.aidial.evaluation.configuration..";

    @ArchTest
    public static void testLayeredArchitecture(JavaClasses classes) {
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
                .whereLayer("web")
                .mayOnlyBeAccessedByLayers("configuration")
                .whereLayer("service")
                .mayOnlyBeAccessedByLayers("web", "configuration")
                .whereLayer("data")
                .mayOnlyBeAccessedByLayers("service", "configuration")
                .check(classes);
    }
}
