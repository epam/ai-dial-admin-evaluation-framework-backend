package com.epam.aidial.evaluation.architectural;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.web.security.apikey.ApiKeyCache;
import com.epam.aidial.evaluation.web.security.apikey.CoreApiKeyIntrospector;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The opt-in {@code CustomizableTraceInterceptor} (see {@code app.customizable-trace-interceptor} in
 * {@code application.yml}) renders every public method argument of a {@code @LogExecution} bean
 * verbatim. Components whose public API receives a plaintext secret as a {@link String} must
 * therefore stay unannotated, otherwise enabling the tracer writes the secret to the log.
 */
@DisplayName("Secret-handling components are excluded from execution tracing")
class SecretHandlingLoggingTest {

    private static final JavaClasses CLASSES =
            new ClassFileImporter().importPackages("com.epam.aidial.evaluation.web.security");

    @Test
    @DisplayName("Components that receive a plaintext API key are not annotated with @LogExecution")
    void plaintextApiKeyComponentsAreNotTraced() {
        classes()
                .that()
                .belongToAnyOf(CoreApiKeyIntrospector.class, ApiKeyCache.class)
                .should()
                .notBeAnnotatedWith(LogExecution.class)
                .because("their public methods take the raw API key and the trace advisor logs arguments verbatim")
                .check(CLASSES);
    }
}
