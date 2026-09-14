## ADDED Requirements

### Requirement: A response media type MUST be declared for response examples to attach

An endpoint's mapping annotation MUST declare `produces = MediaType.APPLICATION_JSON_VALUE` (or otherwise pin its response content type to `application/json`) for any response example to attach. `OpenApiExampleCustomizer` injects response examples by looking up the operation's `application/json` media type on the generated `ApiResponse` content (`OpenApiExampleCustomizer.MEDIA_TYPE_JSON`). Springdoc only creates that media-type entry when the mapping declares `produces` accordingly; without it springdoc documents the response under `*/*`, the customizer's lookup finds no `application/json` entry, and the operation ships with no response examples — even when the example JSON files exist and are named exactly per the `{pathKey}-{method}-response-{status}-{name}.json` convention. A correctly named example file is therefore necessary but not sufficient.

Status: **Implemented**

#### Scenario: Endpoint gains response examples via produces attribute

- **WHEN** a controller method returns a response body and its mapping annotation declares `produces = MediaType.APPLICATION_JSON_VALUE`
- **THEN** springdoc SHALL register the `application/json` media type on that operation's response, and `OpenApiExampleCustomizer` SHALL find and inject matching example files there

#### Scenario: Missing produces attribute silently drops examples

- **WHEN** a controller method returns a response body but its mapping annotation omits `produces`
- **THEN** springdoc SHALL document the response under `*/*` with no `application/json` media-type entry, and `OpenApiExampleCustomizer` SHALL find no place to attach response examples, regardless of whether correctly named example files exist on the classpath

## Implementation notes

- `RunMetricSnapshotController` and `RunMetricSnapshotDeprecatedController` both declare `produces = MediaType.APPLICATION_JSON_VALUE` on their `@GetMapping`/`@PostMapping` methods (`RunMetricSnapshotController.java:35,42`; `RunMetricSnapshotDeprecatedController.java:42,53`) specifically so their response examples attach. Renaming the example JSON files to the `OpenApiExampleCustomizer` convention alone would not have been sufficient without this.
- This requirement is additive guidance for future endpoints; it does not change the filename convention already specified in the baseline `openapi-examples` spec's "Example files SHALL resolve for trailing-wildcard endpoint mappings" requirement.
