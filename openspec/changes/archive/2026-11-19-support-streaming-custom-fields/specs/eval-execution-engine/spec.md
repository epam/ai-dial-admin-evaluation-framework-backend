## ADDED Requirements

### Requirement: OpenAI streaming custom fields assembly

In OpenAI chat-completions mode, successful response assembly SHALL accumulate JSON objects from `choices[0].delta.custom_fields` and attach the result to `choices[0].message.custom_fields`. The extension SHALL be independent of `custom_content` and SHALL preserve arbitrary field names and JSON structures without requiring an annotations schema. A missing, null, or non-object extension payload SHALL contribute nothing. If no fields are accumulated, the assembled message SHALL omit `custom_fields`.

Status: **Implemented**

#### Scenario: Message event carries annotations
- **WHEN** an OpenAI chat-completions stream contains a `message` event whose `data.choices[0].delta.custom_fields` contains an annotations array and parsing succeeds
- **THEN** the assembled `choices[0].message.custom_fields.annotations` SHALL preserve the supplied annotations
- **AND** regular message content SHALL still be assembled from content deltas

#### Scenario: Both extensions are present
- **WHEN** a successful OpenAI stream contains both `delta.custom_content` and `delta.custom_fields`
- **THEN** the assembled message SHALL contain both independently accumulated extensions
- **AND** existing custom-content merging SHALL retain indexed attachments/stages, last-non-null scalar values, recursive object merging, and replacement of other arrays

#### Scenario: Extension contributes no fields
- **WHEN** all custom-fields payloads are absent, null, non-object values, empty objects, or objects containing only ignored null fields
- **THEN** the assembled message SHALL omit `custom_fields`

#### Scenario: Unsuccessful parse retains existing partial response
- **WHEN** parsing an OpenAI stream with custom fields returns a non-success status
- **THEN** the response body SHALL remain a JSON string containing accumulated text, without an extension envelope
- **AND** the parser execution status SHALL remain authoritative

### Requirement: Custom fields recursive merge semantics

Custom-fields accumulation SHALL recursively merge object properties, retain the latest non-null scalar, and append array elements in receipt order by default at every object nesting level. Null or absent properties SHALL NOT erase accumulated values. Null-valued object properties SHALL be ignored recursively, including when a new object is introduced or replaces a value of another type. An explicitly supplied object SHALL remain an object even when ignoring its null properties leaves it empty. These rules SHALL NOT normalize object properties inside array elements, which are copied atomically. Array elements SHALL be preserved as supplied, including nulls and duplicates; array elements SHALL NOT be merged by index or deduplicated. When incoming and existing non-null values have different JSON types, the incoming value SHALL replace the existing value. Accumulation and mutation of returned results SHALL NOT mutate input event payloads or previously accumulated state.

Status: **Implemented**

#### Scenario: Fresh nested object contains only null properties
- **WHEN** a fresh custom-fields accumulation receives `{"metadata":{"label":null}}`
- **THEN** accumulated custom fields SHALL be `{"metadata":{}}`, not omitted and not retaining the null label

#### Scenario: Null-only object replaces a scalar
- **WHEN** `{"metadata":"old"}` is followed by `{"metadata":{"label":null}}`
- **THEN** accumulated custom fields SHALL be `{"metadata":{}}`

#### Scenario: Atomic array objects retain supplied null properties
- **WHEN** a custom-fields accumulation receives `{"annotations":[{"label":null}]}`
- **THEN** accumulated annotations SHALL be `[{"label":null}]`

#### Scenario: Objects and scalars span chunks
- **WHEN** successive custom-fields objects contain `{"metadata":{"label":"first","count":1}}` and `{"metadata":{"label":"second","source":"synthetic"}}`
- **THEN** the accumulated metadata SHALL be `{"label":"second","count":1,"source":"synthetic"}`

#### Scenario: Arrays append without deduplication
- **WHEN** successive custom-fields payloads contain `{"annotations":[{"id":"a"}]}` and `{"annotations":[{"id":"a"},null,{"id":"b"}]}`
- **THEN** the accumulated annotations SHALL be `[{"id":"a"},{"id":"a"},null,{"id":"b"}]` in that order

#### Scenario: Nested arrays append
- **WHEN** successive custom-fields payloads contain `{"metadata":{"tags":["one"]}}` and `{"metadata":{"tags":["two"]}}`
- **THEN** the accumulated metadata tags SHALL be `["one","two"]`

#### Scenario: Null and empty append do not erase values
- **WHEN** `{"label":"retained","annotations":[1]}` is followed by `{"label":null,"annotations":[]}` under the default array behavior
- **THEN** the accumulated label SHALL remain `"retained"` and annotations SHALL remain `[1]`

#### Scenario: A non-null type change replaces the old value
- **WHEN** `{"detail":{"label":"old"}}` is followed by `{"detail":["new"]}`
- **THEN** accumulated detail SHALL be `["new"]`

#### Scenario: Input and output mutations are isolated
- **WHEN** a caller mutates an original payload after accumulation or mutates a returned accumulated result
- **THEN** subsequent accumulated results SHALL retain the values captured at accumulation time
- **AND** the original event payloads SHALL NOT have been modified by accumulation

### Requirement: Explicit custom fields array replacement

Code-level callers SHALL be able to select array replacement instead of the default append behavior for a custom-fields accumulation instance. In replacement mode, each incoming array SHALL replace the existing array at that property, including nested arrays and empty arrays. Object recursion, last-non-null scalar semantics, null ignoring, and type replacement SHALL remain unchanged. Normal streamed-response assembly SHALL use append behavior; no REST request field or application configuration property SHALL be required for this option.

Status: **Implemented**

#### Scenario: Explicit array replacement
- **WHEN** a code-level caller selects replacement and accumulates `{"annotations":[1]}` followed by `{"annotations":[2]}`
- **THEN** accumulated annotations SHALL be `[2]`

#### Scenario: Empty array clears previous elements
- **WHEN** a replacement-mode accumulation receives `{"annotations":[]}` after accumulating annotations
- **THEN** accumulated annotations SHALL be an empty array

#### Scenario: Replacement applies to nested arrays but not whole objects
- **WHEN** replacement-mode accumulation receives `{"metadata":{"label":"retained","tags":["one"]}}` followed by `{"metadata":{"tags":["two"]}}`
- **THEN** metadata SHALL be `{"label":"retained","tags":["two"]}`

## Implementation Notes

- Assembly: `evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/job/StreamingResponseAccumulator.java`.
- Extension merging: runner `.job` classes `CustomFieldsAccumulator`, `JsonObjectDeltaAccumulator`, `ArrayMergeMode`, and legacy `CustomContentAccumulator`.
- Executed unit coverage: corresponding `StreamingResponseAccumulatorTest`, `CustomFieldsAccumulatorTest`, `JsonObjectDeltaAccumulatorTest`, and `CustomContentAccumulatorTest` in `evaluation-runner-core/src/test/java/com/epam/aidial/evaluation/runner/job/`.
