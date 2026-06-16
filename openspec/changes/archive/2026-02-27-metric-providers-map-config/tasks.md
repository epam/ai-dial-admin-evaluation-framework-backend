## 1. Configuration Properties

- [x] 1.1 Update `MetricProviderProperties`: replace `List<ProviderEntry> providers` with `Map<String, ProviderEntry> providers`. Remove the `id` field from `ProviderEntry`. Update `@NotNull` / `@Valid` annotations for map binding.
- [x] 1.2 Update `application.yml`: restructure `metric-providers` from list to map keyed by provider id. Default provider key is `dial` with `base-url: ${METRIC_PROVIDERS_DIAL_BASE_URL:http://localhost:8086}`. Keep `sync` section unchanged.

## 2. Code consuming providers

- [x] 2.1 Update `MetricProviderRestClientConfiguration.metricProviderRestClientFactory()`: iterate `properties.getProviders().entrySet()` instead of list; use `entry.getKey()` as provider id and `entry.getValue()` for config.
- [x] 2.2 Update `MetricProviderSyncJob.run()`: iterate `metricProviderProperties.getProviders().entrySet()` instead of list; use map key as provider id.

## 3. Documentation

- [x] 3.1 Update `docs/configuration.md`: replace list-based provider docs with map-based structure, update env variable names from `METRIC_PROVIDERS_PROVIDERS_0_*` to `METRIC_PROVIDERS_DIAL_*` pattern, update YAML example.

## 4. Tests

- [x] 4.1 Update `PostgresFunctionalTests.MetricProviderSyncJobTests` `@TestPropertySource`: replace `metric-providers.providers[0].id` and `metric-providers.providers[0].base-url` with map-based properties (e.g. `metric-providers.sync-test-provider.base-url`).
