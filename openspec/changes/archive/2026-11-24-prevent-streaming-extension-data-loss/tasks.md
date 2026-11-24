## 1. Field-aware runner accumulation

- [x] 1.1 Update runner `.job` stage and nested attachment accumulation; verify `StageTextDeltaRegressionTest` and synthetic `CustomContentAccumulatorTest` cases pass for concatenation, nulls, duplicates, ordering, cross-stage isolation, and type changes.
- [x] 1.2 Add shared annotation merging for both extensions; verify synthetic tests cover index/HTML-tag identity, independent locations, unkeyed/scalar/null preservation, unknown nested properties/arrays, text fragments, and empty arrays.
- [x] 1.3 Add custom-content state/form_schema snapshot replacement; verify snapshot tests remove stale keys, preserve incoming explicit nulls, ignore null updates, and leave nested/custom-fields namesakes generic.
- [x] 1.4 Preserve generic semantics, explicit custom-fields REPLACE mode, non-array type transitions, and input/result isolation; verify existing and new runner accumulator tests pass.
- [x] 1.5 Cover the combined stream in `StreamingResponseAccumulatorTest`; verify expected assembled values and unsuccessful-parse compatibility, then run the targeted runner accumulator/streaming test classes once, `:evaluation-runner-core:spotlessApply`, `:evaluation-runner-core:checkstyleMain`, and `:evaluation-runner-core:checkstyleTest` successfully.

## 2. Preview parity and documentation

- [x] 2.1 Add synthetic parity coverage in `TryItOutServiceTest` and the existing `TryItOutFunctionalTests` fixture/support path; verify expected full assembled extension values and unchanged parsed events, execute the targeted unit class and `PostgresFunctionalTests$TryItOutTests` successfully.
- [x] 2.2 Update `docs/patterns/evaluation-runner-core-module.md` and applicable assembled-response OpenAPI examples; verify documented policies match tests and run OpenAPI example coverage if examples change. No config/schema/index maintenance expected.

Coordinator closeout: after code review and drift verification, synchronize the delta via `/opsx:sync`, set main-spec statuses accurately, validate strictly, and run the one full `./gradlew clean build` before archive. No implementer performs the full build or spec synchronization.
