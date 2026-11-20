## 1. Shared JSON merging and custom-content compatibility

- [x] 1.1 Add synthetic compatibility cases in `evaluation-runner-core/src/test/java/com/epam/aidial/evaluation/runner/job/CustomContentAccumulatorTest.java` for ordinary-array replacement, indexed partials, and source/result immutability; explicitly verify that initial ordinary-object/indexed-element copies preserve nested null properties, later merges ignore null overwrites, and newly introduced nested objects during later merges retain their copied null properties; done: `./gradlew :evaluation-runner-core:test --tests '*CustomContentAccumulatorTest'` passes before extraction.
- [x] 1.2 Introduce package-private `ArrayMergeMode` and `JsonObjectDeltaAccumulator` in runner `.job`, with tests for recursive merging, append/replace, type transitions, ignored nulls, preserved duplicate/null array elements, and defensive snapshots; done: new focused unit tests are observed failing before implementation and pass afterward.
- [x] 1.3 Delegate shared merging from `CustomContentAccumulator` without changing its initial-copy paths, indexed attachments/stages bookkeeping, unindexed order, or omission rules; done: targeted shared-merge and custom-content unit tests pass with unchanged legacy expectations.

## 2. Custom-fields accumulation

- [x] 2.1 Add `CustomFieldsAccumulatorTest` using synthetic fixtures for object/scalar merging, nested array append, omission of absent/null/non-object/empty extensions, fresh nested null-only objects, scalar-to-null-only-object replacement, preservation of null properties in atomic array elements, and input/output immutability; implement the default-append `CustomFieldsAccumulator` wrapper; done: tests fail for missing behavior before implementation and pass afterward.
- [x] 2.2 Add explicit `ArrayMergeMode.REPLACE` construction to `CustomFieldsAccumulator`, with tests for replacement, empty-array clearing, nested replacement, and preserved sibling object fields; done: targeted replacement tests pass without changing default-append expectations.

## 3. OpenAI assembly integration

- [x] 3.1 Extract `choices[0].delta` once per event in `StreamingResponseAccumulator` and feed independent text, custom-content, and custom-fields accumulators; attach non-empty custom fields on successful assembly; done: `./gradlew :evaluation-runner-core:test --tests 'com.epam.aidial.evaluation.runner.job.StreamingResponseAccumulatorTest$OpenAiMode.accumulate_customFieldsAnnotations_preservedOntoAssembledMessage'` passes with the existing synthetic message-event regression.
- [x] 3.2 Extend `StreamingResponseAccumulatorTest` for multi-chunk append, simultaneous extensions, omitted/invalid custom fields, and non-success parsed-result text-only fallback; done: `./gradlew :evaluation-runner-core:test --tests '*StreamingResponseAccumulatorTest*'` passes, including existing other-mode tests.

## 4. Documentation, specs, and final verification

- [x] 4.1 Update `docs/key-packages.md` and relevant runner/streaming pattern documentation to describe custom-fields assembly, shared merging, and the internal replacement option; done: documented paths and option scope match the implementation, with no new configuration or API request field implied.
- [x] 4.2 Sync this change's added requirements into `openspec/specs/eval-execution-engine/spec.md` and mark them Implemented only after corresponding tests pass; done: `/opsx:sync` preserves existing requirements and `openspec validate support-streaming-custom-fields --strict` passes; assess the spec index and update only if its summary becomes materially inaccurate.
- [x] 4.3 Run `./gradlew :evaluation-runner-core:spotlessApply :evaluation-runner-core:spotlessCheck :evaluation-runner-core:checkstyleMain :evaluation-runner-core:checkstyleTest :evaluation-runner-core:test`; done: the full runner test suite and module static checks pass, or failures are explicitly reported and tasks remain incomplete.

## Verification notes

- The coordinator's initial `./gradlew clean build` passed the runner suite, runner Spotless check, and runner Checkstyle main, but failed runner Checkstyle test on four fixture line-length violations. The implementer corrected only those fixtures, formatted and compiled, then passed runner Checkstyle test and the affected `CustomContentAccumulatorTest` and `JsonObjectDeltaAccumulatorTest` tests.
- The coordinator's subsequent root/CLI checks and tests, CLI bootJar, and runner Spotless check passed (`BUILD SUCCESSFUL`). The literal 4.3 command and full clean build were not rerun, per coordinator cadence; aggregate results satisfy 4.3.
- Groups 1 and 2 did not run pre-implementation red or pre-extraction test runs, despite the earlier task wording, per coordinator cadence.
