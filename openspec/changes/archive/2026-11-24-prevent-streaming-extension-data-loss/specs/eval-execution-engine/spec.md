## MODIFIED Requirements

### Requirement: OpenAI streaming custom fields assembly

In OpenAI chat-completions mode, successful response assembly SHALL accumulate JSON objects from `choices[0].delta.custom_fields` and attach the result to `choices[0].message.custom_fields`. The extension SHALL be independent of `custom_content` and SHALL preserve arbitrary field names and JSON structures without requiring an annotations schema. A missing, null, or non-object extension payload SHALL contribute nothing. If no fields are accumulated, the assembled message SHALL omit `custom_fields`.

Status: **Implemented**

#### Scenario: Message event carries annotations
- **WHEN** an OpenAI chat-completions stream contains a `message` event whose `data.choices[0].delta.custom_fields` contains an annotations array and parsing succeeds
- **THEN** the assembled `choices[0].message.custom_fields.annotations` SHALL preserve the supplied annotations according to field-aware annotation merging
- **AND** regular message content SHALL still be assembled from content deltas

#### Scenario: Both extensions are present
- **WHEN** a successful OpenAI stream contains both `delta.custom_content` and `delta.custom_fields`
- **THEN** the assembled message SHALL contain both independently accumulated extensions
- **AND** custom-content merging SHALL retain indexed attachments/stages and existing generic merge rules, except for the explicitly defined stage-text, stage-attachment, annotation, and snapshot policies

#### Scenario: Extension contributes no fields
- **WHEN** all custom-fields payloads are absent, null, non-object values, empty objects, or objects containing only ignored null fields
- **THEN** the assembled message SHALL omit `custom_fields`

#### Scenario: Unsuccessful parse retains existing partial response
- **WHEN** parsing an OpenAI stream with custom fields returns a non-success status
- **THEN** the response body SHALL remain a JSON string containing accumulated text, without an extension envelope
- **AND** the parser execution status SHALL remain authoritative

### Requirement: Custom fields recursive merge semantics

Custom-fields accumulation SHALL recursively merge object properties, retain the latest non-null scalar, and append array elements in receipt order by default at every object nesting level. Null or absent properties SHALL NOT erase accumulated values. Outside specialized annotation objects, null-valued object properties SHALL be ignored recursively, including when a new object is introduced or replaces a value of another type. An explicitly supplied object SHALL remain an object even when ignoring its null properties leaves it empty. Generic array elements SHALL be copied atomically, preserving nulls and duplicates without index merging or deduplication; the top-level `annotations` array in default append mode SHALL instead follow field-aware annotation merging. When incoming and existing non-null values have different JSON types, the incoming value SHALL replace the existing value. Accumulation and mutation of returned results SHALL NOT mutate input event payloads or previously accumulated state.

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
- **THEN** the accumulated annotations SHALL be `[{"id":"a"},{"id":"a"},null,{"id":"b"}]` in that order because arbitrary `id` fields are not recognized annotation identities

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

## ADDED Requirements

### Requirement: Field-aware stage delta accumulation

Successful OpenAI streaming assembly SHALL concatenate string fragments of stage `name` and `content` by stage index in receipt order, including duplicate fragments verbatim. Null, absent, and empty-string updates to the named text fields SHALL always contribute no update, even when the existing value has a different JSON type; this no-op rule takes precedence over type replacement. Stage attachments SHALL merge by their own index within each stage, concatenating string `title` and `data` fragments and preserving omitted metadata. Existing numeric-index ordering and unindexed-entry append behavior SHALL apply to stages and their attachments. Other stage/attachment fields SHALL retain last-non-null scalar and recursive-object behavior. A non-null type change SHALL replace rather than stringify the old value. These text policies SHALL NOT apply to top-level attachments or arbitrary extension strings.

Status: **Implemented**

#### Scenario: Stage name and content arrive in repeated fragments
- **WHEN** stage index 0 receives `name="Checking "`, `content="Read "`, then null/empty text updates, then `name="inventory"`, `content="the list."`, and `status="completed"`
- **THEN** its assembled name SHALL be `"Checking inventory"`, content SHALL be `"Read the list."`, and status SHALL be `"completed"`

#### Scenario: Stage attachment fragments retain earlier entries and metadata
- **WHEN** a stage receives attachment index 0 with `title="re"`, `data="AA"`, `url="files/report"`, then index 1, then index 0 with `title="port"`, `data="BB"`
- **THEN** both attachments SHALL remain and index 0 SHALL contain `title="report"`, `data="AABB"`, and the original URL
- **AND** the same attachment index in another stage SHALL remain independent

#### Scenario: Empty text takes precedence over type replacement
- **WHEN** a stage name or stage attachment title/data holds numeric value `7`, then receives `""`, then receives non-empty string `"text"`
- **THEN** the empty update SHALL preserve `7` and the non-empty update SHALL replace it with `"text"` without coercion

### Requirement: Field-aware annotation delta accumulation

