package com.epam.aidial.evaluation.configuration;

import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AzureAuthConfig {

    @Bean
    @ConditionalOnProperty(value = "auth.azure.type", havingValue = "managed")
    public ManagedIdentityCredential managedIdentityCredential() {
        return new ManagedIdentityCredentialBuilder().build();
    }
}
