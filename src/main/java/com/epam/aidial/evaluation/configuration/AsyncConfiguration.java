package com.epam.aidial.evaluation.configuration;

import com.epam.aidial.evaluation.configuration.properties.testsuite.TestSuiteRunProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@LogExecution
public class AsyncConfiguration {

    @Bean(name = "testSuiteRunExecutor")
    public ThreadPoolTaskExecutor testSuiteRunExecutor(TestSuiteRunProperties props) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getExecutor().getCorePoolSize());
        executor.setMaxPoolSize(props.getExecutor().getMaxPoolSize());
        executor.setQueueCapacity(props.getExecutor().getQueueCapacity());
        executor.setThreadNamePrefix("test-suite-run-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