Top-level `annotations` arrays under `custom_content` and default-append `custom_fields` SHALL accumulate independently, preserving their original locations and wire shapes. Objects SHALL merge only when their identities match: a non-negative integral numeric `index` takes precedence; otherwise object-shaped `target` and `target.selector` with exact `target.selector.type="html_tag"` and non-empty string `target.selector.id` define identity. An invalid/missing index SHALL permit valid selector fallback; entries with neither valid identity SHALL remain unkeyed. `CssSelector` and arbitrary `id` properties SHALL NOT define identity. Numeric-index and selector-ID identities SHALL be distinct. Unknown, invalid-identity, scalar, and null entries SHALL append without filtering or deduplication. First-seen order SHALL remain stable. Matched objects SHALL recursively preserve omitted/unknown properties, concatenate string `body.title`/`body.quote` fragments, append other nested arrays atomically, and retain last-non-null values for other scalars. Initial objects and newly introduced or replacement nested annotation objects SHALL be copied preserving supplied null properties; subsequent null updates to existing properties SHALL NOT erase values. Empty-string updates to `body.title`/`body.quote` SHALL always contribute no update, taking precedence over type replacement. Non-null type changes SHALL replace the old value. Empty incoming annotation arrays SHALL NOT clear accumulated annotations. Explicit custom-fields replacement mode SHALL remain unchanged, including replacing its annotations array and clearing it with an empty array.

Status: **Implemented**

#### Scenario: Indexed partial annotation assembles without losing unknown data
- **WHEN** either extension receives `{"annotations":[{"index":0,"body":{"quote":"Read ","extra":{"a":1}}}]}` then `{"annotations":[{"index":0,"body":{"quote":"the list.","extra":{"b":2}}}]}`
- **THEN** one annotation SHALL contain `body.quote="Read the list."` and `body.extra={"a":1,"b":2}` in that same extension

#### Scenario: Selector identities merge but unrelated entries remain
- **WHEN** an annotations array receives `{"target":{"selector":{"type":"html_tag","id":"ref-a"}},"body":{"quote":"Read "}}`, then `{"index":-1,"target":{"selector":{"type":"html_tag","id":"ref-a"}},"body":{"quote":"the list."}}`, followed by selector ID `"ref-b"`, two entries with `{"target":{"selector":{"type":"CssSelector","id":"ref-a"}}}`, and null
- **THEN** only the two HTML-tag `"ref-a"` fragments SHALL merge into quote `"Read the list."`; `"ref-b"`, both CSS-selector entries, and null SHALL remain in first-seen order
- **AND** an indexed annotation SHALL NOT merge with a selector-only annotation even when index and ID have similar textual values

#### Scenario: Matched annotation receives a new null-containing object
- **WHEN** `{"index":0,"body":{"quote":"Read "}}` receives `{"index":0,"body":{"extra":{"optional":null}}}` and then `{"index":0,"body":{"extra":null,"quote":null}}`
- **THEN** the merged annotation SHALL retain `body.extra={"optional":null}` and `body.quote="Read "`

#### Scenario: Nulls and empty arrays do not erase annotations
- **WHEN** an accumulated annotation receives null text/properties followed by an empty annotations array
- **THEN** its existing text/properties SHALL remain unchanged

#### Scenario: Explicit replacement remains generic
- **WHEN** explicit custom-fields array replacement receives two arrays with matching indexed annotations and then an empty array
- **THEN** each incoming array SHALL replace the preceding array without concatenation, and the final annotations SHALL be empty

### Requirement: Custom-content snapshot field replacement

Non-null object values of top-level `custom_content.state` and `custom_content.form_schema` SHALL replace the entire previous value with a defensive copy, preserving the incoming object's explicit null properties. Absent or null values SHALL contribute no update. Other custom-content objects SHALL retain existing recursive merge semantics. This policy SHALL NOT apply to similarly named fields under `custom_fields` or inside nested objects.

Status: **Implemented**

#### Scenario: Snapshot replacement removes stale keys
- **WHEN** either snapshot field receives `{"old":1,"keep":2}` followed by `{"keep":3,"optional":null}`
- **THEN** its assembled value SHALL be `{"keep":3,"optional":null}` without `old`
- **AND** a subsequent absent/null snapshot update SHALL preserve that value

#### Scenario: Preview and evaluation use the same field policies
- **WHEN** successful try-it-out and evaluation assemble the same synthetic OpenAI extension stream
- **THEN** their assembled bodies SHALL contain equivalent stage text, stage attachments, annotations, and snapshot values
- **AND** try-it-out's parsed events SHALL remain unchanged

## Implementation notes

Runner accumulation: `evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/job/{CustomContentAccumulator,CustomFieldsAccumulator,AnnotationDeltaAccumulator,JsonObjectDeltaAccumulator}.java`; assembly: `StreamingResponseAccumulator.java` in the same package. Synthetic runner regressions and root `TryItOutServiceTest` / `TryItOutFunctionalTests` cover the policies and preview parity.
