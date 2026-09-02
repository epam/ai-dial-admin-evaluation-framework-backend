# OpenAPI Examples — delta for `add-deployment-lookup-by-id`

## ADDED Requirements

### Requirement: Example files SHALL resolve for trailing-wildcard endpoint mappings

Endpoints whose mapping ends in a trailing wildcard (used for path values that may themselves contain slashes, e.g. DIAL Core deployment IDs) are registered in the OpenAPI document with the `/**` intact. Example files SHALL still be injected for such operations: the example-file key SHALL be derived from the registered path with a trailing `/**` **dropped**, so that no example filename ever contains `*` (illegal in a Windows filename and hostile to shell globbing).

Concretely, `/api/v1/deployments/all/**` SHALL resolve examples named `api-v1-deployments-all-{method}-response-{status}-{name}.json`, and `/api/v1/deployments/{deploymentType}/**` SHALL resolve `api-v1-deployments-deploymentType-{method}-response-{status}-{name}.json`.

Because a mismatched key fails silently — the customizer simply finds no resource and the operation ships with no examples — at least one endpoint using a trailing-wildcard mapping SHALL be covered by a test asserting its examples are present in the generated OpenAPI document.

Status: **Implemented**

Implementation notes:
- Key derivation: `configuration.OpenApiExampleCustomizer#pathToKey` strips a trailing `/**` before the existing `/`→`-` and brace-stripping transforms.
- Regression cover: `DeploymentFunctionalTests#openApiSpecCarriesByIdLookupExamples` reads `/v3/api-docs` and asserts the `minimal` and `full` examples are injected for `/api/v1/deployments/all/**`.

#### Scenario: Examples resolve for a wildcard mapping
- **WHEN** an endpoint is mapped with a trailing `/**` and example files exist under the key formed by dropping that wildcard
- **THEN** the generated OpenAPI document SHALL carry those examples on the operation

#### Scenario: No example filename contains a wildcard character
- **WHEN** example files are added for a trailing-wildcard endpoint
- **THEN** their filenames SHALL contain no `*` character

#### Scenario: Silent example loss is guarded by a test
- **WHEN** a mapping's path shape changes such that its example key no longer matches its files
- **THEN** the test asserting examples in the generated OpenAPI document SHALL fail, rather than the endpoint silently shipping without examples
