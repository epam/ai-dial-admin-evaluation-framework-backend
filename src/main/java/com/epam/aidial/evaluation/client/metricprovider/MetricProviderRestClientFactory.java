package com.epam.aidial.evaluation.client.metricprovider;

import java.util.Map;
import java.util.Optional;
import org.springframework.web.client.RestClient;

/**
 * Provides a RestClient per configured metric provider id. Built at startup from
 * metric-providers.providers; no user token propagation.
 */
public class MetricProviderRestClientFactory {

    private final Map<String, RestClient> clientsByProviderId;

    public MetricProviderRestClientFactory(Map<String, RestClient> clientsByProviderId) {
        this.clientsByProviderId = clientsByProviderId != null ? clientsByProviderId : Map.of();
    }

    public Optional<RestClient> getRestClient(String providerId) {
        return Optional.ofNullable(clientsByProviderId.get(providerId));
    }
}
