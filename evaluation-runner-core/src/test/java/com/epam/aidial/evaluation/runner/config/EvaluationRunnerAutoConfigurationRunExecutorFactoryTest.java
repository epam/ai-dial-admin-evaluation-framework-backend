package com.epam.aidial.evaluation.runner.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link EvaluationRunnerAutoConfiguration#runExecutorFactory(Environment)} coverage.
 *
 * <p>{@code EvaluationRunnerAutoConfiguration} carries a class-level {@code @ComponentScan} of the
 * entire {@code com.epam.aidial.evaluation.runner} package (30+ beans), several of which need
 * infrastructure that only a real consumer application supplies — qualified {@code RestClient} beans
 * ({@code dialCoreTryOutRestClient}, {@code dialFileRestClient}), an {@code ObjectMapper}, a
 * {@code java.time.Clock}, an {@code OpenTelemetry} instance, and more beyond that (verified empirically:
 * stubbing those four still leaves further {@code UnsatisfiedDependencyException}s). Booting the full
 * autoconfiguration standalone in this DB-free module is therefore impractical and would mean
 * reimplementing the consumer application's entire bean graph just to test one bean method.
 *
 * <p>Instead: {@link DirectInvocationTests} calls the real
 * {@link EvaluationRunnerAutoConfiguration#runExecutorFactory(Environment)} method directly against a
 * {@link MockEnvironment}, covering the thread-mode decision itself. {@link OverrideSemanticsTests} proves
 * the {@code @ConditionalOnMissingBean} override contract using a minimal delegating configuration that
 * calls the same production method, without pulling in the full component scan.
 */
@DisplayName("EvaluationRunnerAutoConfiguration.runExecutorFactory")
class EvaluationRunnerAutoConfigurationRunExecutorFactoryTest {

    @Nested
    @DisplayName("direct invocation")
    class DirectInvocationTests {

        private final EvaluationRunnerAutoConfiguration autoConfiguration = new EvaluationRunnerAutoConfiguration();

        @Test
        @DisplayName("spring.threads.virtual.enabled=true yields a virtual-mode factory")
        void virtualThreadsEnabled_yieldsVirtualModeFactory() {
            MockEnvironment environment = new MockEnvironment().withProperty("spring.threads.virtual.enabled", "true");

            RunExecutorFactory factory = autoConfiguration.runExecutorFactory(environment);

            assertThat(factory.isVirtualThreads()).isTrue();
        }

        @Test
        @DisplayName("spring.threads.virtual.enabled=false yields a platform-mode factory")
        void virtualThreadsDisabled_yieldsPlatformModeFactory() {
            MockEnvironment environment = new MockEnvironment().withProperty("spring.threads.virtual.enabled", "false");

            RunExecutorFactory factory = autoConfiguration.runExecutorFactory(environment);

            assertThat(factory.isVirtualThreads()).isFalse();
        }

        @Test
        @DisplayName("spring.threads.virtual.enabled unset defaults to platform mode (Boot default)")
        void virtualThreadsUnset_defaultsToPlatformMode() {
            MockEnvironment environment = new MockEnvironment();

            RunExecutorFactory factory = autoConfiguration.runExecutorFactory(environment);

            assertThat(factory.isVirtualThreads()).isFalse();
        }
    }

    @Nested
    @DisplayName("@ConditionalOnMissingBean override semantics")
    class OverrideSemanticsTests {

        private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DelegatingRunExecutorFactoryConfiguration.class));

        @Test
        @DisplayName("autoconfigured bean is used when no user bean is defined")
        void noUserBean_usesAutoconfiguredFactory() {
            contextRunner
                    .withPropertyValues("spring.threads.virtual.enabled=true")
                    .run(context -> {
                        assertThat(context).hasSingleBean(RunExecutorFactory.class);
                        assertThat(context.getBean(RunExecutorFactory.class).isVirtualThreads())
                                .isTrue();
                    });
        }

        @Test
        @DisplayName("a user-defined RunExecutorFactory bean wins over the autoconfigured one")
        void userDefinedBean_winsOverAutoconfigured() {
            contextRunner
                    .withPropertyValues("spring.threads.virtual.enabled=true")
                    .withUserConfiguration(UserFactoryConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasSingleBean(RunExecutorFactory.class);
                        assertThat(context.getBean(RunExecutorFactory.class).isVirtualThreads())
                                .isFalse();
                    });
        }
    }

    /**
     * Registers only the {@code runExecutorFactory} bean method — delegating to the real
     * {@link EvaluationRunnerAutoConfiguration} instance — without the class-level {@code @ComponentScan}
     * that makes booting the whole autoconfiguration standalone impractical (see class Javadoc).
     */
    @AutoConfiguration
    static class DelegatingRunExecutorFactoryConfiguration {

        private final EvaluationRunnerAutoConfiguration delegate = new EvaluationRunnerAutoConfiguration();

        @Bean
        @ConditionalOnMissingBean
        RunExecutorFactory runExecutorFactory(Environment environment) {
            return delegate.runExecutorFactory(environment);
        }
    }

    @Configuration
    static class UserFactoryConfiguration {

        @Bean
        RunExecutorFactory runExecutorFactory() {
            return new RunExecutorFactory(false);
        }
    }
}
